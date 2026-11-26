package com.port.berth;

import com.port.berth.domain.Laytime;
import com.port.berth.service.LaytimeEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滞期计时引擎规格：固定样例（08:00 就绪、免计 2h、1000/60 元每分钟、
 * 天气 10–11 港方确认暂停、岸桥故障 11:00–12:30 仍计时、修复回执 13:00 迟到、14:00 完工）。
 */
class LaytimeEngineTest {

    private static final LocalDateTime D = LocalDateTime.of(2026, 11, 26, 0, 0);

    private final Laytime.Contract contract = new Laytime.Contract(
            1, 1, 120, new BigDecimal("1000.00"), "PORT_CONFIRMED_WEATHER", "测试合同");

    private long seq = 0;

    private Laytime.Event ev(String type, int occurredH, int occurredM,
                             Integer receivedH, Integer receivedM,
                             String party, boolean confirmed, String claimCause,
                             LocalDateTime windowEnd, String ref) {
        return new Laytime.Event(++seq, 1, type,
                D.withHour(occurredH).withMinute(occurredM),
                receivedH == null ? D.withHour(occurredH).withMinute(occurredM)
                        : D.withHour(receivedH).withMinute(receivedM),
                party, confirmed, claimCause, windowEnd, ref, null);
    }

    /**样例完整事件流（与 SeedService 播种一致）*/
    private List<Laytime.Event> sampleEvents() {
        List<Laytime.Event> ev = new ArrayList<>();
        ev.add(ev(Laytime.PLANNED_STOP, 10, 30, 7, 30, "PORT", false, null,
                D.withHour(11).withMinute(30), "PLAN-QC2-MAINT"));
        ev.add(ev(Laytime.READY, 8, 0, null, null, "SHIP", true, null, null, "NOR-2026-088"));
        ev.add(ev(Laytime.WEATHER_STOP, 10, 0, 10, 4, "PORT", true, null, null, "PORT-WX-1001"));
        ev.add(ev(Laytime.STOP_START, 11, 0, 11, 2, "SHIP", false, "WEATHER", null, "SHIP-LOG-110"));
        ev.add(ev(Laytime.WEATHER_RESUME, 11, 0, 11, 4, "PORT", true, null, null, "PORT-WX-1002"));
        ev.add(ev(Laytime.STOP_END, 12, 30, 12, 31, "SHIP", false, null, null, "SHIP-LOG-111"));
        ev.add(ev(Laytime.REPAIR_RECEIPT, 11, 0, 13, 0, "PORT", true, null,
                D.withHour(12).withMinute(30), "PORT-QC-REP-207"));
        ev.add(ev(Laytime.COMPLETED, 14, 0, null, null, "PORT", true, null, null, "PORT-CPL-300"));
        return ev;
    }

    private Laytime.Segment seg(Laytime.Statement st, String cls) {
        return st.segments().stream().filter(s -> s.segClass().equals(cls))
                .findFirst().orElseThrow(() -> new AssertionError("缺少区段 " + cls));
    }

    @Test
    void finalStatementMatchesSample() {
        Laytime.Statement st = LaytimeEngine.compute(contract, sampleEvents(), D.withHour(15));
        assertEquals(Laytime.FINAL, st.kind());
        assertEquals(D.withHour(8), st.readyAt());
        assertEquals(D.withHour(10), st.countFrom(), "免计 2 小时后 10:00 起计");
        assertEquals(D.withHour(14), st.endAt());
        assertEquals(180, st.billableMinutes());
        assertEquals(0, new BigDecimal("3000.00").compareTo(st.amount()), "应收 3000 元");

        // 区段链：免计 08–10 / 天气 10–11 / 岸桥故障 11–12:30 / 正常计时 12:30–14:00
        assertEquals(4, st.segments().size());
        Laytime.Segment free = seg(st, Laytime.FREE);
        assertEquals(D.withHour(8), free.start());
        assertEquals(D.withHour(10), free.end());
        assertFalse(free.billable());
        Laytime.Segment weather = seg(st, Laytime.WEATHER_EXCEPTED);
        assertEquals(D.withHour(10), weather.start());
        assertEquals(D.withHour(11), weather.end());
        assertFalse(weather.billable());
        Laytime.Segment fault = seg(st, Laytime.CRANE_FAULT);
        assertEquals(D.withHour(11), fault.start());
        assertEquals(D.withHour(12).withMinute(30), fault.end());
        assertTrue(fault.billable(), "岸桥故障按合同仍计时");
        Laytime.Segment run = seg(st, Laytime.BILLABLE);
        assertEquals(D.withHour(12).withMinute(30), run.start());
        assertEquals(D.withHour(14), run.end());

        // 可计时区间连续 11:00–14:00
        assertEquals(fault.start(), D.withHour(11));
        assertEquals(run.end(), D.withHour(14));
        assertEquals(fault.end(), run.start());

        // 船方天气主张被港方修复回执否定
        assertTrue(st.claims().stream().anyMatch(c -> "REFUTED".equals(c.status())));
    }

