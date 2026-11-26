package com.port.berth.service;

import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 现场执行与作业记录。引航、装卸完成时写入只增不改的 work_record 台账，
 * 并把对应分段置 DONE；已完成记录不提供任何修改/删除路径，DB 触发器也禁止 UPDATE/DELETE。
 */
@Service
public class ExecutionService {

    private final PlanDao planDao;
    private final PlanService planService;

    public ExecutionService(PlanDao planDao, PlanService planService) {
        this.planDao = planDao;
        this.planService = planService;
    }

    @Transactional
    public Map<String, Object> startSegment(long segmentId, String operator) {
        PlanDao.SegmentRow seg = mustSegment(segmentId);
        PlanDao.PlanRow plan = planService.mustPlan(seg.planId);
        if (!"CONFIRMED".equals(plan.status)) {
            throw ApiException.conflict("PLAN_NOT_EFFECTIVE",
                    "只有已确认计划才能对班组生效并开工，当前状态：" + plan.status);
        }
        if ("DONE".equals(seg.status)) {
            throw ApiException.conflict("SEGMENT_DONE", "该工作段已完成，不能重复开工");
        }
        planDao.jdbc().update(
                "UPDATE plan_segment SET status='IN_PROGRESS' WHERE id=?", segmentId);
        planDao.event(plan.voyageId, plan.id, "SEGMENT_STARTED",
                seg.title + " 已开工（" + (operator != null ? operator : "班组") + "）", null);
        return segmentView(segmentId);
    }

    @Transactional
    public Map<String, Object> completeSegment(long segmentId, CompleteRequest req) {
        PlanDao.SegmentRow seg = mustSegment(segmentId);
        PlanDao.PlanRow plan = planService.mustPlan(seg.planId);
        if (!"CONFIRMED".equals(plan.status)) {
            throw ApiException.conflict("PLAN_NOT_EFFECTIVE",
                    "只有已确认计划的工作段可以回填完成记录，当前方案状态：" + plan.status);
        }
        if ("DONE".equals(seg.status)) {
            throw ApiException.conflict("SEGMENT_DONE",
                    "该工作段已有完成记录；已完成引航/装卸记录不可覆盖，如需延续请走重排");
        }

        String operator = req != null && req.operatorName() != null ? req.operatorName() : "当班班组";
        String note = req != null ? req.resultNote() : null;
        int workMinutes = req != null && req.workMinutes() != null ? req.workMinutes() : 0;
        int movedUnits = req != null && req.movedUnits() != null ? req.movedUnits() : 0;

        String recordType = switch (seg.segType) {
            case "PILOTAGE" -> "PILOTAGE";
            case "CARGO_OP" -> "CARGO_HANDLING";
            default -> "SEGMENT_LOG";
        };

        // SEGMENT_LOG 不进不可变台账？——同样写入 work_record 以便统一留痕，
        // 但只有 PILOTAGE / CARGO_HANDLING 参与“已完成不可覆盖”的重排保留逻辑。
        long recordId = planDao.insertWorkRecord(plan.voyageId, plan.id, seg.id,
                recordType, operator, workMinutes, movedUnits, note,
                req != null && req.startedAt() != null ? req.startedAt() : null);

        planDao.updateSegmentStatus(segmentId, "DONE", recordId);
        planDao.event(plan.voyageId, plan.id, "SEGMENT_COMPLETED",
                seg.title + " 已完成并写入不可变记录 #" + recordId
                        + ("CARGO_HANDLING".equals(recordType) ? "（作业 " + workMinutes + " 分钟）" : ""),
                planService.writeJson(Map.of(
                        "recordId", recordId, "recordType", recordType,
                        "workMinutes", workMinutes, "movedUnits", movedUnits)));
        return segmentView(segmentId);
    }

    public record CompleteRequest(String operatorName, Integer workMinutes,
                                  Integer movedUnits, String resultNote,
                                  LocalDateTime startedAt) {}

    /**
     * 登记部分作业量（如已完成 30 分钟装卸），写不可变台账但不把工作段置 DONE；
     * 用于“扰动发生时作业中断、已完成工作量必须保留”的场景。
     */
    @Transactional
    public Map<String, Object> logWork(long segmentId, CompleteRequest req) {
        PlanDao.SegmentRow seg = mustSegment(segmentId);
        PlanDao.PlanRow plan = planService.mustPlan(seg.planId);
        if (!"CONFIRMED".equals(plan.status)) {
            throw ApiException.conflict("PLAN_NOT_EFFECTIVE",
                    "只有已确认计划可以登记作业，当前方案状态：" + plan.status);
        }
        if ("DONE".equals(seg.status)) {
            throw ApiException.conflict("SEGMENT_DONE", "工作段已完成，不能再追加工时；记录不可覆盖");
        }
        String operator = req != null && req.operatorName() != null ? req.operatorName() : "当班班组";
        int workMinutes = req != null && req.workMinutes() != null ? req.workMinutes() : 0;
        int movedUnits = req != null && req.movedUnits() != null ? req.movedUnits() : 0;
        String recordType = "CARGO_OP".equals(seg.segType) ? "CARGO_HANDLING" : seg.segType;

        long recordId = planDao.insertWorkRecord(plan.voyageId, plan.id, seg.id,
                recordType, operator, workMinutes, movedUnits,
                req != null ? req.resultNote() : null,
                req != null ? req.startedAt() : null);
        if ("PLANNED".equals(seg.status)) {
            planDao.jdbc().update(
                    "UPDATE plan_segment SET status='IN_PROGRESS' WHERE id=?", segmentId);
        }
        planDao.event(plan.voyageId, plan.id, "WORK_LOGGED",
                seg.title + " 登记不可变作业台账 #" + recordId
                        + "（" + workMinutes + " 分钟 / " + movedUnits + " 箱量）",
                planService.writeJson(Map.of("recordId", recordId,
                        "workMinutes", workMinutes, "movedUnits", movedUnits)));
        return segmentView(segmentId);
    }

    private PlanDao.SegmentRow mustSegment(long id) {
        return planDao.findSegment(id)
                .orElseThrow(() -> ApiException.notFound("工作段不存在: " + id));
    }

    private Map<String, Object> segmentView(long id) {
        PlanDao.SegmentRow s = mustSegment(id);
        return Map.of(
                "id", s.id, "planId", s.planId, "segType", s.segType,
                "title", s.title, "status", s.status,
                "startAt", s.startAt, "endAt", s.endAt,
                "affected", s.affected, "recordId", s.recordId == null ? 0 : s.recordId);
    }
}
