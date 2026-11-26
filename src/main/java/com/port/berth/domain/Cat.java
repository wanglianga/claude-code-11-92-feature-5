package com.port.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**基础数据行模型集合*/
public final class Cat {
    private Cat() {}

    public record AppUser(long id, String username, String displayName, String role) {}

    public record Ship(long id, String code, String name,
                       BigDecimal lengthM, BigDecimal draftM, String cargoType) {}

    public record Voyage(long id, String voyageNo, long shipId, String agentName,
                         LocalDateTime etaStart, LocalDateTime etaEnd,
                         int workDurationMin, int craneCount, String status,
                         int etaRevision, String remark,
                         LocalDateTime createdAt,
                         Ship ship) {}

    public record Berth(long id, String code, String name,
                        BigDecimal maxLengthM, BigDecimal chartDepthM,
                        BigDecimal ukcMinM, List<String> cargoTypes) {}

    public record TideSlot(long id, LocalDateTime start, LocalDateTime end,
                           BigDecimal height, boolean revised, String revisionReason) {}

    public record WorkWindow(String resourceType, long resourceId,
                             LocalDateTime start, LocalDateTime end) {}

    public record Pilot(long id, String code, String name, String qualification,
                        BigDecimal maxLengthM, BigDecimal maxDraftM, int handoverMin,
                        List<WorkWindow> windows) {}

    public record Tug(long id, String code, String name,
                      BigDecimal maxLengthM, BigDecimal maxDraftM,
                      BigDecimal bollardPullT, int turnaroundMin,
                      List<WorkWindow> windows) {}

    public record Crane(long id, String code, String name, int transferMin,
                        boolean active, List<WorkWindow> windows) {}

    public record Fault(long id, String resourceType, long resourceId,
                        LocalDateTime start, LocalDateTime end, String reason) {}

    public record Occupation(long id, long planId, long voyageId,
                             String resourceType, long resourceId, String resourceKey,
                             LocalDateTime start, LocalDateTime end,
                             String kind, String status, LocalDateTime holdExpiresAt) {}
}
