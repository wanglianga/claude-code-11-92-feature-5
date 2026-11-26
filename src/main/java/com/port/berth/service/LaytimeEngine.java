package com.port.berth.service;

import com.port.berth.domain.Laytime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 滞期计时引擎（纯函数，不依赖 Spring / 数据库，便于单元测试与任意时刻回放）。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>只使用 {@code received_at <= asOf} 的回执 —— 回放 12:00 时看不到 12:00 之后送达的信息；</li>
 *   <li>计时区间 = [准备就绪 + 免计时长, min(完工时间, asOf)] —— 尚未发生的时长不计入；</li>
 *   <li>港方确认的天气停工暂停计时（WEATHER_EXCEPTED）；</li>
 *   <li>设备修复回执证明的故障窗口仍计时，但归因港方设备（CRANE_FAULT）；</li>
 *   <li>原因待证的停工暂计时（PENDING_EVIDENCE），待迟到凭证到达后改判；</li>
 *   <li>计划停工预告（PLANNED_STOP）只作展示，绝不参与区段分类 —— 计划不得替代实际凭证。</li>
 * </ul>
 */
public final class LaytimeEngine {

    private LaytimeEngine() {}

    /**内部分类区间（带证据事件 id）*/
    private record Range(LocalDateTime start, LocalDateTime end, String cls,
                         List<Long> evidenceIds) {}

