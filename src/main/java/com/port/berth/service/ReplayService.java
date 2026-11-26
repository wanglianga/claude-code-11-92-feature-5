package com.port.berth.service;

import com.port.berth.domain.Cat;
import com.port.berth.domain.Laytime;
import com.port.berth.repo.CatalogDao;
import com.port.berth.repo.PlanDao;
import com.port.berth.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 固定样例一键回放：
 * 顺序1 A 暂存后未确认（TTL 到期释放）；顺序2 A 暂留期内确认；顺序3 B 先确认；
 * 另含并发原子锁定竞速、“暂存不占资源”策略对照，以及潮汐/岸桥故障扰动重排。
 */
@Service
public class ReplayService {

    private final SeedService seedService;
    private final CatalogDao catalog;
    private final PlanDao planDao;
    private final PlanService planService;
    private final ExecutionService executionService;
    private final DisruptionService disruptionService;
    private final ReplanService replanService;
    private final LaytimeService laytimeService;

    private static final int HOLD_TTL_SECONDS = 2;

    public ReplayService(SeedService seedService, CatalogDao catalog, PlanDao planDao,
                         PlanService planService, ExecutionService executionService,
                         DisruptionService disruptionService, ReplanService replanService,
                         LaytimeService laytimeService) {
        this.seedService = seedService;
        this.catalog = catalog;
        this.planDao = planDao;
        this.planService = planService;
        this.executionService = executionService;
        this.disruptionService = disruptionService;
        this.replanService = replanService;
        this.laytimeService = laytimeService;
    }

    private LocalDateTime keyTime() {
        return SeedService.D.withHour(20).withMinute(0);
    }

    private long voyageId(String no) {
        return catalog.findVoyageByNo(no)
                .orElseThrow(() -> new IllegalStateException("缺少样例船次 " + no)).id();
    }

    private long berthB1() {
        return catalog.listBerths().stream().filter(b -> "B1".equals(b.code()))
                .findFirst().orElseThrow().id();
    }

    /**生成候选并取回 B1 20:00 关键评估点*/
    private PlanDao.PlanRow keyPlan(String voyageNo) {
        long vid = voyageId(voyageNo);
        List<PlanDao.PlanRow> generated = planService.generateCandidates(vid);
        long b1 = berthB1();
        return generated.stream()
                .filter(p -> p.berthId == b1 && keyTime().equals(p.berthFrom))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未找到 " + voyageNo + " 在 B1 20:00 的评估点"));
    }

    private PlanDao.PlanRow bestFeasible(List<PlanDao.PlanRow> generated) {
        return generated.stream().filter(p -> p.feasible)
                .max((a, b) -> Integer.compare(a.score, b.score))
                .orElse(null);
    }

