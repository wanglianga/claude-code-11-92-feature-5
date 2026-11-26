package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.repo.PlanDao;
import com.port.berth.service.ExecutionService;
import com.port.berth.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class PlanController {

    private final PlanService planService;
    private final ExecutionService executionService;
    private final PlanDao planDao;

    public PlanController(PlanService planService,
                          ExecutionService executionService, PlanDao planDao) {
        this.planService = planService;
        this.executionService = executionService;
        this.planDao = planDao;
    }

    public record StageRequest(Integer holdTtlSeconds, Boolean holdResources) {}
    public record CancelRequest(String reason) {}

    // ---------------- 候选 ----------------

    @PostMapping("/api/voyages/{voyageId}/candidates/generate")
    public List<Map<String, Object>> generate(@PathVariable long voyageId,
                                              HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        return planService.generateCandidates(voyageId).stream()
                .map(p -> planService.planDetail(p.id)).toList();
    }

    @GetMapping("/api/voyages/{voyageId}/plans")
    public List<Map<String, Object>> voyagePlans(@PathVariable long voyageId) {
        return planDao.listPlansByVoyage(voyageId).stream()
                .map(p -> planService.planDetail(p.id)).toList();
    }

    @GetMapping("/api/plans")
    public List<Map<String, Object>> allPlans() {
        return planDao.listPlans().stream()
                .map(p -> planService.planDetail(p.id)).toList();
    }

    @GetMapping("/api/plans/{id}")
    public Map<String, Object> plan(@PathVariable long id) {
        return planService.planDetail(id);
    }

    // ---------------- 暂存 / 确认 / 取消 ----------------

    @PostMapping("/api/plans/{id}/stage")
    public Map<String, Object> stage(@PathVariable long id,
                                    @RequestBody(required = false) StageRequest req,
                                    HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        Integer ttl = req != null ? req.holdTtlSeconds() : null;
        Boolean hold = req != null ? req.holdResources() : null;
        planService.stage(id, ttl, hold);
        return planService.planDetail(id);
    }

    @PostMapping("/api/plans/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id, HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        planService.confirm(id);
        return planService.planDetail(id);
    }

    @PostMapping("/api/plans/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id,
                                      @RequestBody(required = false) CancelRequest req,
                                      HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        planService.cancelStage(id, req != null ? req.reason() : null);
        return planService.planDetail(id);
    }

    // ---------------- 现场执行 / 不可变记录 ----------------

    @PostMapping("/api/segments/{id}/start")
    public Map<String, Object> startSegment(@PathVariable long id,
                                            @RequestBody(required = false) Map<String, String> body,
                                            HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        String operator = body != null ? body.get("operatorName") : null;
        return executionService.startSegment(id, operator);
    }

    @PostMapping("/api/segments/{id}/complete")
    public Map<String, Object> completeSegment(@PathVariable long id,
                                               @RequestBody(required = false)
                                               ExecutionService.CompleteRequest req,
                                               HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        return executionService.completeSegment(id, req);
    }

    @PostMapping("/api/segments/{id}/log")
    public Map<String, Object> logWork(@PathVariable long id,
                                       @RequestBody(required = false)
                                       ExecutionService.CompleteRequest req,
                                       HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        return executionService.logWork(id, req);
    }

    @GetMapping("/api/voyages/{voyageId}/records")
    public List<Map<String, Object>> records(@PathVariable long voyageId) {
        return planDao.listWorkRecords(voyageId);
    }

    @GetMapping("/api/events")
    public List<Map<String, Object>> events(@RequestParam(required = false) Long voyageId) {
        return planDao.listEvents(voyageId);
    }
}
