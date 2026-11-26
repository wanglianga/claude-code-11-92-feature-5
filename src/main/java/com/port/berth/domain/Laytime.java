package com.port.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 滞期争议（laytime/demurrage）领域模型。
 * 回执事件 + 合同条款 → 可质证的可计时区段与金额；
 * 账单是纯函数 f(截至 asOf 已送达的回执, 合同, asOf)，可任意时刻回放。
 */
public final class Laytime {
    private Laytime() {}

    // ---------------- 事件类型 ----------------
    public static final String READY = "READY";                       // 准备就绪（NOR）
    public static final String WEATHER_STOP = "WEATHER_STOP";         // 天气停工开始（港方确认才暂停计时）
    public static final String WEATHER_RESUME = "WEATHER_RESUME";     // 天气复工
    public static final String STOP_START = "STOP_START";             // 作业中断（原因待证，可附船方主张）
    public static final String STOP_END = "STOP_END";                 // 恢复作业
    public static final String REPAIR_RECEIPT = "REPAIR_RECEIPT";     // 设备修复回执（证明故障窗口，可能迟到）
    public static final String COMPLETED = "COMPLETED";               // 完工
    public static final String PLANNED_STOP = "PLANNED_STOP";         // 计划停工预告（不构成实际停工凭证）

    // ---------------- 区段分类 ----------------
    public static final String FREE = "FREE";                         // 合同免计
    public static final String WEATHER_EXCEPTED = "WEATHER_EXCEPTED"; // 港方确认天气停工：暂停计时
    public static final String CRANE_FAULT = "CRANE_FAULT";           // 岸桥故障（港方设备）：按合同仍计时
    public static final String PENDING_EVIDENCE = "PENDING_EVIDENCE"; // 停工待证：暂计时，待凭证改判
    public static final String BILLABLE = "BILLABLE";                 // 正常计时

    public static final String PROVISIONAL = "PROVISIONAL";           // 暂计
    public static final String FINAL = "FINAL";                       // 定稿（完工回执到达后）

    /**合同条款*/
    public record Contract(long id, long voyageId, int freeMinutes,
                           BigDecimal ratePerHour, String exceptionRule, String remark) {
        /**每分钟费率 = 小时费率 / 60*/
        public BigDecimal ratePerMinute() {
            return ratePerHour.divide(BigDecimal.valueOf(60), 10,
                    java.math.RoundingMode.HALF_UP);
        }
    }

    /**一条双方回执/事件*/
    public record Event(long id, long voyageId, String eventType,
                        LocalDateTime occurredAt, LocalDateTime receivedAt,
                        String party, boolean confirmed, String claimCause,
                        LocalDateTime windowEnd, String evidenceRef, String note) {
        /**
         * 迟到分钟数（送达晚于业务完结）；不迟到为 0。
         * 带窗口的回执（如修复回执）以窗口结束时刻衡量迟到：
         * 修复完成 12:30、回执 13:00 送达 → 迟到 30 分钟。
         */
        public long lateByMinutes() {
            LocalDateTime base = windowEnd != null ? windowEnd : occurredAt;
            long m = java.time.Duration.between(base, receivedAt).toMinutes();
            return Math.max(0, m);
        }
    }

    /**计算后的一个可质证区段*/
    public record Segment(LocalDateTime start, LocalDateTime end, String segClass,
                          long minutes, boolean billable, String label,
                          List<Long> evidenceEventIds) {}

    /**船方主张的处置结果：PENDING 待证 / REFUTED 被港方凭证否定 / HONORED 与港方确认一致*/
    public record Claim(long eventId, String claimCause, String status,
                        Long refutedByEventId, String note) {}

    /**账单（暂计或定稿）*/
    public record Statement(String kind, LocalDateTime asOf,
                            LocalDateTime readyAt, LocalDateTime countFrom, LocalDateTime endAt,
                            long billableMinutes, BigDecimal amount,
                            List<Segment> segments, List<Claim> claims,
                            String note) {}
}