    /**
     * 计算截至 asOf 的账单。
     *
     * @param contract 合同条款（免计时长、费率）
     * @param events   该船次全部回执（方法内部按送达时间过滤与排序）
     * @param asOf     信息截止时刻（回放时点）
     */
    public static Laytime.Statement compute(Laytime.Contract contract,
                                            List<Laytime.Event> events,
                                            LocalDateTime asOf) {
        List<Laytime.Event> visible = events.stream()
                .filter(e -> !e.receivedAt().isAfter(asOf))
                .sorted(Comparator.comparing(Laytime.Event::occurredAt)
                        .thenComparing(Laytime.Event::receivedAt)
                        .thenComparing(Laytime.Event::id))
                .toList();

        Laytime.Event ready = firstOfType(visible, Laytime.READY);
        if (ready == null) {
            return new Laytime.Statement(Laytime.PROVISIONAL, asOf, null, null, null,
                    0, BigDecimal.ZERO.setScale(2), List.of(), List.of(),
                    "尚未收到准备就绪（NOR）回执，无法起计");
        }
        LocalDateTime readyAt = ready.occurredAt();
        LocalDateTime countFrom = readyAt.plusMinutes(contract.freeMinutes());

        Laytime.Event completed = firstOfType(visible, Laytime.COMPLETED);
        LocalDateTime endAt = completed != null && completed.occurredAt().isBefore(asOf)
                ? completed.occurredAt() : asOf;
        String kind = completed != null ? Laytime.FINAL : Laytime.PROVISIONAL;

        if (!endAt.isAfter(readyAt)) {
            return new Laytime.Statement(kind, asOf, readyAt, countFrom, endAt,
                    0, BigDecimal.ZERO.setScale(2), List.of(), claims(visible, List.of(), List.of()),
                    "计时尚未开始");
        }

        // ---- 1. 港方确认的天气停工区间（仅 PORT + confirmed 才暂停计时） ----
        List<Range> weather = new ArrayList<>();
        Laytime.Event openStop = null;
        for (Laytime.Event e : visible) {
            if (Laytime.WEATHER_STOP.equals(e.eventType()) && e.confirmed()
                    && "PORT".equals(e.party())) {
                openStop = e;
            } else if (Laytime.WEATHER_RESUME.equals(e.eventType()) && openStop != null) {
                weather.add(new Range(openStop.occurredAt(), e.occurredAt(),
                        Laytime.WEATHER_EXCEPTED, evid(openStop, e)));
                openStop = null;
            }
        }
        if (openStop != null) {
            weather.add(new Range(openStop.occurredAt(), endAt,
                    Laytime.WEATHER_EXCEPTED, evid(openStop)));
        }

        // ---- 2. 设备修复回执证明的故障窗口（仍计时，归因港方设备） ----
        List<Range> faults = new ArrayList<>();
        for (Laytime.Event e : visible) {
            if (Laytime.REPAIR_RECEIPT.equals(e.eventType()) && e.windowEnd() != null) {
                faults.add(new Range(e.occurredAt(), e.windowEnd(),
                        Laytime.CRANE_FAULT, List.of(e.id())));
            }
        }

        // ---- 3. 实际停工区间（STOP_START/STOP_END 配对；未闭合延伸至计时截止） ----
        List<Range> stoppages = new ArrayList<>();
        Laytime.Event openActual = null;
        for (Laytime.Event e : visible) {
            if (Laytime.STOP_START.equals(e.eventType())) {
                openActual = e;
            } else if (Laytime.STOP_END.equals(e.eventType()) && openActual != null) {
                stoppages.add(new Range(openActual.occurredAt(), e.occurredAt(),
                        Laytime.PENDING_EVIDENCE, evid(openActual, e)));
                openActual = null;
            }
        }
        if (openActual != null) {
            stoppages.add(new Range(openActual.occurredAt(), endAt,
                    Laytime.PENDING_EVIDENCE, evid(openActual)));
        }

        // ---- 3b. 故障窗口与实际停工重叠时，把停工报告一并列为证据（双方证据可质证） ----
        List<Range> faultsMerged = new ArrayList<>();
        for (Range f : faults) {
            Set<Long> ev = new LinkedHashSet<>(f.evidenceIds());
            for (Range s : stoppages) {
                if (s.start().isBefore(f.end()) && s.end().isAfter(f.start())) {
                    ev.addAll(s.evidenceIds());
                }
            }
            faultsMerged.add(new Range(f.start(), f.end(), f.cls(), List.copyOf(ev)));
        }
        faults = faultsMerged;

        // ---- 4. 扫描线切分 [readyAt, endAt] 并分类 ----
        Set<LocalDateTime> cuts = new TreeSet<>();
        cuts.add(readyAt);
        cuts.add(countFrom);
        cuts.add(endAt);
        for (Range r : union(weather, faults, stoppages)) {
            cuts.add(clip(r.start(), readyAt, endAt));
            cuts.add(clip(r.end(), readyAt, endAt));
        }

        List<Laytime.Segment> segments = new ArrayList<>();
        List<LocalDateTime> pts = new ArrayList<>(cuts);
        for (int i = 0; i + 1 < pts.size(); i++) {
            LocalDateTime a = pts.get(i), b = pts.get(i + 1);
            if (!b.isAfter(a)) continue;
            String cls;
            Set<Long> evidence = new LinkedHashSet<>();
            if (!b.isAfter(countFrom)) {
                cls = Laytime.FREE;
                evidence.add(ready.id());
            } else if (coversAny(weather, a, b)) {
                cls = Laytime.WEATHER_EXCEPTED;
                evidenceOf(weather, a, b, evidence);
            } else if (coversAny(faults, a, b)) {
                cls = Laytime.CRANE_FAULT;
                evidenceOf(faults, a, b, evidence);
            } else if (coversAny(stoppages, a, b)) {
                cls = Laytime.PENDING_EVIDENCE;
                evidenceOf(stoppages, a, b, evidence);
            } else {
                cls = Laytime.BILLABLE;
            }
            mergeAppend(segments, a, b, cls, evidence);
        }

        long billable = segments.stream().filter(Laytime.Segment::billable)
                .mapToLong(Laytime.Segment::minutes).sum();
        BigDecimal amount = contract.ratePerHour()
                .multiply(BigDecimal.valueOf(billable))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        List<Laytime.Claim> claims = claims(visible, weather, faults);
        String note = Laytime.FINAL.equals(kind)
                ? "完工回执已送达，本版为定稿账单"
                : "暂计账单：仅含 " + asOf + " 前送达的回执，后续回执可能更正区段";
        return new Laytime.Statement(kind, asOf, readyAt, countFrom, endAt,
                billable, amount, segments, claims, note);
    }

    // ---------------- 船方主张处置 ----------------

