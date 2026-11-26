package com.port.berth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.domain.Cat;
import com.port.berth.domain.Eval;
import com.port.berth.repo.CatalogDao;
import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**候选落库、暂存留资源、原子确认、释放/取消*/
@Service
public class PlanService {

    private final PlanningEngine engine;
    private final CatalogDao catalog;
    private final PlanDao planDao;
    private final ObjectMapper json;

    @Value("${app.planning.hold-on-stage:true}")
    private boolean holdOnStageDefault;
    @Value("${app.planning.default-hold-ttl-seconds:120}")
    private int defaultTtlSeconds;
    @Value("${app.planning.candidate-step-minutes:15}")
    private int stepMinutes;
    @Value("${app.planning.max-wait-minutes:960}")
    private int maxWaitMinutes;

    public PlanService(PlanningEngine engine, CatalogDao catalog,
                       PlanDao planDao, ObjectMapper json) {
        this.engine = engine;
        this.catalog = catalog;
        this.planDao = planDao;
        this.json = json;
    }

    public boolean isHoldOnStageDefault() {
        return holdOnStageDefault;
    }

    // ====================================================================
    // 候选生成
    // ====================================================================

    @Transactional
    public List<PlanDao.PlanRow> generateCandidates(long voyageId) {
        Cat.Voyage v = mustVoyage(voyageId);
        if ("WITHDRAWN".equals(v.status())) {
            throw ApiException.badRequest("VOYAGE_WITHDRAWN", "该船次已撤回授权，不能再生成计划");
        }

        List<Eval> evals = engine.evaluateAll(v, stepMinutes, maxWaitMinutes);

        // 旧的未处理候选作废（STAGED/CONFIRMED 不受影响）
        planDao.jdbc().update(
                "UPDATE berth_plan SET status='SUPERSEDED', updated_at=now() " +
                        "WHERE voyage_id=? AND status='CANDIDATE'", voyageId);

        String batchId = "b-" + UUID.randomUUID();

        // ETA 窗口内的不可行点全部保留（解释为什么看起来有空位却不能靠）
        List<Eval> infeasibleInWindow = evals.stream()
                .filter(e -> !e.feasible && !e.berthFrom.isAfter(v.etaEnd()))
                .toList();

        // 可行点按分数（越早越高）取前 6
        List<Eval> feasible = evals.stream()
                .filter(e -> e.feasible)
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .limit(6)
                .toList();

        List<PlanDao.PlanRow> saved = new ArrayList<>();
        for (Eval e : infeasibleInWindow) {
            saved.add(persistCandidate(v, e, batchId));
        }
        for (Eval e : feasible) {
            saved.add(persistCandidate(v, e, batchId));
        }

        planDao.event(voyageId, null, "CANDIDATES_GENERATED",
                "生成 " + feasible.size() + " 个可行候选、"
                        + infeasibleInWindow.size() + " 个窗口内不可行评估点",
                null);
        return saved;
    }

    private PlanDao.PlanRow persistCandidate(Cat.Voyage v, Eval e, String batchId) {
        PlanDao.PlanRow p = saveEvalAsPlan(v, e, "CANDIDATE", 1, null, null, null);
        p.batchId = batchId;
        planDao.jdbc().update("UPDATE berth_plan SET batch_id=? WHERE id=?", batchId, p.id);
        return p;
    }