    private Map<String, Object> brief(PlanDao.PlanRow p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.id);
        m.put("planNo", p.planNo);
        m.put("status", p.status);
        m.put("feasible", p.feasible);
        m.put("strategy", p.strategy);
        m.put("berthFrom", p.berthFrom);
        m.put("opsEnd", p.opsEnd);
        m.put("waitMinutes", p.waitMinutes);
        m.put("versionNo", p.versionNo);
        m.put("holdExpiresAt", p.holdExpiresAt);
        m.put("releasedAt", p.releasedAt);
        return m;
    }

    private Map<String, Object> check(List<Map<String, Object>> checks, String name,
                                      boolean pass, Object expected, Object actual) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("pass", pass);
        c.put("expected", expected);
        c.put("actual", actual);
        checks.add(c);
        return c;
    }

    private Map<String, Object> errorBody(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        if (e instanceof ApiException ae) {
            m.put("status", ae.getStatus());
            m.put("code", ae.getCode());
            m.put("message", ae.getMessage());
            if (ae.getDetails() != null) m.put("details", ae.getDetails());
        } else {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            m.put("status", 409);
            m.put("code", root.getClass().getSimpleName());
            m.put("message", root.getMessage());
        }
        return m;
    }

    private Map<String, Object> attempt(String label, Callable<Object> action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", label);
        try {
            Object r = action.call();
            m.put("ok", true);
            if (r instanceof PlanDao.PlanRow p) m.put("result", brief(p));
            else m.put("result", r);
        } catch (Exception e) {
            m.putAll(errorBody(e));
        }
        return m;
    }

    private boolean isConflict(Map<String, Object> attemptResult) {
        return Boolean.FALSE.equals(attemptResult.get("ok"))
                && Integer.valueOf(409).equals(attemptResult.get("status"));
    }

    /**跨 Integer/Long 的数值相等判断（statementView 中分钟数为 long）*/
    private static boolean numEq(Object actual, long expected) {
        return actual instanceof Number n && n.longValue() == expected;
    }

    // ====================================================================
    // 三种顺序
    // ====================================================================

    public Map<String, Object> runOrderings() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "B1 20:00 唯一深水窗口 + 唯一合格引航 PILOT-1 + 唯一适配拖轮 TUG-1");
        report.put("ordering1_stage_no_confirm", ordering1());
        report.put("ordering2_a_confirms_within_hold", ordering2());
        report.put("ordering3_b_confirms_first", ordering3());
        report.put("race_atomic_lock", raceCheck());
        report.put("strategy_contrast_hold_false", strategyContrast());

        boolean allPassed = true;
        for (String k : List.of("ordering1_stage_no_confirm",
                "ordering2_a_confirms_within_hold", "ordering3_b_confirms_first",
                "race_atomic_lock", "strategy_contrast_hold_false")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) report.get(k);
            allPassed &= Boolean.TRUE.equals(block.get("allPassed"));
        }
        report.put("allPassed", allPassed);
        return report;
    }

    // ---------------- 顺序 1：A 暂存后未确认，TTL 到期释放，B 再确认 -------

    private Map<String, Object> ordering1() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> timeline = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        Map<String, Object> aDetail = planService.planDetail(a20.id);
        check(checks, "A 的 B1 20:00 候选可行", a20.feasible, true, a20.feasible);
        check(checks, "A 自动选到 PILOT-1/TUG-1/QC-1/QC-2",
                String.valueOf(aDetail.get("assignments")).contains("PILOT-1")
                        && String.valueOf(aDetail.get("assignments")).contains("TUG-1")
                        && String.valueOf(aDetail.get("assignments")).contains("QC-1")
                        && String.valueOf(aDetail.get("assignments")).contains("QC-2"),
                "PILOT-1,TUG-1,QC-1,QC-2",
                aDetail.get("assignments"));

        PlanDao.PlanRow aStaged = planService.stage(a20.id, HOLD_TTL_SECONDS, true);
        timeline.add(Map.of("step", "A 暂存并暂留全套资源", "at", LocalDateTime.now().toString(),
                "holdExpiresAt", String.valueOf(aStaged.holdExpiresAt)));
        check(checks, "A 暂存后状态 STAGED", "STAGED".equals(aStaged.status), "STAGED", aStaged.status);

        // B 在 A 暂留期内生成候选
        PlanDao.PlanRow b20 = keyPlan("VOY-B");
        Map<String, Object> bDetail = planService.planDetail(b20.id);
        check(checks, "B 的 B1 20:00 候选在 A 暂留期内不可行",
                !b20.feasible, false, b20.feasible);
        List<?> reasons = (List<?>) bDetail.get("reasons");
        List<String> blockerCodes = reasons.stream()
                .filter(r -> r instanceof Map<?, ?>)
                .map(r -> (String) ((Map<?, ?>) r).get("code"))
                .toList();
        check(checks, "B 的等待原因包含被 A 暂留的资源",
                blockerCodes.stream().anyMatch(c -> c != null && c.contains("HELD_TENTATIVE")),
                "至少一个 *_HELD_TENTATIVE", blockerCodes);

        Map<String, Object> bConfirmDuringHold =
                attempt("A 暂留期内 B 强行确认 B1 20:00", () -> planService.confirm(b20.id));
        check(checks, "B 在 A 暂留期内确认被 409 拒绝",
                isConflict(bConfirmDuringHold), 409,
                bConfirmDuringHold.get("status"));
        timeline.add(bConfirmDuringHold);

        // 等待 TTL 到期 + sweeper 释放
        LocalDateTime expiresAt = aStaged.holdExpiresAt;
        PlanDao.PlanRow aAfter = waitUntilStatus(a20.id, "EXPIRED", 15_000);
        LocalDateTime observedAt = LocalDateTime.now();

        List<Map<String, Object>> occs = planDao.jdbc().queryForList("""
                SELECT resource_key, status, released_at, release_reason
                FROM resource_occupation WHERE plan_id=? ORDER BY resource_key
                """, a20.id);
        LocalDateTime lastRelease = null;
        for (Map<String, Object> o : occs) {
            Timestamp ts = (Timestamp) o.get("released_at");
            if (ts != null) {
                LocalDateTime ra = ts.toLocalDateTime();
                if (lastRelease == null || ra.isAfter(lastRelease)) lastRelease = ra;
            }
        }
        long sweepDelayMs = lastRelease == null ? -1
                : Duration.between(expiresAt, lastRelease).toMillis();
        check(checks, "A 暂存方案 TTL 到期后变为 EXPIRED",
                aAfter != null && "EXPIRED".equals(aAfter.status), "EXPIRED",
                aAfter == null ? "timeout" : aAfter.status);
        check(checks, "A 的全套资源占用均已 RELEASED",
                occs.stream().allMatch(o -> "RELEASED".equals(o.get("status"))) && occs.size() >= 5,
                ">=5 条全部 RELEASED",
                occs.size() + " 条: " + occs.stream().map(o -> o.get("status")).toList());
        check(checks, "资源在 TTL 到期后 3 秒内被扫描释放",
                sweepDelayMs >= 0 && sweepDelayMs < 3000,
                "0<=delay<3000ms", sweepDelayMs + "ms");

        // A 未确认：不能产生确认方案
        long aConfirmed = countConfirmed("VOY-A");
        check(checks, "A 始终没有确认结果", aConfirmed == 0, 0, aConfirmed);

        // B 重新生成并确认
        List<PlanDao.PlanRow> bRegen = planService.generateCandidates(voyageId("VOY-B"));
        PlanDao.PlanRow b20Fresh = bRegen.stream()
                .filter(p -> p.berthId == berthB1() && keyTime().equals(p.berthFrom))
                .findFirst().orElseThrow();
        check(checks, "释放后 B1 20:00 对 B 重新可行", b20Fresh.feasible, true, b20Fresh.feasible);
        PlanDao.PlanRow bConfirmed = planService.confirm(b20Fresh.id);
        check(checks, "B 成为唯一确认结果",
                "CONFIRMED".equals(bConfirmed.status) && countConfirmed("VOY-B") == 1,
                "B CONFIRMED ×1", bConfirmed.status + ", count=" + countConfirmed("VOY-B"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "顺序1：A 暂存后未确认 → TTL 到期释放 → B 确认");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("aStaged", brief(aStaged));
        out.put("aAfterExpiry", brief(aAfter));
        out.put("releaseTiming", Map.of(
                "holdExpiresAt", expiresAt,
                "observedAt", observedAt,
                "occupationReleasedAt", lastRelease,
                "sweepDelayMs", sweepDelayMs));
        out.put("bDuringHoldCandidate", Map.of(
                "planId", b20.id, "feasible", b20.feasible, "reasons", reasons));
        out.put("bConfirmDuringHold", bConfirmDuringHold);
        out.put("occupationsOfA", occs);
        out.put("bConfirmed", brief(bConfirmed));
        return out;
    }

    // ---------------- 顺序 2：A 在暂留期内确认 ----------------------------

    private Map<String, Object> ordering2() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        planService.stage(a20.id, 60, true);
        PlanDao.PlanRow aConfirmed = planService.confirm(a20.id);

        check(checks, "A 在暂留期内确认成功",
                "CONFIRMED".equals(aConfirmed.status), "CONFIRMED", aConfirmed.status);
        check(checks, "A 有且仅有一个确认方案",
                countConfirmed("VOY-A") == 1, 1, countConfirmed("VOY-A"));

        PlanDao.PlanRow b20 = keyPlan("VOY-B");
        Map<String, Object> bDetail = planService.planDetail(b20.id);
        List<?> reasons = (List<?>) bDetail.get("reasons");
        List<String> codes = reasons.stream().filter(r -> r instanceof Map<?, ?>)
                .map(r -> (String) ((Map<?, ?>) r).get("code")).toList();
        check(checks, "B 的 20:00 候选被 A 的 FIRM 确认占用阻挡",
                !b20.feasible && codes.stream().anyMatch(c -> c != null && c.contains("BOOKED_FIRM")),
                "*_BOOKED_FIRM", codes);

        Map<String, Object> bStageAttempt =
                attempt("B 尝试暂存同一组合", () -> planService.stage(b20.id, 30, true));
        Map<String, Object> bConfirmAttempt =
                attempt("B 尝试确认同一组合", () -> planService.confirm(b20.id));
        check(checks, "B 暂存被 409 拒绝", isConflict(bStageAttempt), 409, bStageAttempt.get("status"));
        check(checks, "B 确认被 409 拒绝", isConflict(bConfirmAttempt), 409, bConfirmAttempt.get("status"));

        List<PlanDao.PlanRow> bAll = planDao.listPlansByVoyage(voyageId("VOY-B"));
        PlanDao.PlanRow alt = bAll.stream().filter(p -> p.feasible && "CANDIDATE".equals(p.status))
                .max((x, y) -> Integer.compare(x.score, y.score)).orElse(null);
        check(checks, "B 得到等潮/等资源的次日备选候选",
                alt != null && alt.waitMinutes > 120,
                "waitMinutes>120", alt == null ? null : alt.waitMinutes);

        check(checks, "B 没有任何确认方案",
                countConfirmed("VOY-B") == 0, 0, countConfirmed("VOY-B"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "顺序2：A 暂存并在暂留期内确认 → B 只能等待");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("aConfirmed", brief(aConfirmed));
        out.put("bBlockedCandidate", Map.of("planId", b20.id, "reasons", reasons));
        out.put("bStageAttempt", bStageAttempt);
        out.put("bConfirmAttempt", bConfirmAttempt);
        out.put("bEarliestAlternative", alt == null ? null : planService.planDetail(alt.id));
        return out;
    }

    // ---------------- 顺序 3：B 先确认 -----------------------------------

    private Map<String, Object> ordering3() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow b20 = keyPlan("VOY-B");
        PlanDao.PlanRow bConfirmed = planService.confirm(b20.id);
        check(checks, "B 不经暂存直接原子确认成功",
                "CONFIRMED".equals(bConfirmed.status), "CONFIRMED", bConfirmed.status);

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        Map<String, Object> aDetail = planService.planDetail(a20.id);
        List<?> reasons = (List<?>) aDetail.get("reasons");
        List<String> codes = reasons.stream().filter(r -> r instanceof Map<?, ?>)
                .map(r -> (String) ((Map<?, ?>) r).get("code")).toList();
        check(checks, "A 的 20:00 候选显示被先确认的 B 阻挡",
                !a20.feasible && codes.stream().anyMatch(c -> c != null && c.contains("BOOKED_FIRM")),
                "*_BOOKED_FIRM", codes);

        Map<String, Object> aStage =
                attempt("A 尝试暂存", () -> planService.stage(a20.id, 30, true));
        Map<String, Object> aConfirm =
                attempt("A 尝试确认已讨论的候选", () -> planService.confirm(a20.id));
        check(checks, "A 暂存被 409 拒绝", isConflict(aStage), 409, aStage.get("status"));
        check(checks, "A 最后一步确认被 409 拒绝（方案失效）",
                isConflict(aConfirm), 409, aConfirm.get("status"));
        check(checks, "B 保持唯一确认结果、A 无确认",
                countConfirmed("VOY-B") == 1 && countConfirmed("VOY-A") == 0,
                "B=1,A=0",
                Map.of("B", countConfirmed("VOY-B"), "A", countConfirmed("VOY-A")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "顺序3：B 先确认 → A 讨论的方案在最后一步失效");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("bConfirmed", brief(bConfirmed));
        out.put("aBlockedCandidate", Map.of("planId", a20.id, "reasons", reasons));
        out.put("aStageAttempt", aStage);
        out.put("aConfirmAttempt", aConfirm);
        return out;
    }

    // ---------------- 并发竞速：两个确认同时到达，恰好一个成功 -------------

    private Map<String, Object> raceCheck() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        PlanDao.PlanRow b20 = keyPlan("VOY-B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (var pair : List.of(
                Map.entry("VOY-A", a20.id), Map.entry("VOY-B", b20.id))) {
            final long pid = pair.getValue();
            final String no = pair.getKey();
            futures.add(pool.submit(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    PlanDao.PlanRow p = planService.confirm(pid);
                    return Map.<String, Object>of("voyage", no, "ok", true,
                            "status", p.status,
                            "elapsedMs", System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    Map<String, Object> eb = errorBody(e);
                    eb.put("voyage", no);
                    eb.put("elapsedMs", System.currentTimeMillis() - t0);
                    return eb;
                }
            }));
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        int wins = 0;
        for (Future<Map<String, Object>> f : futures) {
            try {
                Map<String, Object> o = f.get();
                outcomes.add(o);
                if (Boolean.TRUE.equals(o.get("ok"))) wins++;
            } catch (Exception e) {
                outcomes.add(errorBody(e));
            }
        }
        pool.shutdown();

        Integer confirmedBoth = planDao.jdbc().queryForObject("""
                SELECT count(*) FROM berth_plan
                WHERE status='CONFIRMED' AND voyage_id IN (?,?)
                """, Integer.class, voyageId("VOY-A"), voyageId("VOY-B"));

        check(checks, "并发确认恰好一个成功", wins == 1, 1, wins);
        check(checks, "A/B 合计只有 1 个 CONFIRMED 方案",
                confirmedBoth != null && confirmedBoth == 1, 1, confirmedBoth);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "并发原子锁定竞速（DB 触发器 + 资源锁行兜底）");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("outcomes", outcomes);
        out.put("confirmedAcrossAB", confirmedBoth);
        return out;
    }

    // ---------------- 设计对照：暂存不占资源，确认时才原子锁定 --------------

    private Map<String, Object> strategyContrast() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        PlanDao.PlanRow aSaved = planService.stage(a20.id, null, false);
        Integer aHolds = planDao.jdbc().queryForObject(
                "SELECT count(*) FROM resource_occupation WHERE plan_id=? AND status='ACTIVE'",
                Integer.class, a20.id);
        check(checks, "holdResources=false 时 A 暂存但不产生资源占用",
                "ATOMIC_CONFIRM".equals(aSaved.strategy) && aHolds != null && aHolds == 0,
                "strategy=ATOMIC_CONFIRM, activeOcc=0",
                "strategy=" + aSaved.strategy + ", activeOcc=" + aHolds);

        PlanDao.PlanRow b20 = keyPlan("VOY-B");
        PlanDao.PlanRow bConfirmed = planService.confirm(b20.id);
        check(checks, "B 先确认并原子锁定成功",
                "CONFIRMED".equals(bConfirmed.status), "CONFIRMED", bConfirmed.status);

        Map<String, Object> aConfirm =
                attempt("A 事后确认此前讨论的候选（最后一步失效）",
                        () -> planService.confirm(a20.id));
        check(checks, "A 的最终确认被 409 拒绝", isConflict(aConfirm), 409,
                aConfirm.get("status"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "核心设计对照：只展示候选 vs 暂存即占全套资源");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("aSavedWithoutHold", brief(aSaved));
        out.put("bConfirmed", brief(bConfirmed));
        out.put("aConfirmAttempt", aConfirm);
        out.put("tradeoff", Map.of(
                "TENTATIVE_HOLD", "调度暂存即暂留泊位/引航/拖轮/岸桥，确认后不会被抢；代价是 ETA 尚宽时资源空等，需 TTL 兜底释放",
                "ATOMIC_CONFIRM", "候选阶段不占资源、周转率高；代价是已讨论方案可能在确认最后一步因资源被锁而失效，必须原子重校+锁定"));
        return out;
    }

    // ====================================================================
    // 扰动：潮汐修订 + 岸桥故障
    // ====================================================================

    public Map<String, Object> runDisruptions() {
        Map<String, Object> tide = tideDisruption();
        Map<String, Object> crane = craneFaultDisruption();
        Map<String, Object> berthChange = berthChangeOrder();
        Map<String, Object> etaLate = etaLateOrder();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tide_revision", tide);
        report.put("crane_fault", crane);
        report.put("berth_change_order", berthChange);
        report.put("eta_late_order", etaLate);
        report.put("allPassed",
                Boolean.TRUE.equals(tide.get("allPassed"))
                        && Boolean.TRUE.equals(crane.get("allPassed"))
                        && Boolean.TRUE.equals(berthChange.get("allPassed"))
                        && Boolean.TRUE.equals(etaLate.get("allPassed")));
        return report;
    }

    private Long tideSlotId(LocalDateTime start) {
        return planDao.jdbc().queryForObject(
                "SELECT id FROM tide_slot WHERE slot_start=?",
                Long.class, Timestamp.valueOf(start));
    }

    private Map<String, Object> tideDisruption() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        planService.stage(a20.id, 60, true);
        PlanDao.PlanRow confirmed = planService.confirm(a20.id);

        // 已完成的引航记录
        PlanDao.SegmentRow pilotSeg = planDao.listSegments(confirmed.id).stream()
                .filter(s -> "PILOTAGE".equals(s.segType)).findFirst().orElseThrow();
        executionService.completeSegment(pilotSeg.id,
                new ExecutionService.CompleteRequest("孙引航", 55, 0,
                        "引航作业正常完成", null));

        // 部分完成的装卸台账（15 分钟），随后扰动打断
        PlanDao.SegmentRow cargoSeg = planDao.listSegments(confirmed.id).stream()
                .filter(s -> "CARGO_OP".equals(s.segType)).findFirst().orElseThrow();
        executionService.logWork(cargoSeg.id,
                new ExecutionService.CompleteRequest("吴岸桥班", 15, 18,
                        "前 15 分钟作业正常", null));

        List<Map<String, Object>> recordsBefore = planDao.listWorkRecords(voyageId("VOY-A"));
        check(checks, "扰动前已有 2 条不可变作业记录（引航+装卸台账）",
                recordsBefore.size() == 2, 2, recordsBefore.size());

        // 修订 21:00-21:30 潮位 3.2 -> 2.2
        Long slotId = tideSlotId(SeedService.D.withHour(21).withMinute(0));
        Map<String, Object> disruption =
                disruptionService.reviseTide(slotId, 2.2, "模拟风暴潮退水提前，安全潮窗缩短");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impacts = (List<Map<String, Object>>) disruption.get("impacts");
        boolean cargoImpacted = impacts.stream().anyMatch(i ->
                "UKC_VIOLATION".equals(i.get("code"))
                        && String.valueOf(i.get("message")).contains("装卸作业"));
        check(checks, "影响分析定位到装卸作业段 UKC 不达标",
                impacts.size() >= 1 && cargoImpacted,
                "存在指向 CARGO_OP 的 UKC_VIOLATION",
                impacts.stream().map(i -> i.get("code") + ":" + i.get("message")).toList());

        // 重排并自动确认
        Map<String, Object> replan = replanService.replan(confirmed.id, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> newPlan = (Map<String, Object>) replan.get("newPlan");
        check(checks, "潮汐重排模式为 RESCHEDULE 且新版本已确认",
                "RESCHEDULE".equals(replan.get("mode"))
                        && "CONFIRMED".equals(newPlan.get("status")),
                "RESCHEDULE + CONFIRMED",
                replan.get("mode") + " / " + newPlan.get("status"));
        LocalDateTime newFrom = (LocalDateTime) newPlan.get("berthFrom");
        check(checks, "重排到次日早潮窗口 08:00",
                newFrom != null && newFrom.toLocalDate().equals(SeedService.D.plusDays(1).toLocalDate())
                        && newFrom.getHour() == 8,
                SeedService.D.plusDays(1).toLocalDate() + "T08:00", newFrom);
        check(checks, "重排只安排剩余 45 分钟作业",
                Integer.valueOf(45).equals(replan.get("remainingMinutes")),
                45, replan.get("remainingMinutes"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preserved =
                (List<Map<String, Object>>) replan.get("preservedRecords");
        check(checks, "重排结果原样保留全部已完成记录",
                preserved.size() == 2, 2, preserved.size());

        // DB 触发器层面证明记录不可改不可删
        Long recordId = ((Number) recordsBefore.get(0).get("id")).longValue();
        Map<String, Object> updateProof = proveImmutableUpdate(recordId);
        Map<String, Object> deleteProof = proveImmutableDelete(recordId);
        check(checks, "数据库阻止 UPDATE 已完成记录",
                Boolean.TRUE.equals(updateProof.get("blocked")), true, updateProof);
        check(checks, "数据库阻止 DELETE 已完成记录",
                Boolean.TRUE.equals(deleteProof.get("blocked")), true, deleteProof);

        List<Map<String, Object>> recordsAfter = planDao.listWorkRecords(voyageId("VOY-A"));
        check(checks, "对抗性修改后记录数量与内容未变",
                recordsAfter.size() == 2
                        && recordsAfter.get(0).get("result_note")
                        .equals(recordsBefore.get(0).get("result_note")),
                "2 条且首条备注不变", recordsAfter.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "扰动A：水位修订插入已确认计划 → 影响段与重排 + 记录固化");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("recordsBefore", recordsBefore);
        out.put("disruption", disruption);
        out.put("replan", replan);
        out.put("immutableUpdateProof", updateProof);
        out.put("immutableDeleteProof", deleteProof);
        return out;
    }

    private Map<String, Object> proveImmutableUpdate(long recordId) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            planDao.jdbc().update(
                    "UPDATE work_record SET result_note='HACKED' WHERE id=?", recordId);
            m.put("blocked", false);
        } catch (Exception e) {
            m.put("blocked", true);
            m.put("error", rootMessage(e));
        }
        return m;
    }

    private Map<String, Object> proveImmutableDelete(long recordId) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            planDao.jdbc().update("DELETE FROM work_record WHERE id=?", recordId);
            m.put("blocked", false);
        } catch (Exception e) {
            m.put("blocked", true);
            m.put("error", rootMessage(e));
        }
        return m;
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    private Map<String, Object> craneFaultDisruption() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        PlanDao.PlanRow confirmed = planService.confirm(a20.id);

        PlanDao.SegmentRow pilotSeg = planDao.listSegments(confirmed.id).stream()
                .filter(s -> "PILOTAGE".equals(s.segType)).findFirst().orElseThrow();
        executionService.completeSegment(pilotSeg.id,
                new ExecutionService.CompleteRequest("孙引航", 50, 0, "引航完成", null));

        Map<String, Object> fault = disruptionService.addCraneFault(
                2L,
                SeedService.D.withHour(20).withMinute(45),
                SeedService.D.withHour(22).withMinute(30),
                "模拟 QC-2 电控故障");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impacts = (List<Map<String, Object>>) fault.get("impacts");
        check(checks, "岸桥故障生成 CRANE_FAULT 影响项并指向装卸段",
                impacts.stream().anyMatch(i -> "CRANE_FAULT".equals(i.get("code"))),
                "CRANE_FAULT", impacts.size());

        Map<String, Object> replan = replanService.replan(confirmed.id, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> newPlan = (Map<String, Object>) replan.get("newPlan");
        String assignments = String.valueOf(newPlan.get("assignments"));
        check(checks, "岸桥故障采用同时刻换桥（SAME_SLOT_REASSIGN）",
                "SAME_SLOT_REASSIGN".equals(replan.get("mode")),
                "SAME_SLOT_REASSIGN", replan.get("mode"));
        check(checks, "新计划用 QC-3 替换故障 QC-2",
                "CONFIRMED".equals(newPlan.get("status"))
                        && assignments.contains("QC-3") && !assignments.contains("QC-2"),
                "contains QC-3, not QC-2", newPlan.get("assignments"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preserved =
                (List<Map<String, Object>>) replan.get("preservedRecords");
        check(checks, "已完成引航记录在换桥重排后保留",
                preserved.size() == 1 && "PILOTAGE".equals(preserved.get(0).get("record_type")),
                "1 条 PILOTAGE", preserved);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "扰动B：岸桥故障插入已确认计划 → 受影响段与换桥重排");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("fault", fault);
        out.put("replan", replan);
        return out;
    }

    // ---------------- 改靠指令 ----------------

    private Map<String, Object> berthChangeOrder() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        // 用对 B2/B3 都适配的滚装船 VOY-D 演示成功改靠
        long vid = voyageId("VOY-D");
        PlanDao.PlanRow dBest = bestFeasible(planService.generateCandidates(vid));
        if (dBest == null) throw new IllegalStateException("VOY-D 应当存在可行候选");
        PlanDao.PlanRow confirmed = planService.confirm(dBest.id);
        long fromBerth = confirmed.berthId;
        long b2 = catalog.listBerths().stream().filter(b -> "B2".equals(b.code())).findFirst().orElseThrow().id();
        long b3 = catalog.listBerths().stream().filter(b -> "B3".equals(b.code())).findFirst().orElseThrow().id();
        long toBerth = fromBerth == b2 ? b3 : b2;

        Map<String, Object> change =
                replanService.changeBerth(confirmed.id, toBerth, confirmed.berthFrom, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> newPlan = (Map<String, Object>) change.get("newPlan");
        @SuppressWarnings("unchecked")
        Map<String, Object> disruption = (Map<String, Object>) change.get("disruption");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impacts =
                (List<Map<String, Object>>) disruption.get("impacts");

        check(checks, "改靠后新版本已确认且泊位发生变化",
                "BERTH_CHANGE".equals(change.get("mode"))
                        && "CONFIRMED".equals(newPlan.get("status"))
                        && !Objects.equals(newPlan.get("berthId"), fromBerth),
                "new berth != " + fromBerth,
                "newBerthId=" + newPlan.get("berthId") + ", status=" + newPlan.get("status"));
        long blockerCount = impacts.stream()
                .filter(i -> "BERTH_CHANGE".equals(i.get("code"))).count();
        check(checks, "改靠逐段说明受影响工作段（>=4 段）",
                blockerCount >= 4, ">=4 BERTH_CHANGE impacts", blockerCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "变更C：调度下达改靠指令 → 受影响段说明 + 新版本确认");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("change", change);
        return out;
    }

    // ---------------- ETA 迟到 ----------------

    private Map<String, Object> etaLateOrder() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        PlanDao.PlanRow a20 = keyPlan("VOY-A");
        PlanDao.PlanRow confirmed = planService.confirm(a20.id);
        long vid = voyageId("VOY-A");

        LocalDateTime newStart = SeedService.D.withHour(21).withMinute(30);
        LocalDateTime newEnd = SeedService.D.withHour(22).withMinute(30);
        catalog.updateEta(vid, newStart, newEnd, "CONFIRMED");
        Map<String, Object> etaImpact = disruptionService.analyzeEtaChange(vid, newStart, newEnd);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impacts = (List<Map<String, Object>>) etaImpact.get("impacts");
        long lateCount = impacts.stream().filter(i -> "ETA_LATE".equals(i.get("code"))).count();
        check(checks, "ETA 迟到生成 ETA_LATE 影响并覆盖引航/拖轮/靠泊/装卸段",
                lateCount >= 4, ">=4 ETA_LATE impacts", lateCount);

        Map<String, Object> replan = replanService.replan(confirmed.id, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> newPlan = (Map<String, Object>) replan.get("newPlan");
        check(checks, "ETA 迟到后重排出新版本并确认",
                "CONFIRMED".equals(newPlan.get("status"))
                        && Integer.valueOf(2).equals(newPlan.get("versionNo")),
                "CONFIRMED v2", newPlan.get("status") + " v" + newPlan.get("versionNo"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "变更D：ETA 迟到插入已确认计划 → 受影响段 + 重排");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("etaImpact", etaImpact);
        out.put("replan", replan);
        return out;
    }

    // ====================================================================
    // 滞期争议：回执 + 合同规则 → 可质证区段与金额
    // ====================================================================

    /**
     * 固定样例 VOY-L：08:00 就绪、免计 2h、费率 1000/60 元/分钟；
     * 天气 10:00–11:00（港方确认）暂停；岸桥故障 11:00–12:30 仍计时，
     * 修复回执 13:00 迟到送达；14:00 完工 → 定稿 11:00–14:00、3000 元。
     */
    public Map<String, Object> runLaytime() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();
        long vid = voyageId("VOY-L");
        LocalDateTime d = SeedService.D;

        // ---- 合同条款 ----
        Map<String, Object> contract = laytimeService.contract(vid);
        check(checks, "合同：免计 120 分钟、费率 1000 元/小时（每分钟 1000/60 元）",
                Integer.valueOf(120).equals(contract.get("freeMinutes"))
                        && "1000.00".equals(String.valueOf(contract.get("ratePerHour"))),
                "free=120, rate=1000.00",
                "free=" + contract.get("freeMinutes") + ", rate=" + contract.get("ratePerHour"));

        // ---- 回放 12:00：只能显示暂计额 ----
        Map<String, Object> at12 = laytimeService.statementView(
                vid, d.withHour(12), LaytimeService.MODE_STREAMING);
        check(checks, "回放 12:00 为暂计（PROVISIONAL），不是定稿",
                Laytime.PROVISIONAL.equals(at12.get("kind")), "PROVISIONAL", at12.get("kind"));
        check(checks, "回放 12:00 暂计 60 分钟 / 1000.00 元（11:00–12:00 待证计时）",
                numEq(at12.get("billableMinutes"), 60)
                        && "1000.00".equals(String.valueOf(at12.get("amount"))),
                "60min / 1000.00",
                at12.get("billableMinutes") + "min / " + at12.get("amount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seg12 = (List<Map<String, Object>>) at12.get("segments");
        LocalDateTime maxEnd12 = seg12.stream()
                .map(s -> LocalDateTime.parse(String.valueOf(s.get("end"))))
                .max(LocalDateTime::compareTo).orElse(null);
        check(checks, "回放 12:00 不把尚未发生的时长算入（区段不越过 12:00）",
                maxEnd12 != null && !maxEnd12.isAfter(d.withHour(12)),
                "maxEnd<=12:00", maxEnd12);
        check(checks, "回放 12:00 时 11:00 起区段为停工待证（PENDING_EVIDENCE）",
                seg12.stream().anyMatch(s -> "PENDING_EVIDENCE".equals(s.get("segClass"))
                        && String.valueOf(s.get("start")).endsWith("11:00")),
                "PENDING_EVIDENCE 11:00–12:00",
                seg12.stream().map(s -> s.get("segClass") + " " + s.get("start") + "→" + s.get("end")).toList());

        // ---- 回放 13:00：迟到修复回执已更正 11:00–12:30 区段 ----
        Map<String, Object> at13 = laytimeService.statementView(
                vid, d.withHour(13), LaytimeService.MODE_STREAMING);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seg13 = (List<Map<String, Object>>) at13.get("segments");
        check(checks, "回放 13:00 时 11:00–12:30 已更正为岸桥故障（CRANE_FAULT，仍计时）",
                seg13.stream().anyMatch(s -> "CRANE_FAULT".equals(s.get("segClass"))
                        && String.valueOf(s.get("start")).endsWith("11:00")
                        && String.valueOf(s.get("end")).endsWith("12:30")),
                "CRANE_FAULT 11:00–12:30",
                seg13.stream().map(s -> s.get("segClass") + " " + s.get("start") + "→" + s.get("end")).toList());
        check(checks, "回放 13:00 暂计 120 分钟 / 2000.00 元",
                numEq(at13.get("billableMinutes"), 120)
                        && "2000.00".equals(String.valueOf(at13.get("amount"))),
                "120min / 2000.00",
                at13.get("billableMinutes") + "min / " + at13.get("amount"));

        // ---- 定稿：可计时区间 11:00–14:00，应收 3000 元 ----
        Map<String, Object> fin = laytimeService.statementView(vid, null, LaytimeService.MODE_STREAMING);
        check(checks, "完工回执到达后账单为定稿（FINAL）",
                Laytime.FINAL.equals(fin.get("kind")), "FINAL", fin.get("kind"));
        check(checks, "定稿可计时 180 分钟 / 应收 3000.00 元",
                numEq(fin.get("billableMinutes"), 180)
                        && "3000.00".equals(String.valueOf(fin.get("amount"))),
                "180min / 3000.00",
                fin.get("billableMinutes") + "min / " + fin.get("amount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segFin = (List<Map<String, Object>>) fin.get("segments");
        List<Map<String, Object>> billableSegs = segFin.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("billable"))).toList();
        LocalDateTime billStart = billableSegs.stream()
                .map(s -> LocalDateTime.parse(String.valueOf(s.get("start"))))
                .min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime billEnd = billableSegs.stream()
                .map(s -> LocalDateTime.parse(String.valueOf(s.get("end"))))
                .max(LocalDateTime::compareTo).orElse(null);
        boolean contiguous = true;
        for (int i = 0; i + 1 < billableSegs.size(); i++) {
            contiguous &= String.valueOf(billableSegs.get(i).get("end"))
                    .equals(String.valueOf(billableSegs.get(i + 1).get("start")));
        }
        check(checks, "定稿可计时区间为连续的 11:00–14:00",
                contiguous && billStart != null && billEnd != null
                        && billStart.equals(d.withHour(11)) && billEnd.equals(d.withHour(14)),
                "11:00–14:00 连续", billStart + " → " + billEnd);

        // ---- 被更正区段留痕 ----
        List<Map<String, Object>> corrections = laytimeService.corrections(vid);
        boolean headline = corrections.stream().anyMatch(c ->
                "PENDING_EVIDENCE".equals(c.get("from_class"))
                        && "CRANE_FAULT".equals(c.get("to_class"))
                        && String.valueOf(c.get("seg_start")).endsWith("11:00")
                        && String.valueOf(c.get("seg_end")).endsWith("12:30")
                        && "PORT-QC-REP-207".equals(c.get("evidence_ref")));
        check(checks, "被更正区段留痕：11:00–12:30 由停工待证更正为岸桥故障（回执 PORT-QC-REP-207）",
                headline, "存在 PENDING_EVIDENCE→CRANE_FAULT 11:00–12:30",
                corrections.stream().map(c -> c.get("from_class") + "→" + c.get("to_class")
                        + " " + c.get("seg_start") + "–" + c.get("seg_end")).toList());

        // ---- 双方证据与船方主张处置 ----
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claims = (List<Map<String, Object>>) fin.get("claims");
        check(checks, "船方“天气延续”主张被港方修复回执否定（REFUTED）",
                claims.stream().anyMatch(c -> "REFUTED".equals(c.get("status"))),
                "存在 REFUTED 主张", claims);
        Map<String, Object> craneSeg = segFin.stream()
                .filter(s -> "CRANE_FAULT".equals(s.get("segClass"))).findFirst().orElse(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> craneEvidence =
                (List<Map<String, Object>>) craneSeg.getOrDefault("evidence", List.of());
        check(checks, "被更正区段附双方证据（船方停工报告 + 港方修复回执）",
                craneEvidence.stream().anyMatch(e -> "SHIP".equals(e.get("party")))
                        && craneEvidence.stream().anyMatch(e -> "PORT".equals(e.get("party"))),
                "SHIP+PORT 证据齐全",
                craneEvidence.stream().map(e -> e.get("party") + ":" + e.get("evidenceRef")).toList());

        // ---- 计划停工不得替代实际停工凭证 ----
        List<Map<String, Object>> events = laytimeService.events(vid);
        long plannedId = events.stream()
                .filter(e -> "PLANNED_STOP".equals(e.get("eventType")))
                .mapToLong(e -> ((Number) e.get("id")).longValue()).findFirst().orElse(-1);
        boolean plannedUsed = segFin.stream().flatMap(s -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ev = (List<Map<String, Object>>) s.getOrDefault("evidence", List.of());
            return ev.stream();
        }).anyMatch(e -> ((Number) e.get("id")).longValue() == plannedId);
        check(checks, "计划保养预告 10:30–11:30 未被用作实际停工凭证（区段分类只认实际回执）",
                plannedId > 0 && !plannedUsed,
                "PLANNED_STOP 存在且不出现在任何区段证据中",
                "plannedEventId=" + plannedId + ", usedAsEvidence=" + plannedUsed);

        // ---- 两种结算口径对照 ----
        Map<String, Object> ac12 = laytimeService.statementView(
                vid, d.withHour(12), LaytimeService.MODE_AT_COMPLETION);
        check(checks, "完工结算口径在 12:00 不出具金额（争议期间没有最终数）",
                Boolean.FALSE.equals(ac12.get("settled")) && ac12.get("amount") == null,
                "settled=false, amount=null",
                "settled=" + ac12.get("settled") + ", amount=" + ac12.get("amount"));
        Map<String, Object> acFin = laytimeService.statementView(
                vid, null, LaytimeService.MODE_AT_COMPLETION);
        check(checks, "完工结算口径在完工后给出同一定稿金额 3000.00 元",
                "3000.00".equals(String.valueOf(acFin.get("amount"))),
                "3000.00", acFin.get("amount"));

        // ---- 迟到回执标记 ----
        Map<String, Object> repair = events.stream()
                .filter(e -> "REPAIR_RECEIPT".equals(e.get("eventType"))).findFirst().orElse(Map.of());
        check(checks, "修复回执标记迟到 30 分钟（12:30 修复完成，13:00 送达）",
                numEq(repair.get("lateByMinutes"), 30),
                30, repair.get("lateByMinutes"));

        // ---- 回执不可变（DB 触发器自证） ----
        long anyEventId = ((Number) events.get(0).get("id")).longValue();
        Map<String, Object> updProof = proveLaytimeImmutable(anyEventId, true);
        Map<String, Object> delProof = proveLaytimeImmutable(anyEventId, false);
        check(checks, "数据库阻止 UPDATE 已入账回执",
                Boolean.TRUE.equals(updProof.get("blocked")), true, updProof);
        check(checks, "数据库阻止 DELETE 已入账回执",
                Boolean.TRUE.equals(delProof.get("blocked")), true, delProof);

        // ---- 版本链：边收事件边累计 ----
        List<Map<String, Object>> versions = laytimeService.statements(vid);
        check(checks, "每条回执生成一个账单版本（8 条回执 → 8 版），暂计额随事件累计",
                versions.size() == 8, 8, versions.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "滞期争议：双方回执 + 合同规则 → 可质证区段与金额（定稿 11:00–14:00 / 3000 元）");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("contract", contract);
        out.put("replay12", at12);
        out.put("replay13", at13);
        out.put("finalStatement", fin);
        out.put("corrections", corrections);
        out.put("versionTimeline", versions.stream().map(v -> Map.of(
                "version", v.get("version_no"), "kind", v.get("kind"),
                "asOf", v.get("as_of"), "billableMinutes", v.get("billable_minutes"),
                "amount", v.get("amount"))).toList());
        out.put("settlementContrast", Map.of(
                "streamingAt12", Map.of("billableMinutes", at12.get("billableMinutes"),
                        "amount", at12.get("amount")),
                "atCompletionAt12", Map.of("settled", ac12.get("settled"),
                        "amount", ac12.get("amount") == null ? "null" : ac12.get("amount")),
                "note", "边收边累计让船代及时看到暂计额，但迟到回执会更正区段；完工再结算更稳定，但争议期间没有最终数"));
        return out;
    }

    private Map<String, Object> proveLaytimeImmutable(long eventId, boolean update) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            if (update) {
                planDao.jdbc().update(
                        "UPDATE laytime_event SET note='HACKED' WHERE id=?", eventId);
            } else {
                planDao.jdbc().update("DELETE FROM laytime_event WHERE id=?", eventId);
            }
            m.put("blocked", false);
        } catch (Exception e) {
            m.put("blocked", true);
            m.put("error", rootMessage(e));
        }
        return m;
    }

    // ====================================================================

    private PlanDao.PlanRow waitUntilStatus(long planId, String status, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            PlanDao.PlanRow p = planDao.findPlan(planId).orElse(null);
            if (p != null && status.equals(p.status)) return p;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return planDao.findPlan(planId).orElse(null);
    }

    private long countConfirmed(String voyageNo) {
        return planDao.listPlansByVoyage(voyageId(voyageNo)).stream()
                .filter(p -> "CONFIRMED".equals(p.status)).count();
    }
}
