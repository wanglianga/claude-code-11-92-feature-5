package com.port.berth;

import com.port.berth.domain.ConstraintMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintMathTest {

    private final LocalDateTime t = LocalDateTime.of(2026, 11, 26, 20, 0);

    @Test
    void halfOpenOverlapSemantics() {
        // [20:00,21:00) vs [20:30,21:30) 重叠
        assertTrue(ConstraintMath.overlap(t, t.plusHours(1),
                t.plusMinutes(30), t.plusMinutes(90)));
        // 端点相接 [20:00,21:00) vs [21:00,22:00) 不算重叠（周转缓冲边界可衔接）
        assertFalse(ConstraintMath.overlap(t, t.plusHours(1),
                t.plusHours(1), t.plusHours(2)));
        // 完全包含
        assertTrue(ConstraintMath.overlap(t.minusMinutes(30), t.plusHours(2),
                t, t.plusHours(1)));
        // 完全分离
        assertFalse(ConstraintMath.overlap(t, t.plusMinutes(30),
                t.plusHours(2), t.plusHours(3)));
    }

    @Test
    void ukcRequiresAtLeastOneMeter() {
        BigDecimal depth = new BigDecimal("9.5");
        BigDecimal draft = new BigDecimal("11.5");
        BigDecimal min = BigDecimal.ONE;
        // 需要潮位 >= 3.0m
        assertFalse(ConstraintMath.ukcOk(depth, new BigDecimal("2.80"), draft, min));
        assertTrue(ConstraintMath.ukcOk(depth, new BigDecimal("3.00"), draft, min));
        assertTrue(ConstraintMath.ukcOk(depth, new BigDecimal("3.20"), draft, min));
        // 2.75m 时净空 0.75m
        assertFalse(ConstraintMath.ukcOk(depth, new BigDecimal("2.75"), draft, min));
    }

    @Test
    void workWindowContainment() {
        LocalDateTime ws = t.minusHours(3);   // 17:00
        LocalDateTime we = t.plusHours(6);    // 02:00 next day-ish
        assertTrue(ConstraintMath.withinWindow(ws, we,
                t.minusMinutes(75), t.plusMinutes(45)));
        // 含交接缓冲后超出值班窗口 -> 不满足
        assertFalse(ConstraintMath.withinWindow(ws, t.plusMinutes(30),
                t, t.plusMinutes(90)));
    }
}
