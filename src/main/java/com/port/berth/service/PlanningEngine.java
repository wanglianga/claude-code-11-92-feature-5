package com.port.berth.service;

import com.port.berth.domain.Cat;
import com.port.berth.domain.Eval;
import com.port.berth.domain.ResourceType;
import com.port.berth.domain.SegmentType;
import com.port.berth.repo.CatalogDao;
import com.port.berth.repo.PlanDao;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选排泊引擎。
 *
 * <p>固定时间轴（S = 靠泊操纵开始）：
 * <pre>
 *   引航段   [S-45, S+15]                 资源占用前后各加 handoverMin 交接缓冲
 *   拖轮靠泊 [S-15, S+30]                 占用尾部加 turnaroundMin 周转
 *   靠泊操纵 [S, S+30]                    泊位占用开始
 *   装卸作业 [S+30, S+30+duration]        每台岸桥占用至 opsEnd + transferMin
 *   拖轮离泊 [opsEnd, opsEnd+30]          占用尾部加 turnaroundMin
 *   离泊段   [opsEnd, opsEnd+30]          泊位占用结束 = opsEnd+30
 * </pre>
 * UKC 校验窗口为 [S, opsEnd]（整个靠泊操纵 + 装卸窗口）。
 */
@Service
public class PlanningEngine {

    public static final int PILOT_BEFORE_S = 45;
    public static final int PILOT_AFTER_S = 15;
    public static final int TUG_IN_BEFORE_S = 15;
    public static final int TUG_IN_AFTER_S = 30;
    public static final int BERTHING_MIN = 30;
    public static final int UNBERTHING_MIN = 30;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final BigDecimal EPS = new BigDecimal("0.001");

    private final CatalogDao catalog;
    private final PlanDao planDao;

    public PlanningEngine(CatalogDao catalog, PlanDao planDao) {
        this.catalog = catalog;
        this.planDao = planDao;
    }

    public static String fmt(LocalDateTime t) {
        return t == null ? "-" : t.format(FMT);
    }

    /**一次候选生成的资源可用性快照（内存做冲突诊断；强一致由 DB 触发器兜底）*/
    public class Availability {
        final List<PlanDao.HolderRow> occs;
        final List<Cat.Fault> faults;
        final Long excludeVoyageId;

        Availability(Long excludeVoyageId) {
            this.occs = planDao.listActiveOccupations();
            this.faults = catalog.listFaults();
            this.excludeVoyageId = excludeVoyageId;
        }

        List<PlanDao.HolderRow> overlaps(String key, LocalDateTime s, LocalDateTime e) {
            List<PlanDao.HolderRow> out = new ArrayList<>();
            for (PlanDao.HolderRow h : occs) {
                if (!key.equals(h.resourceKey)) continue;
                if (excludeVoyageId != null && h.voyageId == excludeVoyageId) continue;
                if (com.port.berth.domain.ConstraintMath.overlap(
                        h.startAt, h.endAt, s, e)) out.add(h);
            }
            return out;
        }

        Cat.Fault faultOverlap(String type, long id, LocalDateTime s, LocalDateTime e) {
            for (Cat.Fault f : faults) {
                if (!f.resourceType().equals(type) || f.resourceId() != id) continue;
                if (com.port.berth.domain.ConstraintMath.overlap(
                        f.start(), f.end(), s, e)) return f;
            }
            return null;
        }
    }

    public static String resourceKey(String type, long id) {
        return type + ":" + id;
    }

    // ====================================================================
    // 全量枚举
    // ====================================================================

    public List<Eval> evaluateAll(Cat.Voyage v, int stepMin, int maxWaitMin) {
        return evaluateAll(v, v.workDurationMin(), stepMin, maxWaitMin);
    }

    /**重排使用：可指定剩余作业时长（已完成部分不再排入）*/
    public List<Eval> evaluateAll(Cat.Voyage v, int durationMin,
                                  int stepMin, int maxWaitMin) {
        Availability av = new Availability(v.id());
        List<Cat.TideSlot> tides = catalog.listTides();
        List<Cat.Berth> berths = catalog.listBerths();

        List<Eval> result = new ArrayList<>();
        LocalDateTime limit = v.etaEnd().plusMinutes(maxWaitMin);
        for (Cat.Berth b : berths) {
            for (LocalDateTime s = v.etaStart(); !s.isAfter(limit); s = s.plusMinutes(stepMin)) {
                result.add(doEvaluate(v, b, s, durationMin, av, tides));
            }
        }
        return result;
    }

