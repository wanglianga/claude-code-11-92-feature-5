package com.port.berth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.domain.Laytime;
import com.port.berth.repo.LaytimeDao;
import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 滞期争议服务：双方回执入账（只增不改）、按“信息截止时刻”重算账单版本、
 * 相邻版本间区段更正留痕、完工定稿。
 *
 * <p>两种结算口径（与任务要求对照）：</p>
 * <ul>
 *   <li>STREAMING 边收事件边累计：任何 asOf 都给出暂计额，船代可及时看到；
 *       代价是迟到回执会更正此前区段（见 laytime_correction 留痕）。</li>
 *   <li>AT_COMPLETION 完工收齐再结算：完工回执送达前不出具金额，数字稳定；
 *       代价是争议期间没有最终数。</li>
 * </ul>
 */
@Service
public class LaytimeService {

    /**结算口径：边收边累计（暂计）*/
    public static final String MODE_STREAMING = "STREAMING";
    /**结算口径：完工收齐再结算*/
    public static final String MODE_AT_COMPLETION = "AT_COMPLETION";

    private static final Set<String> EVENT_TYPES = Set.of(
            Laytime.READY, Laytime.WEATHER_STOP, Laytime.WEATHER_RESUME,
            Laytime.STOP_START, Laytime.STOP_END, Laytime.REPAIR_RECEIPT,
            Laytime.COMPLETED, Laytime.PLANNED_STOP);

    private final LaytimeDao dao;
    private final PlanDao planDao;
    private final ObjectMapper om;

    public LaytimeService(LaytimeDao dao, PlanDao planDao, ObjectMapper om) {
        this.dao = dao;
        this.planDao = planDao;
        this.om = om;
    }

    // ================= 回执入账（触发重算与更正留痕） =================

    /**
     * 录入一条回执并重算账单版本。
     *
     * @param party 提交方（由控制器按登录角色确定：AGENT→SHIP，其余→PORT）
     */
    @Transactional
    public Map<String, Object> ingest(long voyageId, String eventType,
                                      LocalDateTime occurredAt, LocalDateTime receivedAt,
                                      String party, String claimCause,
                                      LocalDateTime windowEnd,
                                      String evidenceRef, String note) {
        if (!EVENT_TYPES.contains(eventType)) {
            throw ApiException.badRequest("BAD_EVENT_TYPE", "未知回执类型: " + eventType);
        }
        Laytime.Contract contract = mustContract(voyageId);
        if (occurredAt == null) {
            throw ApiException.badRequest("OCCURRED_REQUIRED", "必须给出业务发生时间 occurredAt");
        }
        if (receivedAt == null) receivedAt = occurredAt;
        if (receivedAt.isBefore(occurredAt) && !Laytime.PLANNED_STOP.equals(eventType)) {
            // 计划停工预告可在窗口开始之前送达；其余回执送达不能早于发生
            throw ApiException.badRequest("RECEIVED_BEFORE_OCCURRED", "送达时间不能早于发生时间");
        }
        Optional<Map<String, Object>> latest = dao.latestStatement(voyageId);
        if (latest.isPresent() && Boolean.TRUE.equals(latest.get().get("finalized"))) {
            throw ApiException.conflict("LAYTIME_FINALIZED",
                    "该船次滞期账单已定稿锁定，不再接受新回执；如需调整请先走争议复议流程");
        }
        if (Laytime.READY.equals(eventType) && dao.hasEventOfType(voyageId, Laytime.READY)) {
            throw ApiException.conflict("READY_EXISTS", "准备就绪（NOR）回执已存在，不能重复登记");
        }
        if (Laytime.COMPLETED.equals(eventType) && dao.hasEventOfType(voyageId, Laytime.COMPLETED)) {
            throw ApiException.conflict("COMPLETED_EXISTS", "完工回执已存在，不能重复登记");
        }
        if ((Laytime.REPAIR_RECEIPT.equals(eventType) || Laytime.PLANNED_STOP.equals(eventType))
                && (windowEnd == null || !windowEnd.isAfter(occurredAt))) {
            throw ApiException.badRequest("WINDOW_REQUIRED",
                    "修复回执/计划停工必须给出窗口结束时间 windowEnd（晚于发生时间）");
        }
        // 合同只认港方确认的凭证：船方提交的一律标记未确认（如船方主张天气）
        boolean confirmed = "PORT".equals(party) && !Laytime.PLANNED_STOP.equals(eventType);

        Laytime.Event event = new Laytime.Event(0, voyageId, eventType, occurredAt, receivedAt,
                party, confirmed, claimCause, windowEnd, evidenceRef, note);
        long eventId = dao.insertEvent(event);

        // 重算最新版本（asOf = 全部回执的最晚送达时刻）
        List<Laytime.Event> all = dao.listEvents(voyageId);
        LocalDateTime asOf = all.stream().map(Laytime.Event::receivedAt)
                .max(LocalDateTime::compareTo).orElse(receivedAt);
        Laytime.Statement st = LaytimeEngine.compute(contract, all, asOf);

        int versionNo = dao.nextVersionNo(voyageId);
        List<Map<String, Object>> segViews = segmentViews(st, all, contract);
        long stmtId = dao.insertStatement(voyageId, versionNo, st, writeJson(segViews));

        // 与上一版比对，留下被更正区段
        List<Map<String, Object>> corrections = new ArrayList<>();
        if (latest.isPresent()) {
            corrections = diffAndPersist(voyageId, stmtId, latest.get(), st, eventId,
                    evidenceRef);
        }

        planDao.event(voyageId, null, "LAYTIME_RECEIPT",
                "滞期回执入账：" + eventType + "（" + party + " · " + evidenceRef + "）"
                        + (event.lateByMinutes() > 0 ? "，迟到 " + event.lateByMinutes() + " 分钟" : "")
                        + " → 账单 v" + versionNo + "（" + st.kind() + "，可计时 "
                        + st.billableMinutes() + " 分钟，" + st.amount() + " 元）",
                writeJson(Map.of("eventId", eventId, "statementId", stmtId,
                        "versionNo", versionNo, "kind", st.kind())));

        Map<String, Object> view = statementView(voyageId, asOf, MODE_STREAMING);
        view.put("versionNo", versionNo);
        view.put("statementId", stmtId);
        view.put("newCorrections", corrections);
        return view;
    }

