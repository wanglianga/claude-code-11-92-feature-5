package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.ReplayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**一键回放三种顺序、并发竞速、策略对照与扰动演练*/
@RestController
@RequestMapping("/api/replays")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/orderings")
    public Map<String, Object> orderings(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runOrderings();
    }

    @PostMapping("/disruptions")
    public Map<String, Object> disruptions(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runDisruptions();
    }

    @PostMapping("/laytime")
    public Map<String, Object> laytime(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runLaytime();
    }

    @PostMapping("/full")
    public Map<String, Object> full(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("orderings", replayService.runOrderings());
        all.put("disruptions", replayService.runDisruptions());
        all.put("laytime", replayService.runLaytime());
        boolean pass = Boolean.TRUE.equals(((Map<?, ?>) all.get("orderings")).get("allPassed"))
                && Boolean.TRUE.equals(((Map<?, ?>) all.get("disruptions")).get("allPassed"))
                && Boolean.TRUE.equals(((Map<?, ?>) all.get("laytime")).get("allPassed"));
        all.put("allPassed", pass);
        return all;
    }
}