    /**把引擎评估结果落为方案（候选或重排新版本），并持久化分段*/
    @Transactional
    public PlanDao.PlanRow saveEvalAsPlan(Cat.Voyage v, Eval e, String status, int versionNo,
                                          Long replanOfId, Long disruptionId, String note) {
        PlanDao.PlanRow p = new PlanDao.PlanRow();
        p.planNo = "PL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        p.voyageId = v.id();
        p.berthId = e.berthId;
        p.status = status;
        p.feasible = e.feasible;
        p.strategy = holdOnStageDefault ? "TENTATIVE_HOLD" : "ATOMIC_CONFIRM";
        p.berthFrom = e.berthFrom;
        p.pilotStart = e.pilotStart;
        p.opsStart = e.opsStart;
        p.opsEnd = e.opsEnd;
        p.berthTo = e.berthTo;
        p.score = e.score;
        p.waitMinutes = e.waitMinutes;
        p.reasonsJson = writeJson(e.reasons);
        p.assignmentsJson = writeJson(e.assignments);
        p.versionNo = versionNo;
        p.replanOfId = replanOfId;
        p.disruptionId = disruptionId;
        p.note = note;
        planDao.insertPlan(p);
        for (Eval.SegSpec s : e.segments) {
            planDao.insertSegment(p.id, s.seq, s.segType, s.title,
                    s.resourceType, s.resourceId, s.resourceCode, s.start, s.end);
        }
        return p;
    }

    // ====================================================================
    // 暂存（可选择是否一并暂留全套资源）
    // ====================================================================

    @Transactional
    public PlanDao.PlanRow stage(long planId, Integer ttlSeconds, Boolean holdResources) {
        PlanDao.PlanRow p = mustPlan(planId);
        Cat.Voyage v = mustVoyage(p.voyageId);
        if ("WITHDRAWN".equals(v.status())) {
            throw ApiException.badRequest("VOYAGE_WITHDRAWN", "该船次已撤回授权，不能暂存方案");
        }
        if ("CONFIRMED".equals(p.status)) {
            throw ApiException.conflict("PLAN_CONFIRMED", "方案已确认，无需暂存");
        }
        if ("STAGED".equals(p.status)) {
            return p; // 幂等
        }
        if (!"CANDIDATE".equals(p.status) && !"EXPIRED".equals(p.status)
                && !"CANCELLED".equals(p.status)) {
            throw ApiException.conflict("PLAN_NOT_STAGEABLE",
                    "方案状态 " + p.status + " 不能暂存");
        }

        int cargoMin = (int) Duration.between(p.opsStart, p.opsEnd).toMinutes();
        Eval eval = engine.evaluateAt(v, p.berthId, p.berthFrom, cargoMin);
        if (!eval.feasible) {
            throw ApiException.conflict("PLAN_NOT_FEASIBLE",
                            "该候选在当前资源/潮汐/故障状态下已不可行，无法暂存")
                    .details(eval.reasons);
        }

        boolean hold = holdResources != null ? holdResources : holdOnStageDefault;
        int ttl = ttlSeconds != null ? ttlSeconds : defaultTtlSeconds;

        if (hold) {
            LocalDateTime expires = LocalDateTime.now().plusSeconds(ttl);
            for (Eval.OccSpec occ : eval.occupations) {
                planDao.insertOccupation(p.id, v.id(), occ.resourceType, occ.resourceId,
                        occ.resourceKey, occ.start, occ.end, "TENTATIVE");
            }
            planDao.markStaged(p.id, "TENTATIVE_HOLD", expires);
            p.status = "STAGED";
            p.strategy = "TENTATIVE_HOLD";
            p.holdExpiresAt = expires;
            planDao.event(v.id(), p.id, "PLAN_STAGED_HOLD",
                    "调度暂存方案并暂留泊位/引航/拖轮/岸桥全套资源，TTL " + ttl + " 秒，"
                            + "暂留至 " + PlanningEngine.fmt(expires),
                    writeJson(Map.of("ttlSeconds", ttl, "expiresAt", expires.toString())));
        } else {
            // 设计分支二：只保存候选，不占资源，确认时才原子锁定
            planDao.jdbc().update("""
                    UPDATE berth_plan SET status='STAGED', strategy='ATOMIC_CONFIRM',
                           staged_at=now(), hold_expires_at=NULL, updated_at=now()
                    WHERE id=?
                    """, p.id);
            p.status = "STAGED";
            p.strategy = "ATOMIC_CONFIRM";
            planDao.event(v.id(), p.id, "PLAN_STAGED_NO_HOLD",
                    "仅暂存候选，未占用资源；确认时将原子锁定全套资源", null);
        }
        return mustPlan(p.id);
    }

