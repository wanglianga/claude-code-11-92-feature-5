package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.DisruptionService;
import com.port.berth.service.ReplanService;
import com.port.berth.service.SeedService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SimulationController {

    private final DisruptionService disruptionService;
    private final ReplanService replanService;
    private final SeedService seedService;

    public SimulationController(DisruptionService disruptionService,
                                ReplanService replanService, SeedService seedService) {
        this.disruptionService = disruptionService;
        this.replanService = replanService;
        this.seedService = seedService;
    }

    public record TideReviseRequest(Double heightM, String reason) {}
    public record CraneFaultRequest(Long craneId, LocalDateTime startAt,
                                    LocalDateTime endAt, String reason) {}
    public record ReplanRequest(Boolean autoConfirm) {}
    public record ChangeBerthRequest(Long newBerthId, LocalDateTime newBerthFrom,
                                     Boolean autoConfirm) {}

    @PutMapping("/simulation/tides/{slotId}")
    public Map<String, Object> reviseTide(@PathVariable long slotId,
                                          @RequestBody TideReviseRequest req,
                                          HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        if (req == null || req.heightM() == null) {
            throw ApiException.badRequest("HEIGHT_REQUIRED", "请提供新的水位 heightM");
        }
        return disruptionService.reviseTide(slotId, req.heightM(), req.reason());
    }

    @PostMapping("/simulation/crane-faults")
    public Map<String, Object> craneFault(@RequestBody CraneFaultRequest req,
                                         HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        if (req == null || req.craneId() == null
                || req.startAt() == null || req.endAt() == null) {
            throw ApiException.badRequest("BAD_FAULT", "请提供 craneId/startAt/endAt");
        }
        return disruptionService.addCraneFault(
                req.craneId(), req.startAt(), req.endAt(), req.reason());
    }

    @GetMapping("/disruptions")
    public List<Map<String, Object>> disruptions() {
        return disruptionService.listDisruptions();
    }

    @GetMapping("/disruptions/{id}")
    public Map<String, Object> disruption(@PathVariable long id) {
        return disruptionService.disruptionDetail(id);
    }

    @PostMapping("/plans/{id}/replan")
    public Map<String, Object> replan(@PathVariable long id,
                                      @RequestBody(required = false) ReplanRequest req,
                                      HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        boolean auto = req == null || req.autoConfirm() == null || req.autoConfirm();
        return replanService.replan(id, auto);
    }

    @PostMapping("/plans/{id}/change-berth")
    public Map<String, Object> changeBerth(@PathVariable long id,
                                           @RequestBody(required = false) ChangeBerthRequest req,
                                           HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        Long berthId = req == null ? null : req.newBerthId();
        LocalDateTime from = req == null ? null : req.newBerthFrom();
        boolean auto = req == null || req.autoConfirm() == null || req.autoConfirm();
        return replanService.changeBerth(id, berthId, from, auto);
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        seedService.resetAndSeed();
        return Map.of("ok", true, "message", "场景已重置为固定样例数据");
    }
}