    /**对指定泊位/S 做完整评估（暂存、确认、重排前重校都走这里）*/
    public Eval evaluateAt(Cat.Voyage v, long berthId, LocalDateTime s,
                           Integer cargoDurationMin) {
        Cat.Berth berth = catalog.findBerth(berthId)
                .orElseThrow(() -> new IllegalArgumentException("泊位不存在: " + berthId));
        int dur = cargoDurationMin != null ? cargoDurationMin : v.workDurationMin();
        return doEvaluate(v, berth, s, dur,
                new Availability(v.id()), catalog.listTides());
    }

    // ====================================================================
    // 核心评估
    // ====================================================================

    private Eval doEvaluate(Cat.Voyage v, Cat.Berth berth, LocalDateTime s, int duration,
                            Availability av, List<Cat.TideSlot> tides) {
        Cat.Ship ship = v.ship();
        Eval e = new Eval();
        e.berthId = berth.id();
        e.berthCode = berth.code();
        e.berthFrom = s;
        e.pilotStart = s.minusMinutes(PILOT_BEFORE_S);
        e.opsStart = s.plusMinutes(BERTHING_MIN);
        e.opsEnd = e.opsStart.plusMinutes(duration);
        e.berthTo = e.opsEnd.plusMinutes(UNBERTHING_MIN);
        e.waitMinutes = (int) Math.max(0, Duration.between(v.etaEnd(), s).toMinutes());
        long offset = Math.max(0, Duration.between(v.etaStart(), s).toMinutes());
        e.score = (int) (1_000_000L - offset);

        // 1) 泊位几何与货类限制 ------------------------------------------
        if (berth.maxLengthM().compareTo(ship.lengthM()) < 0) {
            e.addReason(new Eval.Reason("BERTH_TOO_SHORT",
                    String.format("%s 最大可容船长 %s m，小于本船 %s m",
                            berth.code(), berth.maxLengthM(), ship.lengthM()))
                    .resource(ResourceType.BERTH.name(), berth.id(), berth.code()));
        }
        if (!berth.cargoTypes().contains(ship.cargoType())) {
            e.addReason(new Eval.Reason("CARGO_NOT_ALLOWED",
                    String.format("%s 不允许 %s 货类，允许：%s",
                            berth.code(), ship.cargoType(), berth.cargoTypes()))
                    .resource(ResourceType.BERTH.name(), berth.id(), berth.code()));
        }

        // 2) 潮汐 / UKC（整个靠泊及装卸窗口） -----------------------------
        checkTide(e, berth, ship, s, e.opsEnd, tides);

        // 3) 泊位占用 ------------------------------------------------------
        Eval.OccSpec berthOcc = new Eval.OccSpec(
                ResourceType.BERTH.name(), berth.id(), s, e.berthTo);
        e.occupations.add(berthOcc);
        addOccupationReasons(e, av, berthOcc,
                String.format("泊位 %s 在 %s~%s 已被占用",
                        berth.code(), fmt(s), fmt(e.berthTo)));

        // 4) 引航员 --------------------------------------------------------
        assignPilot(e, ship, s, av);

        // 5) 拖轮 ----------------------------------------------------------
        assignTug(e, ship, s, e.opsEnd, av);

        // 6) 岸桥 ----------------------------------------------------------
        assignCranes(e, v.craneCount(), e.opsStart, e.opsEnd, av);

        // 7) 基础分段（即使资源未选到也保留时间轴，便于说明受影响段） -------
        buildBaseSegments(e, s, duration);

        return e;
    }

    // ---------------- 潮汐 UKC ----------------

    /**UKC 违规子区间（影响分析用）*/
    public record UkcViolation(LocalDateTime start, LocalDateTime end,
                               java.math.BigDecimal clearance, String message) {}

    public List<UkcViolation> findUkcViolations(long berthId, Cat.Ship ship,
                                                LocalDateTime from, LocalDateTime to) {
        Cat.Berth berth = catalog.findBerth(berthId)
                .orElseThrow(() -> new IllegalArgumentException("泊位不存在: " + berthId));
        List<UkcViolation> out = new ArrayList<>();
        for (Cat.TideSlot t : catalog.listTides()) {
            LocalDateTime os = t.start().isAfter(from) ? t.start() : from;
            LocalDateTime oe = t.end().isBefore(to) ? t.end() : to;
            if (!os.isBefore(oe)) continue;
            BigDecimal clearance = berth.chartDepthM().add(t.height()).subtract(ship.draftM());
            if (clearance.compareTo(berth.ukcMinM()) < 0) {
                out.add(new UkcViolation(os, oe, clearance,
                        String.format("%s~%s 净空 %.2f m < %.1f m（潮位 %.2f m）",
                                fmt(os), fmt(oe), clearance, berth.ukcMinM(), t.height())));
            }
        }
        return out;
    }