    @Transactional
    public PlanDao.PlanRow cancelStage(long planId, String reason) {
        PlanDao.PlanRow p = mustPlan(planId);
        int released = planDao.releaseOccupations(p.id, "DISPATCHER_CANCEL");
        planDao.markReleased(p.id, "CANCELLED",
                reason != null ? reason : "DISPATCHER_CANCEL");
        planDao.event(p.voyageId, p.id, "PLAN_CANCELLED",
                "方案取消，释放 " + released + " 条资源占用", null);
        return mustPlan(planId);
    }

    /**TTL 到期处理（由 sweeper 逐条调用以写事件）*/
    @Transactional
    public boolean expireIfDue(long planId) {
        PlanDao.PlanRow p = planDao.findPlan(planId).orElse(null);
        if (p == null || !"STAGED".equals(p.status) || p.holdExpiresAt == null) return false;
        if (p.holdExpiresAt.isAfter(LocalDateTime.now())) return false;

        int released = planDao.releaseOccupations(p.id, "TTL_EXPIRED");
        planDao.markReleased(p.id, "EXPIRED", "TTL_EXPIRED");
        planDao.event(p.voyageId, p.id, "HOLD_EXPIRED",
                "暂留超过 TTL 未确认，释放 " + released + " 条资源占用；"
                        + "其他船次现在可以锁定该资源组合",
                writeJson(Map.of("holdExpiresAt", p.holdExpiresAt.toString(),
                        "occupationsReleased", released)));
        return true;
    }

    // ====================================================================
    // 确认：原子锁定全套资源
    // ====================================================================

    @Transactional
    public PlanDao.PlanRow confirm(long planId) {
        PlanDao.PlanRow p = mustPlan(planId);
        Cat.Voyage v = mustVoyage(p.voyageId);
        if ("WITHDRAWN".equals(v.status())) {
            throw ApiException.badRequest("VOYAGE_WITHDRAWN", "该船次已撤回授权，不能确认计划");
        }
        if ("CONFIRMED".equals(p.status)) {
            return p; // 幂等
        }
        if (!"CANDIDATE".equals(p.status) && !"STAGED".equals(p.status)) {
            throw ApiException.conflict("PLAN_NOT_CONFIRMABLE",
                    "方案状态 " + p.status + " 不能确认（需为候选或已暂存）");
        }

        // 串行化同一船次的确认
        Long locked = planDao.jdbc().queryForObject(
                "SELECT id FROM voyage WHERE id=? FOR UPDATE", Long.class, v.id());
        if (locked == null) throw ApiException.notFound("船次不存在");

        for (PlanDao.PlanRow other : planDao.listPlansByVoyage(v.id())) {
            if (other.id != p.id && "CONFIRMED".equals(other.status)) {
                throw ApiException.conflict("VOYAGE_ALREADY_CONFIRMED",
                        "该船次已有确认方案 " + other.planNo + "，同一船次只能有一个确认结果");
            }
        }

        int cargoMin = (int) Duration.between(p.opsStart, p.opsEnd).toMinutes();
        Eval eval = engine.evaluateAt(v, p.berthId, p.berthFrom, cargoMin);
        if (!eval.feasible) {
            throw ApiException.conflict("PLAN_NOT_FEASIBLE",
                            "确认前重校失败：资源/潮汐/设备状态已变化，全套资源未能锁定")
                    .details(eval.reasons);
        }

        // 若来自暂存，先释放自身 TENTATIVE 行，再插入 FIRM 行（同事务）
        planDao.releaseOccupations(p.id, "CONFIRM_CONVERT");
        for (Eval.OccSpec occ : eval.occupations) {
            planDao.insertOccupation(p.id, v.id(), occ.resourceType, occ.resourceId,
                    occ.resourceKey, occ.start, occ.end, "FIRM");
        }

        planDao.markConfirmed(p.id);
        catalog.updateVoyageStatus(v.id(), "CONFIRMED");

        // 同船次其余候选/暂存作废
        for (PlanDao.PlanRow other : planDao.listPlansByVoyage(v.id())) {
            if (other.id == p.id) continue;
            if ("CANDIDATE".equals(other.status)) {
                planDao.updateStatus(other.id, "SUPERSEDED");
            } else if ("STAGED".equals(other.status)) {
                planDao.releaseOccupations(other.id, "OTHER_CONFIRMED");
                planDao.updateStatus(other.id, "CANCELLED");
            }
        }

        planDao.event(v.id(), p.id, "PLAN_CONFIRMED",
                "方案 " + p.planNo + " 已确认，泊位/引航/拖轮/岸桥全套资源原子锁定，"
                        + "计划即刻对引航、拖轮、岸桥班组生效",
                writeJson(Map.of("planNo", p.planNo)));
        return mustPlan(p.id);
    }

