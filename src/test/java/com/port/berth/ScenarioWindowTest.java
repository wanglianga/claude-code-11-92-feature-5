package com.port.berth;

import com.port.berth.domain.ConstraintMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 题目样例的可执行规格：
 * B1 图示水深 9.5m、UKC 要求 1m、A/B 船吃水 11.5m → 潮位必须 ≥ 3.0m。
 * 用播种数据中关键潮汐时段的真实水位，验证在 ETA 19:30–20:30 内、
 * 15 分钟候选网格上，只有 S=20:00 一个靠泊起点能通过全窗口 UKC 校验。
 */
class ScenarioWindowTest {

    private static final BigDecimal DEPTH = new BigDecimal("9.5");
    private static final BigDecimal DRAFT = new BigDecimal("11.5");
    private static final BigDecimal UKC = BigDecimal.ONE;

    /** 与 SeedService 覆盖值一致的傍晚潮汐（30 分钟一档，key=档起点分钟数） */
    private Map<LocalDateTime, BigDecimal> tides() {
        LocalDateTime d = LocalDateTime.of(2026, 11, 26, 0, 0);
        Map<LocalDateTime, BigDecimal> m = new HashMap<>();
        double[][] hv = {
                {19 * 60, 2.50}, {19 * 60 + 30, 2.80},
                {20 * 60, 3.40}, {20 * 60 + 30, 3.30},
                {21 * 60, 3.20}, {21 * 60 + 30, 2.75},
                {22 * 60, 2.60}, {22 * 60 + 30, 2.40},
        };
        for (double[] x : hv) {
            int mins = (int) x[0];
            m.put(d.plusMinutes(mins), BigDecimal.valueOf(x[1]));
        }
        return m;
    }

    /** 复刻引擎 checkTide：UKC 窗口 [S, opsEnd]，与每个重叠的半小时档校验 */
    private boolean feasible(LocalDateTime s, int cargoDurationMin) {
        LocalDateTime opsEnd = s.plusMinutes(30).plusMinutes(cargoDurationMin);
        Map<LocalDateTime, BigDecimal> tides = tides();
        for (var e : tides.entrySet()) {
            LocalDateTime ts = e.getKey();
            LocalDateTime te = ts.plusMinutes(30);
            LocalDateTime os = ts.isAfter(s) ? ts : s;
            LocalDateTime oe = te.isBefore(opsEnd) ? te : opsEnd;
            if (!os.isBefore(oe)) continue;
            if (!ConstraintMath.ukcOk(DEPTH, e.getValue(), DRAFT, UKC)) return false;
        }
        return true;
    }

    @Test
    void onlyTwentyOClockBerthStartWorks() {
        LocalDateTime etaStart = LocalDateTime.of(2026, 11, 26, 19, 30);
        LocalDateTime etaEnd = etaStart.plusHours(1); // 20:30
        LocalDateTime unique = null;
        int feasibleCount = 0;
        for (LocalDateTime s = etaStart; !s.isAfter(etaEnd); s = s.plusMinutes(15)) {
            if (feasible(s, 60)) { feasibleCount++; unique = s; }
        }
        assertEquals(1, feasibleCount, "应当恰好只有一个可行起点");
        assertEquals(20, unique.getHour());
        assertEquals(0, unique.getMinute());
    }
}
