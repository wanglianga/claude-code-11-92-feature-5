package com.port.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**排泊约束中的纯函数：区间重叠、UKC 净空、工作窗口包含判断*/
public final class ConstraintMath {

    private ConstraintMath() {}

    /**半开区间 [s1,e1) 与 [s2,e2) 是否重叠（端点相接不算重叠）*/
    public static boolean overlap(LocalDateTime s1, LocalDateTime e1,
                                  LocalDateTime s2, LocalDateTime e2) {
        return s1.isBefore(e2) && e1.isAfter(s2);
    }

    /**实际水下净空 = 图示水深 + 潮汐水位 - 船舶吃水*/
    public static BigDecimal clearance(BigDecimal chartDepth, BigDecimal tideHeight,
                                       BigDecimal draft) {
        return chartDepth.add(tideHeight).subtract(draft);
    }

    /**吃水下净空是否满足最小 UKC 要求（题目要求至少 1 米）*/
    public static boolean ukcOk(BigDecimal chartDepth, BigDecimal tideHeight,
                                BigDecimal draft, BigDecimal minUkc) {
        return clearance(chartDepth, tideHeight, draft).compareTo(minUkc) >= 0;
    }

    /**作业/占用区间 [s,e]（含缓冲）是否完整落在工作窗口 [ws,we] 内*/
    public static boolean withinWindow(LocalDateTime ws, LocalDateTime we,
                                       LocalDateTime s, LocalDateTime e) {
        return !ws.isAfter(s) && !we.isBefore(e);
    }
}