    // ====================================================================
    // 撤回授权：释放该船次全部未终结方案与占用
    // ====================================================================

    @Transactional
    public void withdrawVoyage(long voyageId) {
        Cat.Voyage v = mustVoyage(voyageId);
        int occReleased = 0;
        for (PlanDao.PlanRow p : planDao.listPlansByVoyage(voyageId)) {
            if ("STAGED".equals(p.status) || "CONFIRMED".equals(p.status)) {
                occReleased += planDao.releaseOccupations(p.id, "VOYAGE_WITHDRAWN");
                planDao.updateStatus(p.id, "CANCELLED");
            } else if ("CANDIDATE".equals(p.status)) {
                planDao.updateStatus(p.id, "CANCELLED");
            }
        }
        catalog.updateVoyageStatus(voyageId, "WITHDRAWN");
        planDao.event(voyageId, null, "VOYAGE_WITHDRAWN",
                "船代撤回授权，相关候选/暂存/确认方案全部取消，释放 "
                        + occReleased + " 条资源占用；已完成作业记录仍保留", null);
    }

    // ====================================================================
    // 详情 / 工具
    // ====================================================================

    public Cat.Voyage mustVoyage(long id) {
        return catalog.findVoyage(id).orElseThrow(() -> ApiException.notFound("船次不存在: " + id));
    }

    public PlanDao.PlanRow mustPlan(long id) {
        return planDao.findPlan(id).orElseThrow(() -> ApiException.notFound("方案不存在: " + id));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> planDetail(long planId) {
        PlanDao.PlanRow p = mustPlan(planId);
        Cat.Berth berth = catalog.findBerth(p.berthId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("planNo", p.planNo);
        m.put("batchId", p.batchId);
        m.put("voyageId", p.voyageId);
        m.put("berthId", p.berthId);
        m.put("berthCode", berth != null ? berth.code() : null);
        m.put("berthName", berth != null ? berth.name() : null);
        m.put("status", p.status);
        m.put("feasible", p.feasible);
        m.put("strategy", p.strategy);
        m.put("berthFrom", p.berthFrom);
        m.put("pilotStart", p.pilotStart);
        m.put("opsStart", p.opsStart);
        m.put("opsEnd", p.opsEnd);
        m.put("berthTo", p.berthTo);
        m.put("score", p.score);
        m.put("waitMinutes", p.waitMinutes);
        m.put("holdExpiresAt", p.holdExpiresAt);
        m.put("stagedAt", p.stagedAt);
        m.put("confirmedAt", p.confirmedAt);
        m.put("releasedAt", p.releasedAt);
        m.put("releaseReason", p.releaseReason);
        m.put("versionNo", p.versionNo);
        m.put("replanOfId", p.replanOfId);
        m.put("disruptionId", p.disruptionId);
        m.put("note", p.note);
        m.put("createdAt", p.createdAt);
        m.put("reasons", readJson(p.reasonsJson));
        m.put("assignments", readJson(p.assignmentsJson));
        m.put("impact", readJson(p.impactJson));
        m.put("segments", planDao.listSegments(planId));
        return m;
    }

    public String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public Object readJson(String s) {
        if (s == null || s.isBlank()) return List.of();
        try {
            return json.readValue(s, Object.class);
        } catch (Exception e) {
            return s;
        }
    }
}
