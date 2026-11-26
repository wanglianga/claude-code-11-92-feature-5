package com.port.berth.service;

import com.port.berth.domain.Cat;
import com.port.berth.domain.Eval;
import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扰动后的重排：已完成工作段与 work_record 台账原样保留，
 * 未执行段取消并释放占用，按“剩余作业分钟”生成新版本方案；可一步确认。
 */
@Service
public class ReplanService {

    private final PlanningEngine engine;
    private final PlanService planService;
    private final PlanDao planDao;

    @Value("${app.planning.candidate-step-minutes:15}")
    private int stepMinutes;
    @Value("${app.planning.max-wait-minutes:960}")
    private int maxWaitMinutes;

    public ReplanService(PlanningEngine engine, PlanService planService, PlanDao planDao) {
        this.engine = engine;
        this.planService = planService;
        this.planDao = planDao;
    }

    @Transactional
    public Map<String, Object> replan(long planId, boolean autoConfirm) {
        PlanDao.PlanRow old = planService.mustPlan(planId);
        Cat.Voyage v = planService.mustVoyage(old.voyageId);
        if (!"CONFIRMED".equals(old.status)) {
            throw ApiException.conflict("PLAN_NOT_CONFIRMED",
                    "只能重排已确认且受扰动的方案，当前状态：" + old.status);
        }

        int doneMinutes = planDao.sumHandlingMinutes(v.id());
        int remaining = Math.max(0, v.workDurationMin() - doneMinutes);
        if (remaining <= 0) {
            throw ApiException.badRequest("NOTHING_TO_REPLAN",
                    "该船次计划装卸时长已全部完成，无剩余作业需要重排；"
                            + "引航与装卸记录继续保留");
        }

        // 1) 优先尝试原泊位/原时刻（适用于岸桥可直接替换的情形）
        Eval same = engine.evaluateAt(v, old.berthId, old.berthFrom, remaining);
        Eval chosen;
        String mode;
        if (same.feasible) {
            chosen = same;
            mode = "SAME_SLOT_REASSIGN";
        } else {
            // 2) 否则全局搜索最早可行的新时刻（等潮/等班次/等资源）
            List<Eval> all = engine.evaluateAll(v, remaining, stepMinutes, maxWaitMinutes);
            chosen = all.stream().filter(e -> e.feasible)
                    .max((a, b) -> Integer.compare(a.score, b.score))
                    .orElseThrow(() -> ApiException.conflict("NO_FEASIBLE_REPLAN",
                            "扰动后在搜索范围内找不到可行的重排方案，需要人工延长等待或调整资源")
                            .details(same.reasons));
            mode = "RESCHEDULE";
        }

        // 3) 旧版本：未完成段取消、占用释放、状态转 REPLANNED；DONE 段与记录不动
        planDao.cancelPendingSegments(old.id,
                "扰动后重排：未执行段由新版本 " + "(待生成)" + " 承接");
        int released = planDao.releaseOccupations(old.id, "REPLANNED");
        planDao.updateStatus(old.id, "REPLANNED");

        String note = String.format(
                "重排 v%d 替代 %s；已完成装卸 %d 分钟，剩余 %d 分钟；策略=%s；释放占用 %d 条",
                old.versionNo + 1, old.planNo, doneMinutes, remaining, mode, released);
        PlanDao.PlanRow np = planService.saveEvalAsPlan(
                v, chosen, "CANDIDATE", old.versionNo + 1, old.id, old.disruptionId, note);

        // 修正取消原因中的新版本号
        planDao.jdbc().update(
                "UPDATE plan_segment SET affect_reason = REPLACE(affect_reason, '(待生成)', ?) " +
                        "WHERE plan_id=? AND status='CANCELLED'",
                np.planNo, old.id);

        planDao.event(v.id(), np.id, "REPLAN_CREATED", note,
                planService.writeJson(Map.of(
                        "oldPlanId", old.id, "mode", mode,
                        "doneMinutes", doneMinutes, "remainingMinutes", remaining)));

        boolean confirmed = false;
        if (autoConfirm) {
            planService.confirm(np.id);
            confirmed = true;
            planDao.jdbc().update(
                    "UPDATE impact_item SET handled=true WHERE plan_id=?", old.id);
            planDao.event(v.id(), np.id, "REPLAN_CONFIRMED",
                    "重排方案 " + np.planNo + " 已确认并对班组生效", null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("autoConfirmed", confirmed);
        result.put("doneHandlingMinutes", doneMinutes);
        result.put("remainingMinutes", remaining);
        result.put("releasedOccupations", released);
        result.put("oldPlan", planService.planDetail(old.id));
        result.put("newPlan", planService.planDetail(np.id));
        result.put("preservedRecords", planDao.listWorkRecords(v.id()));
        return result;
    }

    /**
     * 显式“改靠 / 改时”指令：调度指定新泊位/新靠泊起点，系统重校全套约束，
     * 逐段说明受影响工作段，并生成新版本（可一步确认）。已完成记录同样保留。
     */
    @Transactional
    public Map<String, Object> changeBerth(long planId, Long newBerthId,
                                          LocalDateTime newBerthFrom, boolean autoConfirm) {
        PlanDao.PlanRow old = planService.mustPlan(planId);
        Cat.Voyage v = planService.mustVoyage(old.voyageId);
        if (!"CONFIRMED".equals(old.status)) {
            throw ApiException.conflict("PLAN_NOT_CONFIRMED",
                    "只能对已确认计划下达改靠指令，当前状态：" + old.status);
        }
        long targetBerth = newBerthId != null ? newBerthId : old.berthId;
        LocalDateTime targetFrom = newBerthFrom != null ? newBerthFrom : old.berthFrom;

        int doneMinutes = planDao.sumHandlingMinutes(v.id());
        int remaining = Math.max(0, v.workDurationMin() - doneMinutes);
        int evalDur = remaining > 0 ? remaining : Math.min(v.workDurationMin(), 1);

        Eval target = engine.evaluateAt(v, targetBerth, targetFrom, evalDur);
        if (!target.feasible) {
            throw ApiException.conflict("CHANGE_BERTH_INFEASIBLE",
                            "改靠目标在当前潮汐/人员/设备状态下不可行，已列出具体约束冲突")
                    .details(target.reasons);
        }

        String toCode = target.berthCode;

        String summary = String.format("改靠指令：%s %s → %s %s",
                old.planNo, PlanningEngine.fmt(old.berthFrom),
                toCode, PlanningEngine.fmt(targetFrom));
        long disruptionId = planDao.insertDisruption("BERTH_CHANGE", planId,
                planService.writeJson(Map.of(
                        "planId", planId,
                        "fromBerthId", old.berthId,
                        "toBerthId", targetBerth,
                        "newBerthFrom", targetFrom.toString())),
                summary);

        // 逐段说明影响：引航/拖轮/靠泊/装卸/离泊时刻或资源都会随新泊位时间轴重排
        List<PlanDao.SegmentRow> oldSegs = planDao.listSegments(old.id);
        for (PlanDao.SegmentRow s : oldSegs) {
            String msg = "改靠至 " + toCode + " " + PlanningEngine.fmt(targetFrom)
                    + "，【" + s.title + "】时刻/资源需按新计划调整"
                    + ("DONE".equals(s.status) ? "（该段已完成，仅留痕不覆盖）" : "");
            planDao.insertImpact(disruptionId, old.id, v.id(), s.id,
                    "DONE".equals(s.status) ? "WARNING" : "BLOCKER",
                    "BERTH_CHANGE", msg, s.startAt, s.endAt);
            if (!"DONE".equals(s.status)) {
                planDao.markSegmentAffected(s.id, "BERTH_CHANGE: " + msg);
            }
        }

        planDao.cancelPendingSegments(old.id, "改靠指令：未执行段由新版本承接");
        int released = planDao.releaseOccupations(old.id, "BERTH_CHANGE");
        planDao.updateStatus(old.id, "REPLANNED");

        String note = String.format("改靠重排 v%d 替代 %s；目标泊位 #%d %s；剩余作业 %d 分钟；释放占用 %d 条",
                old.versionNo + 1, old.planNo, targetBerth, PlanningEngine.fmt(targetFrom),
                remaining, released);
        PlanDao.PlanRow np = planService.saveEvalAsPlan(
                v, target, "CANDIDATE", old.versionNo + 1, old.id, disruptionId, note);
        if (autoConfirm) {
            planService.confirm(np.id);
            planDao.jdbc().update(
                    "UPDATE impact_item SET handled=true WHERE plan_id=?", old.id);
        }
        planDao.event(v.id(), np.id, "BERTH_CHANGED", note, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "BERTH_CHANGE");
        result.put("autoConfirmed", autoConfirm);
        result.put("targetBerthCode", toCode);
        result.put("targetBerthFrom", targetFrom);
        result.put("remainingMinutes", remaining);
        result.put("releasedOccupations", released);
        result.put("oldPlan", planService.planDetail(old.id));
        result.put("newPlan", planService.planDetail(np.id));
        result.put("preservedRecords", planDao.listWorkRecords(v.id()));
        result.put("disruption", disruptionServiceDetail(disruptionId));
        return result;
    }

    private Object disruptionServiceDetail(long id) {
        // 经 JDBC 直接组装，避免再注入 DisruptionService 形成循环依赖
        Map<String, Object> d = planDao.jdbc().queryForMap(
                "SELECT id, dtype, summary, created_at FROM disruption WHERE id=?", id);
        d.put("impacts", planDao.listImpacts(id));
        return d;
    }
}