    private void checkTide(Eval e, Cat.Berth berth, Cat.Ship ship,
                           LocalDateTime from, LocalDateTime to,
                           List<Cat.TideSlot> tides) {
        boolean covered = false;
        LocalDateTime cursor = from;
        for (Cat.TideSlot t : tides) {
            LocalDateTime os = t.start().isAfter(from) ? t.start() : from;
            LocalDateTime oe = t.end().isBefore(to) ? t.end() : to;
            if (!os.isBefore(oe)) continue;
            covered = true;
            if (t.start().isAfter(cursor)) {
                e.addReason(new Eval.Reason("TIDE_DATA_GAP",
                        String.format("潮汐数据在 %s~%s 存在空档，无法校验安全潮位",
                                fmt(cursor), fmt(t.start()))));
            }
            cursor = t.end().isAfter(cursor) ? t.end() : cursor;

            BigDecimal clearance = com.port.berth.domain.ConstraintMath
                    .clearance(berth.chartDepthM(), t.height(), ship.draftM());
            if (clearance.compareTo(berth.ukcMinM()) < 0) {
                e.addReason(new Eval.Reason("UKC_VIOLATION",
                        String.format(
                                "安全净空不足：%s~%s 潮位 %.2f m，%s 图示水深 %.1f m、" +
                                        "本船吃水 %.1f m，实际净空 %.2f m < 要求 %.1f m",
                                fmt(os), fmt(oe), t.height(), berth.code(),
                                berth.chartDepthM(), ship.draftM(),
                                clearance, berth.ukcMinM()))
                        .resource(ResourceType.BERTH.name(), berth.id(), berth.code()));
            }
        }
        if (!covered || cursor.isBefore(to)) {
            e.addReason(new Eval.Reason("TIDE_DATA_GAP",
                    String.format("缺少覆盖 %s~%s 的潮汐时段数据", fmt(from), fmt(to))));
        }
    }

    // ---------------- 占用冲突诊断 ----------------

    private void addOccupationReasons(Eval e, Availability av, Eval.OccSpec occ,
                                      String baseMessage) {
        List<PlanDao.HolderRow> holders = av.overlaps(occ.resourceKey, occ.start, occ.end);
        for (PlanDao.HolderRow h : holders) {
            boolean tentative = "TENTATIVE".equals(h.kind);
            String who = h.voyageNo != null ? h.voyageNo : ("plan#" + h.planId);
            String msg = baseMessage + "；与 " + who + " 的"
                    + (tentative ? "暂留方案 " : "已确认方案 ")
                    + (h.planNo != null ? h.planNo : "#" + h.planId)
                    + " 占用 " + fmt(h.startAt) + "~" + fmt(h.endAt) + " 重叠";
            if (tentative && h.holdExpiresAt != null) {
                msg += "（暂留至 " + fmt(h.holdExpiresAt) + "）";
            }
            Eval.Reason r = new Eval.Reason(
                    tentative ? "RESOURCE_HELD_TENTATIVE" : "RESOURCE_BOOKED_FIRM", msg)
                    .resource(occ.resourceType, occ.resourceId, null)
                    .holder(h.planId, h.planNo, h.voyageNo, h.kind, h.holdExpiresAt);
            e.addReason(r);
        }
    }

    // ---------------- 引航员 ----------------