    private static List<Laytime.Claim> claims(List<Laytime.Event> visible,
                                              List<Range> weather, List<Range> faults) {
        List<Laytime.Claim> out = new ArrayList<>();
        for (Laytime.Event e : visible) {
            if (!Laytime.STOP_START.equals(e.eventType()) || e.claimCause() == null) continue;
            LocalDateTime stopEnd = visible.stream()
                    .filter(x -> Laytime.STOP_END.equals(x.eventType())
                            && !x.occurredAt().isBefore(e.occurredAt()))
                    .map(Laytime.Event::occurredAt).min(Comparator.naturalOrder())
                    .orElse(e.occurredAt());
            String status = "PENDING";
            Long refutedBy = null;
            String note = "船方主张 " + causeLabel(e.claimCause()) + "，待港方凭证确认";
            if ("WEATHER".equals(e.claimCause())) {
                for (Range w : weather) {
                    if (covers(w, e.occurredAt(), stopEnd)) {
                        status = "HONORED";
                        note = "与港方确认天气停工一致，暂停计时";
                        break;
                    }
                }
            }
            if (!"HONORED".equals(status)) {
                for (Range f : faults) {
                    if (covers(f, e.occurredAt(), stopEnd)) {
                        status = "REFUTED";
                        refutedBy = f.evidenceIds().isEmpty() ? null : f.evidenceIds().get(0);
                        note = "船方主张 " + causeLabel(e.claimCause())
                                + " 被港方修复回执否定：实为设备故障，按合同仍计时";
                        break;
                    }
                }
            }
            out.add(new Laytime.Claim(e.id(), e.claimCause(), status, refutedBy, note));
        }
        return out;
    }

    private static String causeLabel(String cause) {
        return switch (cause) {
            case "WEATHER" -> "天气原因";
            case "EQUIPMENT" -> "设备原因";
            default -> "原因待定";
        };
    }

    // ---------------- 小工具 ----------------

    private static Laytime.Event firstOfType(List<Laytime.Event> events, String type) {
        return events.stream().filter(e -> type.equals(e.eventType())).findFirst().orElse(null);
    }

    private static List<Long> evid(Laytime.Event... es) {
        List<Long> ids = new ArrayList<>();
        for (Laytime.Event e : es) ids.add(e.id());
        return ids;
    }

    private static List<Range> union(List<Range>... lists) {
        List<Range> all = new ArrayList<>();
        for (List<Range> l : lists) all.addAll(l);
        return all;
    }

    private static LocalDateTime clip(LocalDateTime t, LocalDateTime lo, LocalDateTime hi) {
        if (t.isBefore(lo)) return lo;
        if (t.isAfter(hi)) return hi;
        return t;
    }

    private static boolean covers(Range r, LocalDateTime a, LocalDateTime b) {
        return !r.start().isAfter(a) && !r.end().isBefore(b);
    }

    private static boolean coversAny(List<Range> ranges, LocalDateTime a, LocalDateTime b) {
        return ranges.stream().anyMatch(r -> covers(r, a, b));
    }

    private static void evidenceOf(List<Range> ranges, LocalDateTime a, LocalDateTime b,
                                   Set<Long> into) {
        for (Range r : ranges) {
            if (covers(r, a, b)) into.addAll(r.evidenceIds());
        }
    }

    /**相邻同类区段合并（证据取并集），保证输出是连续不重叠的时间轴*/
    private static void mergeAppend(List<Laytime.Segment> segments,
                                    LocalDateTime a, LocalDateTime b,
                                    String cls, Set<Long> evidence) {
        long minutes = Duration.between(a, b).toMinutes();
        boolean billable = Laytime.BILLABLE.equals(cls)
                || Laytime.CRANE_FAULT.equals(cls) || Laytime.PENDING_EVIDENCE.equals(cls);
        if (!segments.isEmpty()) {
            Laytime.Segment last = segments.get(segments.size() - 1);
            if (last.segClass().equals(cls) && last.end().equals(a)) {
                Set<Long> ev = new LinkedHashSet<>(last.evidenceEventIds());
                ev.addAll(evidence);
                segments.set(segments.size() - 1, new Laytime.Segment(
                        last.start(), b, cls, last.minutes() + minutes, billable,
                        label(cls), List.copyOf(ev)));
                return;
            }
        }
        segments.add(new Laytime.Segment(a, b, cls, minutes, billable,
                label(cls), List.copyOf(evidence)));
    }

    public static String label(String cls) {
        return switch (cls) {
            case Laytime.FREE -> "合同免计";
            case Laytime.WEATHER_EXCEPTED -> "天气停工·港方确认（暂停计时）";
            case Laytime.CRANE_FAULT -> "岸桥故障·港方设备（按合同仍计时）";
            case Laytime.PENDING_EVIDENCE -> "停工待证（暂计时，待凭证改判）";
            default -> "正常计时";
        };
    }
}