    @Test
    void replayAtNoonIsProvisionalAndCannotSeeFuture() {
        Laytime.Statement st = LaytimeEngine.compute(contract, sampleEvents(), D.withHour(12));
        assertEquals(Laytime.PROVISIONAL, st.kind(), "12:00 只能给出暂计");
        assertEquals(60, st.billableMinutes(), "只有 11:00–12:00 计入");
        assertEquals(0, new BigDecimal("1000.00").compareTo(st.amount()));
        assertEquals(D.withHour(12), st.endAt(), "不能把尚未发生的时长算入");
        assertTrue(st.segments().stream().noneMatch(s -> s.end().isAfter(D.withHour(12))));
        Laytime.Segment pending = seg(st, Laytime.PENDING_EVIDENCE);
        assertEquals(D.withHour(11), pending.start());
        assertEquals(D.withHour(12), pending.end(), "未闭合停工延伸至回放时刻");
    }

    @Test
    void lateRepairReceiptCorrectsSegment() {
        // 12:31（修复回执送达前）：11:00–12:30 仍是停工待证
        Laytime.Statement before = LaytimeEngine.compute(contract, sampleEvents(),
                D.withHour(12).withMinute(31));
        Laytime.Segment pending = seg(before, Laytime.PENDING_EVIDENCE);
        assertEquals(D.withHour(11), pending.start());
        assertEquals(D.withHour(12).withMinute(30), pending.end());

        // 13:00（回执送达后）：同一区段更正为岸桥故障
        Laytime.Statement after = LaytimeEngine.compute(contract, sampleEvents(),
                D.withHour(13));
        Laytime.Segment fault = seg(after, Laytime.CRANE_FAULT);
        assertEquals(D.withHour(11), fault.start());
        assertEquals(D.withHour(12).withMinute(30), fault.end());
        assertTrue(after.segments().stream()
                .noneMatch(s -> Laytime.PENDING_EVIDENCE.equals(s.segClass())));
        assertEquals(120, after.billableMinutes());
        assertEquals(0, new BigDecimal("2000.00").compareTo(after.amount()));
    }

    @Test
    void plannedStopIsNotUsedAsEvidence() {
        Laytime.Statement st = LaytimeEngine.compute(contract, sampleEvents(), D.withHour(15));
        // 计划保养 10:30–11:30 窗口内：10:30–11:00 仍按天气（港方确认）、11:00–11:30 按故障
        Laytime.Segment weather = seg(st, Laytime.WEATHER_EXCEPTED);
        assertEquals(D.withHour(11), weather.end(), "计划预告不改变天气区段边界");
        Laytime.Segment fault = seg(st, Laytime.CRANE_FAULT);
        assertEquals(D.withHour(11), fault.start(), "计划预告不改变故障区段边界");
        // 全部区段证据均不含计划预告事件（id=1）
        assertTrue(st.segments().stream().flatMap(s -> s.evidenceEventIds().stream())
                .noneMatch(id -> id == 1L), "计划停工不得替代实际停工凭证");
    }

    @Test
    void unconfirmedShipWeatherClaimDoesNotPauseClock() {
        // 没有港方确认天气、只有船方主张：全部计时
        List<Laytime.Event> ev = new ArrayList<>();
        ev.add(ev(Laytime.READY, 8, 0, null, null, "SHIP", true, null, null, "NOR-1"));
        ev.add(ev(Laytime.STOP_START, 10, 0, 10, 1, "SHIP", false, "WEATHER", null, "SHIP-1"));
        ev.add(ev(Laytime.STOP_END, 11, 0, 11, 1, "SHIP", false, null, null, "SHIP-2"));
        ev.add(ev(Laytime.COMPLETED, 12, 0, null, null, "PORT", true, null, null, "CPL-1"));
        Laytime.Statement st = LaytimeEngine.compute(contract, ev, D.withHour(13));
        assertEquals(120, st.billableMinutes(), "船方单方主张天气不暂停计时（待证仍计时）");
        assertTrue(st.claims().stream().anyMatch(c -> "PENDING".equals(c.status())));
    }

    @Test
    void repairReceiptLatenessMeasuredFromWindowEnd() {
        Laytime.Event receipt = sampleEvents().stream()
                .filter(e -> Laytime.REPAIR_RECEIPT.equals(e.eventType())).findFirst().orElseThrow();
        assertEquals(30, receipt.lateByMinutes(), "12:30 修复完成、13:00 送达 → 迟到 30 分钟");
    }
}
