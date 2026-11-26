package com.port.berth.service;

import com.port.berth.domain.Cat;
import com.port.berth.repo.CatalogDao;
import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部模拟输入（潮汐修订、岸桥故障、ETA 迟到）接入后，
 * 对已确认计划做影响分析，精确定位受影响工作段。
 */
@Service
public class DisruptionService {

    private final PlanDao planDao;
    private final CatalogDao catalog;
    private final PlanningEngine engine;
    private final PlanService planService;

    public DisruptionService(PlanDao planDao, CatalogDao catalog,
                             PlanningEngine engine, PlanService planService) {
        this.planDao = planDao;
        this.catalog = catalog;
        this.engine = engine;
        this.planService = planService;
    }

    // ====================================================================
    // 潮汐修订
    // ====================================================================

    @Transactional
    public Map<String, Object> reviseTide(long slotId, double newHeight, String reason) {
        Map<String, Object> slot;
        try {
            slot = planDao.jdbc().queryForMap(
                    "SELECT id, slot_start, slot_end, height_m FROM tide_slot WHERE id=?", slotId);
        } catch (Exception e) {
            throw ApiException.notFound("潮汐时段不存在: " + slotId);
        }
        double oldHeight = ((Number) slot.get("height_m")).doubleValue();

        int n = planDao.jdbc().update("""
                UPDATE tide_slot SET height_m=?, revised=true, revision_reason=? WHERE id=?
                """, newHeight, reason, slotId);
        if (n == 0) throw ApiException.notFound("潮汐时段不存在: " + slotId);

        String summary = String.format("潮汐修订：时段 #%d 水位 %.2f→%.2f m（%s）",
                slotId, oldHeight, newHeight, reason != null ? reason : "模拟气象/径流更新");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slotId", slotId);
        payload.put("oldHeightM", oldHeight);
        payload.put("newHeightM", newHeight);
        payload.put("reason", reason);

        long did = planDao.insertDisruption("TIDE_REVISION", slotId,
                planService.writeJson(payload), summary);
        analyze(did);
        return disruptionDetail(did);
    }

    // ====================================================================
    // 岸桥故障
    // ====================================================================

