package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Cat;
import com.port.berth.domain.Role;
import com.port.berth.service.LaytimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 滞期争议：双方回执、按时刻回放的暂计/定稿账单、被更正区段、定稿。
 * 提交方由登录角色决定：船代 → SHIP，调度/班组/管理员 → PORT。
 */
@RestController
@RequestMapping("/api/voyages/{voyageId}/laytime")
public class LaytimeController {

    private final LaytimeService laytimeService;

    public LaytimeController(LaytimeService laytimeService) {
        this.laytimeService = laytimeService;
    }

    public record ReceiptRequest(
            @NotNull String eventType,
            @NotNull LocalDateTime occurredAt,
            LocalDateTime receivedAt,     // 缺省 = occurredAt；> occurredAt 即迟到回执
            String claimCause,            // STOP_START 时船方主张的原因
            LocalDateTime windowEnd,      // REPAIR_RECEIPT / PLANNED_STOP 的窗口结束
            String evidenceRef,
            String note) {}

    @GetMapping("/contract")
    public Map<String, Object> contract(@PathVariable long voyageId) {
        return laytimeService.contract(voyageId);
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@PathVariable long voyageId) {
        return laytimeService.events(voyageId);
    }

    @PostMapping("/events")
    public Map<String, Object> submitReceipt(@PathVariable long voyageId,
                                             @RequestBody ReceiptRequest req,
                                             HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER, Role.TEAM, Role.ADMIN);
        Cat.AppUser u = WebAuth.user(http);
        String party = Role.AGENT.name().equals(u.role()) ? "SHIP" : "PORT";
        String note = req.note() != null ? req.note()
                : "由 " + u.displayName() + " 登记";
        return laytimeService.ingest(voyageId, req.eventType(), req.occurredAt(),
                req.receivedAt(), party, req.claimCause(), req.windowEnd(),
                req.evidenceRef(), note);
    }

    /**任一时刻账单回放：asOf 缺省为最新；mode=STREAMING|AT_COMPLETION*/
    @GetMapping
    public Map<String, Object> statement(
            @PathVariable long voyageId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOf,
            @RequestParam(defaultValue = "STREAMING") String mode) {
        return laytimeService.statementView(voyageId, asOf, mode);
    }

    @GetMapping("/statements")
    public List<Map<String, Object>> statements(@PathVariable long voyageId) {
        return laytimeService.statements(voyageId);
    }

    @GetMapping("/corrections")
    public List<Map<String, Object>> corrections(@PathVariable long voyageId) {
        return laytimeService.corrections(voyageId);
    }

    @PostMapping("/finalize")
    public Map<String, Object> finalize(@PathVariable long voyageId, HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return laytimeService.finalize(voyageId);
    }
}