    private void assignPilot(Eval e, Cat.Ship ship, LocalDateTime s, Availability av) {
        boolean needDeep = ship.draftM().doubleValue() >= 9.0
                || ship.lengthM().doubleValue() >= 240.0;
        LocalDateTime pStart = s.minusMinutes(PILOT_BEFORE_S);
        LocalDateTime pEnd = s.plusMinutes(PILOT_AFTER_S);

        List<Cat.Pilot> all = catalog.listPilots();
        List<Cat.Pilot> qualified = new ArrayList<>();
        for (Cat.Pilot p : all) {
            if (p.maxLengthM().compareTo(ship.lengthM()) >= 0
                    && p.maxDraftM().compareTo(ship.draftM()) >= 0
                    && (!needDeep || "DEEP".equals(p.qualification()))) {
                qualified.add(p);
            }
        }

        e.segments.add(seg(10, SegmentType.PILOTAGE, "引航员登轮引航",
                null, null, null, pStart, pEnd));

        if (qualified.isEmpty()) {
            e.addReason(new Eval.Reason("NO_QUALIFIED_PILOT",
                    String.format("没有满足船长 %s m / 吃水 %s m 且具备%s资质的引航员",
                            ship.lengthM(), ship.draftM(), needDeep ? "深水大船(DEEP)" : "对应"))
                    .resource(ResourceType.PILOT.name(), null, null));
            return;
        }

        List<String> diagnostics = new ArrayList<>();
        for (Cat.Pilot p : qualified) {
            LocalDateTime occStart = pStart.minusMinutes(p.handoverMin());
            LocalDateTime occEnd = pEnd.plusMinutes(p.handoverMin());
            Eval.OccSpec occ = new Eval.OccSpec(
                    ResourceType.PILOT.name(), p.id(), occStart, occEnd);

            Cat.Fault fault = av.faultOverlap("PILOT", p.id(), occStart, occEnd);
            if (fault != null) {
                diagnostics.add(p.code() + " 在该时段停用/故障（" + fault.reason() + "）");
                continue;
            }
            if (!withinWindow(p.windows(), occStart, occEnd)) {
                diagnostics.add(String.format("%s 不在值班区间（需覆盖 %s~%s，含交接）",
                        p.code(), fmt(occStart), fmt(occEnd)));
                continue;
            }
            List<PlanDao.HolderRow> holders = av.overlaps(occ.resourceKey, occStart, occEnd);
            if (!holders.isEmpty()) {
                PlanDao.HolderRow h = holders.get(0);
                diagnostics.add(String.format("%s 值班但 %s~%s 已被 %s 的%s占用",
                        p.code(), fmt(occStart), fmt(occEnd),
                        h.voyageNo != null ? h.voyageNo : "plan#" + h.planId,
                        "TENTATIVE".equals(h.kind) ? "暂留" : "确认方案"));
                continue;
            }
            // 选中
            e.assignments.add(new Eval.Assignment("PILOT", p.id(), p.code(), p.name(), "主引航员"));
            e.occupations.add(occ);
            replaceSegmentResource(e, SegmentType.PILOTAGE, "PILOT", p.id(), p.code());
            return;
        }

        Eval.Reason reason = new Eval.Reason("PILOT_UNAVAILABLE",
                "合格引航员均无法安排：" + String.join("；", diagnostics));
        Cat.Pilot first = qualified.get(0);
        reason.resource(ResourceType.PILOT.name(), first.id(), first.code());
        // 尽量带上持有者结构化信息
        LocalDateTime occStart = pStart.minusMinutes(first.handoverMin());
        LocalDateTime occEnd = pEnd.plusMinutes(first.handoverMin());
        List<PlanDao.HolderRow> hs = av.overlaps(
                resourceKey("PILOT", first.id()), occStart, occEnd);
        if (!hs.isEmpty()) {
            PlanDao.HolderRow h = hs.get(0);
            reason.holder(h.planId, h.planNo, h.voyageNo, h.kind, h.holdExpiresAt);
            reason.code = "TENTATIVE".equals(h.kind) ? "PILOT_HELD_TENTATIVE" : "PILOT_BOOKED_FIRM";
        } else if (diagnostics.stream().anyMatch(d -> d.contains("不在值班"))) {
            reason.code = "PILOT_OFF_DUTY";
        }
        e.addReason(reason);
    }

    // ---------------- 拖轮 ----------------

