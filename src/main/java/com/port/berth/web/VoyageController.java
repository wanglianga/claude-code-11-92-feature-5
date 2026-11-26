package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Cat;
import com.port.berth.domain.Role;
import com.port.berth.repo.CatalogDao;
import com.port.berth.repo.PlanDao;
import com.port.berth.service.DisruptionService;
import com.port.berth.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/voyages")
public class VoyageController {

    private final CatalogDao catalog;
    private final PlanDao planDao;
    private final PlanService planService;
    private final DisruptionService disruptionService;

    public VoyageController(CatalogDao catalog, PlanDao planDao,
                            PlanService planService, DisruptionService disruptionService) {
        this.catalog = catalog;
        this.planDao = planDao;
        this.planService = planService;
        this.disruptionService = disruptionService;
    }

    public record VoyageRequest(
            Long shipId,
            String voyageNo,
            String agentName,
            @NotNull LocalDateTime etaStart,
            @NotNull LocalDateTime etaEnd,
            Integer workDurationMin,
            Integer craneCount,
            String remark) {}

    public record EtaRequest(@NotNull LocalDateTime etaStart,
                              @NotNull LocalDateTime etaEnd,
                              String note) {}

    @GetMapping
    public List<Cat.Voyage> list() {
        return catalog.listVoyages();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Cat.Voyage v = planService.mustVoyage(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voyage", v);
        m.put("plans", planDao.listPlansByVoyage(id).stream()
                .map(p -> planService.planDetail(p.id)).toList());
        m.put("records", planDao.listWorkRecords(id));
        m.put("events", planDao.listEvents(id));
        return m;
    }

    @PostMapping
    public Map<String, Object> submit(@RequestBody VoyageRequest req,
                                      HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        if (req.shipId() == null || catalog.findShip(req.shipId()).isEmpty()) {
            throw ApiException.badRequest("SHIP_REQUIRED", "请选择有效船舶");
        }
        if (req.etaStart() == null || req.etaEnd() == null
                || req.etaEnd().isBefore(req.etaStart())) {
            throw ApiException.badRequest("BAD_ETA", "ETA 区间不合法（结束不能早于开始）");
        }
        int duration = req.workDurationMin() != null ? req.workDurationMin() : 120;
        int cranes = req.craneCount() != null ? req.craneCount() : 2;
        if (duration <= 0) throw ApiException.badRequest("BAD_DURATION", "作业时长必须为正");
        if (cranes < 0) throw ApiException.badRequest("BAD_CRANES", "岸桥数不能为负");

        String no = (req.voyageNo() == null || req.voyageNo().isBlank())
                ? "VOY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                : req.voyageNo().trim();
        if (catalog.findVoyageByNo(no).isPresent()) {
            throw ApiException.conflict("VOYAGE_NO_EXISTS", "船次号已存在: " + no);
        }
        Cat.AppUser u = WebAuth.user(http);
        String agent = req.agentName() != null && !req.agentName().isBlank()
                ? req.agentName() : u.displayName();

        long id = catalog.createVoyage(no, req.shipId(), agent,
                req.etaStart(), req.etaEnd(), duration, cranes, req.remark());
        planDao.event(id, null, "VOYAGE_SUBMITTED",
                "船代提交船次 " + no + "，ETA " + req.etaStart() + " ~ " + req.etaEnd(), null);
        return detail(id);
    }

    @PatchMapping("/{id}/eta")
    public Map<String, Object> supplementEta(@PathVariable long id,
                                             @RequestBody EtaRequest req,
                                             HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        Cat.Voyage v = planService.mustVoyage(id);
        if ("WITHDRAWN".equals(v.status())) {
            throw ApiException.badRequest("VOYAGE_WITHDRAWN", "船次已撤回授权，不能补报 ETA");
        }
        if (req.etaEnd() == null || req.etaStart() == null
                || req.etaEnd().isBefore(req.etaStart())) {
            throw ApiException.badRequest("BAD_ETA", "ETA 区间不合法");
        }

        boolean hasConfirmed = planDao.listPlansByVoyage(id).stream()
                .anyMatch(p -> "CONFIRMED".equals(p.status));
        // 已有确认计划时保持 CONFIRMED 状态并走 ETA 迟到影响分析；否则标记 ETA_UPDATED
        catalog.updateEta(id, req.etaStart(), req.etaEnd(),
                hasConfirmed ? "CONFIRMED" : "ETA_UPDATED");
        planDao.event(id, null, "ETA_SUPPLEMENTED",
                "船代补报 ETA 为 " + req.etaStart() + " ~ " + req.etaEnd()
                        + (req.note() != null ? "（" + req.note() + "）" : ""), null);

        Map<String, Object> impact = null;
        if (hasConfirmed) {
            impact = disruptionService.analyzeEtaChange(id, req.etaStart(), req.etaEnd());
        }
        Map<String, Object> result = detail(id);
        result.put("etaImpact", impact);
        return result;
    }

    @PostMapping("/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable long id, HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        planService.withdrawVoyage(id);
        return detail(id);
    }
}