    @Transactional
    public Map<String, Object> addCraneFault(Long craneId, LocalDateTime start,
                                            LocalDateTime end, String reason) {
        if (craneId == null) throw ApiException.badRequest("CRANE_REQUIRED", "必须指定故障岸桥");
        Cat.Crane crane = catalog.listCranes().stream()
                .filter(c -> c.id() == craneId).findFirst()
                .orElseThrow(() -> ApiException.notFound("岸桥不存在: " + craneId));

        KeyHolder kh = new GeneratedKeyHolder();
        planDao.jdbc().update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO equipment_fault(resource_type, resource_id, start_at, end_at, reason)
                    VALUES ('CRANE',?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, craneId);
            ps.setTimestamp(2, Timestamp.valueOf(start));
            ps.setTimestamp(3, Timestamp.valueOf(end));
            ps.setString(4, reason != null ? reason : "模拟设备故障");
            return ps;
        }, kh);
        long faultId = kh.getKeyAs(Long.class);

        String summary = String.format("岸桥故障：%s 在 %s~%s 不可用（%s）",
                crane.code(), PlanningEngine.fmt(start), PlanningEngine.fmt(end),
                reason != null ? reason : "模拟机械故障");
        Map<String, Object> payload = Map.of(
                "faultId", faultId, "craneId", craneId, "craneCode", crane.code(),
                "start", start.toString(), "end", end.toString(),
                "reason", reason != null ? reason : "");
        long did = planDao.insertDisruption("CRANE_FAULT", faultId,
                planService.writeJson(payload), summary);
        analyze(did);
        return disruptionDetail(did);
    }

    // ====================================================================
    // ETA 迟到影响
    // ====================================================================

    @Transactional
    public Map<String, Object> analyzeEtaChange(long voyageId,
                                                LocalDateTime newStart, LocalDateTime newEnd) {
        Cat.Voyage v = planService.mustVoyage(voyageId);
        PlanDao.PlanRow confirmed = planDao.listPlansByVoyage(voyageId).stream()
                .filter(p -> "CONFIRMED".equals(p.status)).findFirst().orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("newEtaStart", newStart.toString());
        payload.put("newEtaEnd", newEnd.toString());

        if (confirmed == null) {
            long did = planDao.insertDisruption("ETA_CHANGE", voyageId,
                    planService.writeJson(payload),
                    "船次 " + v.voyageNo() + " 补报 ETA，当前无确认计划，只需重新生成候选");
            return disruptionDetail(did);
        }

        payload.put("planId", confirmed.id);
        payload.put("planNo", confirmed.planNo);
        payload.put("plannedBerthFrom", confirmed.berthFrom.toString());
        long did = planDao.insertDisruption("ETA_CHANGE", voyageId,
                planService.writeJson(payload),
                "船次 " + v.voyageNo() + " ETA 变更，核对已确认方案 " + confirmed.planNo);

        if (newStart.isAfter(confirmed.berthFrom)) {
            // 新 ETA 上限晚于计划靠泊起点：引航/拖轮/靠泊/装卸段全部受影响
            List<PlanDao.SegmentRow> segs = planDao.listSegments(confirmed.id);
            for (PlanDao.SegmentRow s : segs) {
                if (List.of("PILOTAGE", "TUG_INBOUND", "BERTHING", "CARGO_OP").contains(s.segType)) {
                    String msg = String.format(
                            "ETA 迟到：新 ETA 最早 %s 晚于计划靠泊时刻 %s，【%s】需顺延或重排",
                            PlanningEngine.fmt(newStart),
                            PlanningEngine.fmt(confirmed.berthFrom), s.title);
                    planDao.insertImpact(did, confirmed.id, voyageId, s.id,
                            "BLOCKER", "ETA_LATE", msg, s.startAt, s.endAt);
                    planDao.markSegmentAffected(s.id, "ETA_LATE: " + msg);
                }
            }
            planDao.event(voyageId, confirmed.id, "ETA_LATE_IMPACT",
                    "ETA 迟到冲击已确认方案，受影响工作段已标记，等待调度重排", null);
        }
        // ETA_CHANGE 的影响项已在上面直接写入，不再走潮/设备扫描
        return disruptionDetail(did);
    }

    // ====================================================================
    // 影响分析
    // ====================================================================

    @Transactional
    public void analyze(long disruptionId) {
        Map<String, Object> d;
        try {
            d = planDao.jdbc().queryForMap("SELECT * FROM disruption WHERE id=?", disruptionId);
        } catch (Exception e) {
            throw ApiException.notFound("扰动记录不存在: " + disruptionId);
        }
        String dtype = (String) d.get("dtype");

        // 重新分析前清掉旧影响项
        planDao.jdbc().update("DELETE FROM impact_item WHERE disruption_id=?", disruptionId);

        List<PlanDao.PlanRow> confirmed = planDao.listPlansByStatus("CONFIRMED");
        int affectedPlans = 0;

        if ("TIDE_REVISION".equals(dtype)) {
            for (PlanDao.PlanRow p : confirmed) {
                Cat.Voyage v = catalog.findVoyage(p.voyageId).orElse(null);
                if (v == null) continue;
                List<PlanningEngine.UkcViolation> violations =
                        engine.findUkcViolations(p.berthId, v.ship(), p.berthFrom, p.opsEnd);
                if (violations.isEmpty()) continue;
                affectedPlans++;
                for (PlanningEngine.UkcViolation vio : violations) {
                    List<PlanDao.SegmentRow> segs = planDao.listSegments(p.id);
                    boolean matched = false;
                    for (PlanDao.SegmentRow s : segs) {
                        if (s.startAt.isBefore(vio.end()) && s.endAt.isAfter(vio.start())) {
                            matched = true;
                            String msg = "潮位修订后 " + vio.message()
                                    + "，工作段【" + s.title + "】安全净空不达标";
                            planDao.insertImpact(disruptionId, p.id, v.id(), s.id,
                                    "BLOCKER", "UKC_VIOLATION", msg,
                                    max(s.startAt, vio.start()), min(s.endAt, vio.end()));
                            planDao.markSegmentAffected(s.id, "UKC_VIOLATION: " + vio.message());
                        }
                    }
                    if (!matched) {
                        planDao.insertImpact(disruptionId, p.id, v.id(), null,
                                "BLOCKER", "UKC_VIOLATION",
                                "潮位修订后 " + vio.message(), vio.start(), vio.end());
                    }
                }
                writePlanImpactSummary(p, disruptionId);
            }
        } else if ("CRANE_FAULT".equals(dtype)) {
            Long refId = ((Number) d.get("ref_id")).longValue();
            Map<String, Object> fault;
            try {
                fault = planDao.jdbc().queryForMap(
                        "SELECT * FROM equipment_fault WHERE id=?", refId);
            } catch (Exception e) {
                return;
            }
            long craneId = ((Number) fault.get("resource_id")).longValue();
            LocalDateTime fs = ((Timestamp) fault.get("start_at")).toLocalDateTime();
            LocalDateTime fe = ((Timestamp) fault.get("end_at")).toLocalDateTime();

            List<Map<String, Object>> occs = planDao.jdbc().queryForList("""
                    SELECT * FROM resource_occupation
                    WHERE status='ACTIVE' AND resource_type='CRANE' AND resource_id=?
                      AND start_at < ? AND end_at > ?
                    """, craneId, Timestamp.valueOf(fe), Timestamp.valueOf(fs));
            for (Map<String, Object> o : occs) {
                long planId = ((Number) o.get("plan_id")).longValue();
                PlanDao.PlanRow p = planDao.findPlan(planId).orElse(null);
                if (p == null || !"CONFIRMED".equals(p.status)) continue;
                Cat.Voyage v = catalog.findVoyage(p.voyageId).orElse(null);
                if (v == null) continue;
                affectedPlans++;
                PlanDao.SegmentRow cargoSeg = planDao.listSegments(planId).stream()
                        .filter(s -> "CARGO_OP".equals(s.segType)).findFirst().orElse(null);
                String craneCode = catalog.listCranes().stream()
                        .filter(c -> c.id() == craneId).map(Cat.Crane::code).findFirst()
                        .orElse("QC-" + craneId);
                String msg = String.format("岸桥 %s 在故障窗口 %s~%s 不可用，与【装卸作业】段冲突",
                        craneCode, PlanningEngine.fmt(fs), PlanningEngine.fmt(fe));
                planDao.insertImpact(disruptionId, planId, v.id(),
                        cargoSeg != null ? cargoSeg.id : null,
                        "BLOCKER", "CRANE_FAULT", msg,
                        cargoSeg != null ? cargoSeg.startAt : fs,
                        cargoSeg != null ? cargoSeg.endAt : fe);
                if (cargoSeg != null) {
                    planDao.markSegmentAffected(cargoSeg.id, "CRANE_FAULT: " + msg);
                }
                writePlanImpactSummary(p, disruptionId);
            }
        }
        // ETA_CHANGE 的影响项已在 analyzeEtaChange 中写入

        planDao.event(null, null, "DISRUPTION_ANALYZED",
                "扰动 #" + disruptionId + " 分析完成，波及确认方案 " + affectedPlans + " 个",
                null);
    }

    private void writePlanImpactSummary(PlanDao.PlanRow p, long disruptionId) {
        Integer cnt = planDao.jdbc().queryForObject(
                "SELECT count(*) FROM impact_item WHERE disruption_id=? AND plan_id=?",
                Integer.class, disruptionId, p.id);
        planDao.jdbc().update("UPDATE berth_plan SET impact_json=? WHERE id=?",
                planService.writeJson(Map.of(
                        "disruptionId", disruptionId,
                        "impactCount", cnt == null ? 0 : cnt)),
                p.id);
    }

    public Map<String, Object> disruptionDetail(long id) {
        Map<String, Object> d = planDao.jdbc().queryForMap(
                "SELECT id, dtype, ref_id, payload_json, summary, created_at " +
                        "FROM disruption WHERE id=?", id);
        Map<String, Object> m = new LinkedHashMap<>(d);
        m.put("impacts", planDao.listImpacts(id));
        return m;
    }

    public List<Map<String, Object>> listDisruptions() {
        return planDao.listDisruptions();
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