    /**比对上一版账单，把分类发生变化的区段写入 laytime_correction*/
    private List<Map<String, Object>> diffAndPersist(long voyageId, long stmtId,
                                                     Map<String, Object> prevRow,
                                                     Laytime.Statement cur,
                                                     long causeEventId, String causeRef) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Laytime.Segment> prev = parseSegments(prevRow.get("segments_json"));
        for (Laytime.Segment now : cur.segments()) {
            for (Laytime.Segment old : prev) {
                LocalDateTime s = now.start().isAfter(old.start()) ? now.start() : old.start();
                LocalDateTime e = now.end().isBefore(old.end()) ? now.end() : old.end();
                if (!e.isAfter(s) || now.segClass().equals(old.segClass())) continue;
                String note = "区段 " + s.toLocalTime() + "–" + e.toLocalTime() + " 由「"
                        + LaytimeEngine.label(old.segClass()) + "」更正为「"
                        + LaytimeEngine.label(now.segClass()) + "」（回执 " + causeRef + "）";
                dao.insertCorrection(voyageId, stmtId, s, e,
                        old.segClass(), now.segClass(), causeEventId, note);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("segStart", s);
                c.put("segEnd", e);
                c.put("fromClass", old.segClass());
                c.put("toClass", now.segClass());
                c.put("causeEventId", causeEventId);
                c.put("note", note);
                out.add(c);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Laytime.Segment> parseSegments(Object segmentsJson) {
        if (segmentsJson == null) return List.of();
        try {
            List<Map<String, Object>> raw = om.readValue(String.valueOf(segmentsJson), List.class);
            List<Laytime.Segment> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                out.add(new Laytime.Segment(
                        LocalDateTime.parse(String.valueOf(m.get("start"))),
                        LocalDateTime.parse(String.valueOf(m.get("end"))),
                        String.valueOf(m.get("segClass")),
                        ((Number) m.get("minutes")).longValue(),
                        Boolean.TRUE.equals(m.get("billable")),
                        String.valueOf(m.get("label")),
                        List.of()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================= 查询 =================

    public Laytime.Contract mustContract(long voyageId) {
        return dao.findContract(voyageId)
                .orElseThrow(() -> ApiException.notFound(
                        "船次 " + voyageId + " 没有滞期合同条款（laytime_contract）"));
    }

    /**
     * 计算任一时刻的账单（不落库，纯回放）。
     *
     * @param asOf 信息截止时刻；null = 全部已送达回执的最晚时刻
     * @param mode STREAMING / AT_COMPLETION
     */
    public Map<String, Object> statementView(long voyageId, LocalDateTime asOf, String mode) {
        Laytime.Contract contract = mustContract(voyageId);
        List<Laytime.Event> all = dao.listEvents(voyageId);
        final LocalDateTime asOfFinal = asOf != null ? asOf
                : all.stream().map(Laytime.Event::receivedAt)
                        .max(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        Laytime.Statement st = LaytimeEngine.compute(contract, all, asOfFinal);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voyageId", voyageId);
        m.put("mode", mode);
        m.put("asOf", asOfFinal);
        m.put("contract", contractView(contract));

        boolean completionVisible = all.stream().anyMatch(e ->
                Laytime.COMPLETED.equals(e.eventType()) && !e.receivedAt().isAfter(asOfFinal));
        if (MODE_AT_COMPLETION.equals(mode) && !completionVisible) {
            // 完工收齐再结算：完工回执送达前不出具任何金额
            m.put("settled", false);
            m.put("kind", "UNSETTLED");
            m.put("billableMinutes", null);
            m.put("amount", null);
            m.put("segments", List.of());
            m.put("note", "完工结算口径：完工回执送达前不出具金额（争议期间没有最终数）");
            putTradeoff(m);
            return m;
        }

        m.put("settled", true);
        m.put("kind", st.kind());
        m.put("readyAt", st.readyAt());
        m.put("countFrom", st.countFrom());
        m.put("endAt", st.endAt());
        m.put("billableMinutes", st.billableMinutes());
        m.put("amount", st.amount());
        m.put("segments", segmentViews(st, all, contract));
        m.put("claims", st.claims().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("eventId", c.eventId());
            cm.put("claimCause", c.claimCause());
            cm.put("status", c.status());
            cm.put("refutedByEventId", c.refutedByEventId());
            cm.put("note", c.note());
            return cm;
        }).toList());
        m.put("lateReceipts", all.stream()
                .filter(e -> !e.receivedAt().isAfter(asOfFinal) && e.lateByMinutes() > 0)
                .map(this::eventView).toList());
        m.put("plannedNotices", all.stream()
                .filter(e -> Laytime.PLANNED_STOP.equals(e.eventType())
                        && !e.receivedAt().isAfter(asOfFinal))
                .map(e -> {
                    Map<String, Object> p = new LinkedHashMap<>(eventView(e));
                    p.put("warning", "计划停工预告仅作参考，不构成实际停工凭证；实际停工以双方回执为准");
                    return p;
                }).toList());
        m.put("note", st.note());
        putTradeoff(m);
        return m;
    }

    private void putTradeoff(Map<String, Object> m) {
        m.put("settlementModes", Map.of(
                MODE_STREAMING, "边收事件边累计：船代可随时看到暂计额；代价是迟到回执会更正此前区段（全部留痕可质证）",
                MODE_AT_COMPLETION, "完工收齐再结算：数字稳定一次定稿；代价是争议期间没有最终数"));
    }

    private Map<String, Object> contractView(Laytime.Contract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("freeMinutes", c.freeMinutes());
        m.put("ratePerHour", c.ratePerHour());
        m.put("ratePerMinute", c.ratePerMinute().setScale(4, RoundingMode.HALF_UP));
        m.put("exceptionRule", c.exceptionRule());
        m.put("exceptionRuleText", "仅港方确认的天气停工暂停计时；设备故障等其他停工仍计时");
        m.put("remark", c.remark());
        return m;
    }

    /**区段视图：附金额与双方证据（回执明细）*/
    private List<Map<String, Object>> segmentViews(Laytime.Statement st,
                                                   List<Laytime.Event> all,
                                                   Laytime.Contract contract) {
        Map<Long, Laytime.Event> byId = new LinkedHashMap<>();
        for (Laytime.Event e : all) byId.put(e.id(), e);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Laytime.Segment s : st.segments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", s.start());
            m.put("end", s.end());
            m.put("segClass", s.segClass());
            m.put("label", s.label());
            m.put("minutes", s.minutes());
            m.put("billable", s.billable());
            if (s.billable()) {
                m.put("amount", contract.ratePerHour()
                        .multiply(BigDecimal.valueOf(s.minutes()))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
            }
            List<Map<String, Object>> ev = new ArrayList<>();
            for (Long id : s.evidenceEventIds()) {
                Laytime.Event e = byId.get(id);
                if (e != null) ev.add(eventView(e));
            }
            m.put("evidence", ev);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> eventView(Laytime.Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("eventType", e.eventType());
        m.put("occurredAt", e.occurredAt());
        m.put("receivedAt", e.receivedAt());
        m.put("party", e.party());
        m.put("confirmed", e.confirmed());
        m.put("claimCause", e.claimCause());
        m.put("windowEnd", e.windowEnd());
        m.put("evidenceRef", e.evidenceRef());
        m.put("note", e.note());
        m.put("lateByMinutes", e.lateByMinutes());
        return m;
    }

    public List<Map<String, Object>> events(long voyageId) {
        return dao.listEvents(voyageId).stream().map(this::eventView).toList();
    }

    public Map<String, Object> contract(long voyageId) {
        return contractView(mustContract(voyageId));
    }

    public List<Map<String, Object>> statements(long voyageId) {
        return dao.listStatements(voyageId);
    }

    public List<Map<String, Object>> corrections(long voyageId) {
        return dao.listCorrections(voyageId);
    }

    /**定稿：完工回执到达后锁定最新账单，之后拒绝新回执*/
    @Transactional
    public Map<String, Object> finalize(long voyageId) {
        Map<String, Object> latest = dao.latestStatement(voyageId)
                .orElseThrow(() -> ApiException.badRequest("NO_STATEMENT", "尚无任何账单版本"));
        if (!Laytime.FINAL.equals(latest.get("kind"))) {
            throw ApiException.conflict("NOT_COMPLETED",
                    "完工回执尚未送达，不能定稿；当前仍为暂计状态");
        }
        dao.markFinalized(((Number) latest.get("id")).longValue());
        planDao.event(voyageId, null, "LAYTIME_FINALIZED",
                "滞期账单定稿：可计时 " + latest.get("billable_minutes") + " 分钟，应收 "
                        + latest.get("amount") + " 元（版本 v" + latest.get("version_no") + "）", null);
        Map<String, Object> view = statementView(voyageId, null, MODE_STREAMING);
        view.put("finalized", true);
        return view;
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
