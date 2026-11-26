package com.port.berth.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**排泊引擎产物：对某个船次在某泊位、某靠泊起点 S 的完整评估*/
public class Eval {

    public Long berthId;
    public String berthCode;
    public LocalDateTime berthFrom;      // S
    public LocalDateTime pilotStart;
    public LocalDateTime pilotEnd;
    public LocalDateTime opsStart;
    public LocalDateTime opsEnd;
    public LocalDateTime berthTo;

    public boolean feasible = true;
    public int score;
    public int waitMinutes;

    public final List<Reason> reasons = new ArrayList<>();
    public final List<Assignment> assignments = new ArrayList<>();
    public final List<SegSpec> segments = new ArrayList<>();
    public final List<OccSpec> occupations = new ArrayList<>();

    public Eval addReason(Reason r) {
        reasons.add(r);
        if ("BLOCKER".equals(r.severity)) feasible = false;
        return this;
    }

    /**候选不可行/等待原因*/
    public static class Reason {
        public String code;
        public String severity = "BLOCKER"; // BLOCKER / WARNING
        public String message;
        public String resourceType;
        public Long resourceId;
        public String resourceCode;
        public Long holderPlanId;
        public String holderPlanNo;
        public String holderVoyageNo;
        public String holderKind;
        public LocalDateTime holdExpiresAt;

        public Reason() {}

        public Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public Reason resource(String type, Long id, String code) {
            this.resourceType = type;
            this.resourceId = id;
            this.resourceCode = code;
            return this;
        }

        public Reason holder(Long planId, String planNo, String voyageNo,
                             String kind, LocalDateTime expiresAt) {
            this.holderPlanId = planId;
            this.holderPlanNo = planNo;
            this.holderVoyageNo = voyageNo;
            this.holderKind = kind;
            this.holdExpiresAt = expiresAt;
            return this;
        }
    }

    public static class Assignment {
        public String resourceType;
        public Long resourceId;
        public String code;
        public String name;
        public String role;
        public Assignment() {}
        public Assignment(String type, Long id, String code, String name, String role) {
            this.resourceType = type;
            this.resourceId = id;
            this.code = code;
            this.name = name;
            this.role = role;
        }
    }

    /**方案分段（实际工作段）*/
    public static class SegSpec {
        public int seq;
        public String segType;
        public String title;
        public String resourceType;
        public Long resourceId;
        public String resourceCode;
        public LocalDateTime start;
        public LocalDateTime end;

        public SegSpec(int seq, SegmentType t, String title,
                       String resourceType, Long resourceId, String resourceCode,
                       LocalDateTime start, LocalDateTime end) {
            this.seq = seq;
            this.segType = t.name();
            this.title = title;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.resourceCode = resourceCode;
            this.start = start;
            this.end = end;
        }
    }

    /**需要加锁/占用的资源区间（已含准备、交接、转场、周转缓冲）*/
    public static class OccSpec {
        public String resourceType;
        public Long resourceId;
        public String resourceKey;
        public LocalDateTime start;
        public LocalDateTime end;

        public OccSpec(String resourceType, Long resourceId,
                       LocalDateTime start, LocalDateTime end) {
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.resourceKey = resourceType + ":" + resourceId;
            this.start = start;
            this.end = end;
        }
    }
}