    private void assignTug(Eval e, Cat.Ship ship, LocalDateTime s,
                           LocalDateTime opsEnd, Availability av) {
        LocalDateTime inStart = s.minusMinutes(TUG_IN_BEFORE_S);
        LocalDateTime inEnd = s.plusMinutes(TUG_IN_AFTER_S);
        LocalDateTime outStart = opsEnd;
        LocalDateTime outEnd = opsEnd.plusMinutes(UNBERTHING_MIN);

        e.segments.add(seg(20, SegmentType.TUG_INBOUND, "拖轮协助靠泊",
                null, null, null, inStart, inEnd));
        e.segments.add(seg(50, SegmentType.TUG_OUTBOUND, "拖轮协助离泊",
                null, null, null, outStart, outEnd));

        List<Cat.Tug> compatible = new ArrayList<>();
        for (Cat.Tug t : catalog.listTugs()) {
            if (t.maxLengthM().compareTo(ship.lengthM()) >= 0
                    && t.maxDraftM().compareTo(ship.draftM()) >= 0) {
                compatible.add(t);
            }
        }
        if (compatible.isEmpty()) {
            e.addReason(new Eval.Reason("NO_COMPATIBLE_TUG",
                    String.format("没有适配船长 %s m / 吃水 %s m 的拖轮",
                            ship.lengthM(), ship.draftM()))
                    .resource(ResourceType.TUG.name(), null, null));
            return;
        }

        List<String> diagnostics = new ArrayList<>();
        for (Cat.Tug t : compatible) {
            // 同一拖轮负责靠/离两段，两段占用都要空闲；每段尾部计周转
            Eval.OccSpec inOcc = new Eval.OccSpec("TUG", t.id(),
                    inStart, inEnd.plusMinutes(t.turnaroundMin()));
            Eval.OccSpec outOcc = new Eval.OccSpec("TUG", t.id(),
                    outStart, outEnd.plusMinutes(t.turnaroundMin()));

            boolean bad = false;
            for (Eval.OccSpec occ : List.of(inOcc, outOcc)) {
                Cat.Fault f = av.faultOverlap("TUG", t.id(), occ.start, occ.end);
                if (f != null) {
                    diagnostics.add(t.code() + " 故障停用（" + f.reason() + "）");
                    bad = true;
                    break;
                }
                if (!withinWindow(t.windows(), occ.start, occ.end)) {
                    diagnostics.add(String.format("%s 工作区间不能覆盖 %s~%s（含周转）",
                            t.code(), fmt(occ.start), fmt(occ.end)));
                    bad = true;
                    break;
                }
                if (!av.overlaps(occ.resourceKey, occ.start, occ.end).isEmpty()) {
                    PlanDao.HolderRow h = av.overlaps(occ.resourceKey, occ.start, occ.end).get(0);
                    diagnostics.add(String.format("%s 在 %s~%s 被 %s 的%s占用",
                            t.code(), fmt(occ.start), fmt(occ.end),
                            h.voyageNo != null ? h.voyageNo : "plan#" + h.planId,
                            "TENTATIVE".equals(h.kind) ? "暂留" : "确认方案"));
                    bad = true;
                    break;
                }
            }
            if (bad) continue;

            e.assignments.add(new Eval.Assignment("TUG", t.id(), t.code(), t.name(), "靠离泊拖轮"));
            e.occupations.add(inOcc);
            e.occupations.add(outOcc);
            replaceSegmentResource(e, SegmentType.TUG_INBOUND, "TUG", t.id(), t.code());
            replaceSegmentResource(e, SegmentType.TUG_OUTBOUND, "TUG", t.id(), t.code());
            return;
        }

        Eval.Reason reason = new Eval.Reason("TUG_UNAVAILABLE",
                "适配拖轮均无法安排：" + String.join("；", diagnostics));
        Cat.Tug first = compatible.get(0);
        reason.resource(ResourceType.TUG.name(), first.id(), first.code());
        Eval.OccSpec probe = new Eval.OccSpec("TUG", first.id(),
                inStart, inEnd.plusMinutes(first.turnaroundMin()));
        List<PlanDao.HolderRow> hs = av.overlaps(probe.resourceKey, probe.start, probe.end);
        if (!hs.isEmpty()) {
            PlanDao.HolderRow h = hs.get(0);
            reason.holder(h.planId, h.planNo, h.voyageNo, h.kind, h.holdExpiresAt);
            reason.code = "TENTATIVE".equals(h.kind) ? "TUG_HELD_TENTATIVE" : "TUG_BOOKED_FIRM";
        }
        e.addReason(reason);
    }

    // ---------------- 岸桥 ----------------

    private void assignCranes(Eval e, int need, LocalDateTime opsStart,
                              LocalDateTime opsEnd, Availability av) {
        if (need <= 0) return;
        List<Cat.Crane> all = catalog.listCranes();
        List<Long> chosen = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Eval.Reason firstHolderReason = null;

        for (Cat.Crane c : all) {
            if (!c.active()) {
                diagnostics.add(c.code() + " 台账停用");
                continue;
            }
            Eval.OccSpec occ = new Eval.OccSpec("CRANE", c.id(),
                    opsStart, opsEnd.plusMinutes(c.transferMin()));
            Cat.Fault f = av.faultOverlap("CRANE", c.id(), occ.start, occ.end);
            if (f != null) {
                diagnostics.add(c.code() + " 故障窗口 " + fmt(f.start()) + "~" + fmt(f.end())
                        + "（" + f.reason() + "）");
                continue;
            }
            if (!withinWindow(c.windows(), occ.start, occ.end)) {
                diagnostics.add(String.format("%s 工作区间不覆盖 %s~%s（含转场）",
                        c.code(), fmt(occ.start), fmt(occ.end)));
                continue;
            }
            List<PlanDao.HolderRow> hs = av.overlaps(occ.resourceKey, occ.start, occ.end);
            if (!hs.isEmpty()) {
                PlanDao.HolderRow h = hs.get(0);
                diagnostics.add(String.format("%s 在作业窗口被 %s 的%s占用",
                        c.code(), h.voyageNo != null ? h.voyageNo : "plan#" + h.planId,
                        "TENTATIVE".equals(h.kind) ? "暂留" : "确认方案"));
                if (firstHolderReason == null) {
                    firstHolderReason = new Eval.Reason(
                            "TENTATIVE".equals(h.kind) ? "CRANE_HELD_TENTATIVE" : "CRANE_BOOKED_FIRM",
                            "岸桥 " + c.code() + " 冲突")
                            .resource("CRANE", c.id(), c.code())
                            .holder(h.planId, h.planNo, h.voyageNo, h.kind, h.holdExpiresAt);
                }
                continue;
            }
            chosen.add(c.id());
            e.assignments.add(new Eval.Assignment("CRANE", c.id(), c.code(), c.name(), "装卸岸桥"));
            e.occupations.add(occ);
            if (chosen.size() == need) break;
        }

        if (chosen.size() < need) {
            Eval.Reason r = new Eval.Reason("INSUFFICIENT_CRANES",
                    String.format("需要 %d 台岸桥，当前窗口仅 %d 台可用；%s",
                            need, chosen.size(), String.join("；", diagnostics)));
            if (firstHolderReason != null) {
                r.holderPlanId = firstHolderReason.holderPlanId;
                r.holderPlanNo = firstHolderReason.holderPlanNo;
                r.holderVoyageNo = firstHolderReason.holderVoyageNo;
                r.holderKind = firstHolderReason.holderKind;
                r.holdExpiresAt = firstHolderReason.holdExpiresAt;
            }
            e.addReason(r);
        }
    }

    // ---------------- 分段构造 ----------------

    private Eval.SegSpec seg(int seq, SegmentType type, String title,
                             String rt, Long rid, String rcode,
                             LocalDateTime start, LocalDateTime end) {
        return new Eval.SegSpec(seq, type, title, rt, rid, rcode, start, end);
    }

    private void replaceSegmentResource(Eval e, SegmentType type,
                                        String rt, Long rid, String rcode) {
        for (Eval.SegSpec s : e.segments) {
            if (s.segType.equals(type.name())) {
                s.resourceType = rt;
                s.resourceId = rid;
                s.resourceCode = rcode;
            }
        }
    }

    private void buildBaseSegments(Eval e, LocalDateTime s, int duration) {
        e.segments.add(seg(30, SegmentType.BERTHING, "靠泊操纵/带缆",
                "BERTH", e.berthId, e.berthCode, s, s.plusMinutes(BERTHING_MIN)));
        e.segments.add(seg(40, SegmentType.CARGO_OP, "装卸作业",
                null, null, null, e.opsStart, e.opsEnd));
        e.segments.add(seg(60, SegmentType.UNBERTHING, "离泊/解缆",
                "BERTH", e.berthId, e.berthCode, e.opsEnd,
                e.opsEnd.plusMinutes(UNBERTHING_MIN)));
        e.segments.sort((a, b) -> a.seq - b.seq);
    }

    private boolean withinWindow(List<Cat.WorkWindow> windows,
                                 LocalDateTime start, LocalDateTime end) {
        for (Cat.WorkWindow w : windows) {
            if (com.port.berth.domain.ConstraintMath.withinWindow(
                    w.start(), w.end(), start, end)) return true;
        }
        return false;
    }
}
