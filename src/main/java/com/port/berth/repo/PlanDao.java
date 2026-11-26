package com.port.berth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**方案、分段、资源占用、事件、作业记录、扰动的数据访问*/
@Repository
public class PlanDao {

    private final JdbcTemplate jdbc;

    public PlanDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ================= 方案行 =================

    public static class PlanRow {
        public long id;
        public String planNo;
        public String batchId;
        public long voyageId;
        public long berthId;
        public String status;
        public boolean feasible;
        public String strategy;
        public LocalDateTime berthFrom, pilotStart, opsStart, opsEnd, berthTo;
        public int score, waitMinutes;
        public String reasonsJson, assignmentsJson;
        public LocalDateTime holdExpiresAt, stagedAt, confirmedAt, releasedAt;
        public String releaseReason;
        public int versionNo;
        public Long replanOfId, disruptionId;
        public String impactJson, note;
        public LocalDateTime createdAt;
    }

    private static final String PLAN_COLS = """
            id, plan_no, batch_id, voyage_id, berth_id, status, feasible, strategy,
            berth_from, pilot_start, ops_start, ops_end, berth_to, score, wait_minutes,
            reasons_json, assignments_json, hold_expires_at, staged_at, confirmed_at,
            released_at, release_reason, version_no, replan_of_id, disruption_id,
            impact_json, note, created_at
            """;

    private final org.springframework.jdbc.core.RowMapper<PlanRow> planMapper = (rs, n) -> {
        PlanRow p = new PlanRow();
        int i = 1;
        p.id = rs.getLong(i++);
        p.planNo = rs.getString(i++);
        p.batchId = rs.getString(i++);
        p.voyageId = rs.getLong(i++);
        p.berthId = rs.getLong(i++);
        p.status = rs.getString(i++);
        p.feasible = rs.getBoolean(i++);
        p.strategy = rs.getString(i++);
        p.berthFrom = rs.getObject(i++, LocalDateTime.class);
        p.pilotStart = rs.getObject(i++, LocalDateTime.class);
        p.opsStart = rs.getObject(i++, LocalDateTime.class);
        p.opsEnd = rs.getObject(i++, LocalDateTime.class);
        p.berthTo = rs.getObject(i++, LocalDateTime.class);
        p.score = rs.getInt(i++);
        p.waitMinutes = rs.getInt(i++);
        p.reasonsJson = rs.getString(i++);
        p.assignmentsJson = rs.getString(i++);
        p.holdExpiresAt = rs.getObject(i++, LocalDateTime.class);
        p.stagedAt = rs.getObject(i++, LocalDateTime.class);
        p.confirmedAt = rs.getObject(i++, LocalDateTime.class);
        p.releasedAt = rs.getObject(i++, LocalDateTime.class);
        p.releaseReason = rs.getString(i++);
        p.versionNo = rs.getInt(i++);
        long rp = rs.getLong(i++);
        p.replanOfId = rs.wasNull() ? null : rp;
        long dp = rs.getLong(i++);
        p.disruptionId = rs.wasNull() ? null : dp;
        p.impactJson = rs.getString(i++);
        p.note = rs.getString(i++);
        p.createdAt = rs.getObject(i++, LocalDateTime.class);
        return p;
    };

    public long insertPlan(PlanRow p) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO berth_plan(
                      plan_no, batch_id, voyage_id, berth_id, status, feasible, strategy,
                      berth_from, pilot_start, ops_start, ops_end, berth_to, score, wait_minutes,
                      reasons_json, assignments_json, hold_expires_at, staged_at, confirmed_at,
                      version_no, replan_of_id, disruption_id, impact_json, note)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            int i = 1;
            ps.setString(i++, p.planNo);
            ps.setString(i++, p.batchId);
            ps.setLong(i++, p.voyageId);
            ps.setLong(i++, p.berthId);
            ps.setString(i++, p.status);
            ps.setBoolean(i++, p.feasible);
            ps.setString(i++, p.strategy);
            setTs(ps, i++, p.berthFrom);
            setTs(ps, i++, p.pilotStart);
            setTs(ps, i++, p.opsStart);
            setTs(ps, i++, p.opsEnd);
            setTs(ps, i++, p.berthTo);
            ps.setInt(i++, p.score);
            ps.setInt(i++, p.waitMinutes);
            ps.setString(i++, p.reasonsJson);
            ps.setString(i++, p.assignmentsJson);
            setTs(ps, i++, p.holdExpiresAt);
            setTs(ps, i++, p.stagedAt);
            setTs(ps, i++, p.confirmedAt);
            ps.setInt(i++, p.versionNo);
            if (p.replanOfId == null) ps.setNull(i++, java.sql.Types.BIGINT); else ps.setLong(i++, p.replanOfId);
            if (p.disruptionId == null) ps.setNull(i++, java.sql.Types.BIGINT); else ps.setLong(i++, p.disruptionId);
            ps.setString(i++, p.impactJson);
            ps.setString(i++, p.note);
            return ps;
        }, kh);
        p.id = kh.getKeyAs(Long.class);
        return p.id;
    }

    private static void setTs(PreparedStatement ps, int idx, LocalDateTime t) throws java.sql.SQLException {
        if (t == null) ps.setNull(idx, java.sql.Types.TIMESTAMP);
        else ps.setTimestamp(idx, Timestamp.valueOf(t));
    }

    public Optional<PlanRow> findPlan(long id) {
        return jdbc.query("SELECT " + PLAN_COLS + " FROM berth_plan WHERE id=?", planMapper, id)
                .stream().findFirst();
    }

    public Optional<PlanRow> findPlanByNo(String no) {
        return jdbc.query("SELECT " + PLAN_COLS + " FROM berth_plan WHERE plan_no=?", planMapper, no)
                .stream().findFirst();
    }

    public List<PlanRow> listPlans() {
        return jdbc.query("SELECT " + PLAN_COLS + " FROM berth_plan ORDER BY id DESC", planMapper);
    }

    public List<PlanRow> listPlansByVoyage(long voyageId) {
        return jdbc.query("SELECT " + PLAN_COLS + " FROM berth_plan WHERE voyage_id=? ORDER BY id DESC",
                planMapper, voyageId);
    }

    public List<PlanRow> listPlansByStatus(String status) {
        return jdbc.query("SELECT " + PLAN_COLS + " FROM berth_plan WHERE status=? ORDER BY id",
                planMapper, status);
    }

    public void updateStatus(long planId, String status) {
        jdbc.update("UPDATE berth_plan SET status=?, updated_at=now() WHERE id=?", status, planId);
    }

    public void markStaged(long planId, String strategy, LocalDateTime expiresAt) {
        jdbc.update("""
                UPDATE berth_plan SET status='STAGED', strategy=?, staged_at=now(),
                       hold_expires_at=?, released_at=NULL, release_reason=NULL, updated_at=now()
                WHERE id=?
                """, strategy, Timestamp.valueOf(expiresAt), planId);
    }

    public void markConfirmed(long planId) {
        jdbc.update("""
                UPDATE berth_plan SET status='CONFIRMED', confirmed_at=now(), updated_at=now()
                WHERE id=?
                """, planId);
    }

    public void markReleased(long planId, String status, String reason) {
        jdbc.update("""
                UPDATE berth_plan SET status=?, released_at=now(), release_reason=?, updated_at=now()
                WHERE id=?
                """, status, reason, planId);
    }

    public int expireStagedPlans(LocalDateTime now) {
        return jdbc.update("""
                UPDATE berth_plan SET status='EXPIRED', released_at=now(),
                       release_reason='TTL_EXPIRED', updated_at=now()
                WHERE status='STAGED' AND hold_expires_at IS NOT NULL AND hold_expires_at <= ?
                """, Timestamp.valueOf(now));
    }

    // ================= 分段 =================

    public static class SegmentRow {
        public long id, planId;
        public int seqNo;
        public String segType, title, resourceType, resourceCode;
        public Long resourceId, recordId;
        public LocalDateTime startAt, endAt;
        public String status;
        public boolean affected;
        public String affectReason;
    }

    private final org.springframework.jdbc.core.RowMapper<SegmentRow> segMapper = (rs, n) -> {
        SegmentRow s = new SegmentRow();
        s.id = rs.getLong("id");
        s.planId = rs.getLong("plan_id");
        s.seqNo = rs.getInt("seq_no");
        s.segType = rs.getString("seg_type");
        s.title = rs.getString("title");
        s.resourceType = rs.getString("resource_type");
        long rid = rs.getLong("resource_id");
        s.resourceId = rs.wasNull() ? null : rid;
        s.resourceCode = rs.getString("resource_code");
        s.startAt = rs.getObject("start_at", LocalDateTime.class);
        s.endAt = rs.getObject("end_at", LocalDateTime.class);
        s.status = rs.getString("status");
        s.affected = rs.getBoolean("affected");
        s.affectReason = rs.getString("affect_reason");
        long rec = rs.getLong("record_id");
        s.recordId = rs.wasNull() ? null : rec;
        return s;
    };

    public long insertSegment(long planId, int seq, String segType, String title,
                              String resourceType, Long resourceId, String resourceCode,
                              LocalDateTime start, LocalDateTime end) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO plan_segment(plan_id, seq_no, seg_type, title, resource_type,
                                             resource_id, resource_code, start_at, end_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, planId);
            ps.setInt(2, seq);
            ps.setString(3, segType);
            ps.setString(4, title);
            ps.setString(5, resourceType);
            if (resourceId == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, resourceId);
            ps.setString(7, resourceCode);
            setTs(ps, 8, start);
            setTs(ps, 9, end);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public List<SegmentRow> listSegments(long planId) {
        return jdbc.query("SELECT * FROM plan_segment WHERE plan_id=? ORDER BY seq_no, id",
                segMapper, planId);
    }

    public Optional<SegmentRow> findSegment(long segmentId) {
        return jdbc.query("SELECT * FROM plan_segment WHERE id=?", segMapper, segmentId)
                .stream().findFirst();
    }

    public void updateSegmentStatus(long segmentId, String status, Long recordId) {
        jdbc.update("UPDATE plan_segment SET status=?, record_id=COALESCE(?, record_id) WHERE id=?",
                status, recordId, segmentId);
    }

    public void cancelPendingSegments(long planId, String reason) {
        jdbc.update("""
                UPDATE plan_segment
                   SET status='CANCELLED', affected=true,
                       affect_reason = CASE WHEN affect_reason IS NULL OR affect_reason=''
                                           THEN ? ELSE affect_reason || ' || 重排处置: ' || ? END
                WHERE plan_id=? AND status IN ('PLANNED','IN_PROGRESS')
                """, reason, reason, planId);
    }

    public void markSegmentAffected(long segmentId, String reason) {
        jdbc.update("UPDATE plan_segment SET affected=true, affect_reason=? WHERE id=?",
                reason, segmentId);
    }

    // ================= 资源占用 =================

    public void insertOccupation(long planId, long voyageId, String resourceType, long resourceId,
                                 String resourceKey, LocalDateTime start, LocalDateTime end,
                                 String kind) {
        jdbc.update("""
                INSERT INTO resource_occupation(plan_id, voyage_id, resource_type, resource_id,
                                                 resource_key, start_at, end_at, kind, status)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE')
                """, planId, voyageId, resourceType, resourceId, resourceKey,
                Timestamp.valueOf(start), Timestamp.valueOf(end), kind);
    }

    /**释放某方案的全部 ACTIVE 占用*/
    public int releaseOccupations(long planId, String reason) {
        return jdbc.update("""
                UPDATE resource_occupation SET status='RELEASED', released_at=now(),
                       release_reason=?
                WHERE plan_id=? AND status='ACTIVE'
                """, reason, planId);
    }

    /**TTL 扫描：释放过期 STAGED 方案的 TENTATIVE 占用，返回释放行数*/
    public int releaseExpiredTentativeOccupations(LocalDateTime now) {
        return jdbc.update("""
                UPDATE resource_occupation o SET status='RELEASED', released_at=now(),
                       release_reason='TTL_EXPIRED'
                FROM berth_plan p
                WHERE o.plan_id = p.id
                  AND o.status='ACTIVE' AND o.kind='TENTATIVE'
                  AND p.status='EXPIRED'
                """);
    }

    public static class HolderRow {
        public long occupationId, planId, voyageId;
        public String resourceKey;
        public String planNo, voyageNo, kind;
        public LocalDateTime holdExpiresAt, startAt, endAt;
    }

    public List<HolderRow> listActiveOccupations() {
        String sql = """
                SELECT o.id, o.plan_id, p.plan_no, o.voyage_id, v.voyage_no, o.kind,
                       p.hold_expires_at, o.start_at, o.end_at, o.resource_key
                FROM resource_occupation o
                LEFT JOIN berth_plan p ON p.id = o.plan_id
                LEFT JOIN voyage v ON v.id = o.voyage_id
                WHERE o.status='ACTIVE'
                ORDER BY o.start_at
                """;
        return jdbc.query(sql, (rs, n) -> {
            HolderRow h = new HolderRow();
            h.occupationId = rs.getLong(1);
            h.planId = rs.getLong(2);
            h.planNo = rs.getString(3);
            h.voyageId = rs.getLong(4);
            h.voyageNo = rs.getString(5);
            h.kind = rs.getString(6);
            h.holdExpiresAt = rs.getObject(7, LocalDateTime.class);
            h.startAt = rs.getObject(8, LocalDateTime.class);
            h.endAt = rs.getObject(9, LocalDateTime.class);
            h.resourceKey = rs.getString(10);
            return h;
        });
    }

    public List<HolderRow> findOverlapHolders(String resourceKey,
                                              LocalDateTime start, LocalDateTime end,
                                              Long excludePlanId) {
        String sql = """
                SELECT o.id, o.plan_id, p.plan_no, o.voyage_id, v.voyage_no, o.kind,
                       p.hold_expires_at, o.start_at, o.end_at, o.resource_key
                FROM resource_occupation o
                LEFT JOIN berth_plan p ON p.id = o.plan_id
                LEFT JOIN voyage v ON v.id = o.voyage_id
                WHERE o.status='ACTIVE' AND o.resource_key=?
                  AND o.start_at < ? AND o.end_at > ?
                  AND (? IS NULL OR o.plan_id <> ?)
                ORDER BY o.start_at
                """;
        return jdbc.query(sql, (rs, n) -> {
            HolderRow h = new HolderRow();
            h.occupationId = rs.getLong(1);
            h.planId = rs.getLong(2);
            h.planNo = rs.getString(3);
            h.voyageId = rs.getLong(4);
            h.voyageNo = rs.getString(5);
            h.kind = rs.getString(6);
            h.holdExpiresAt = rs.getObject(7, LocalDateTime.class);
            h.startAt = rs.getObject(8, LocalDateTime.class);
            h.endAt = rs.getObject(9, LocalDateTime.class);
            h.resourceKey = rs.getString(10);
            return h;
        }, resourceKey, Timestamp.valueOf(end), Timestamp.valueOf(start),
                excludePlanId, excludePlanId);
    }

    // ================= 事件 =================

    public void event(Long voyageId, Long planId, String type, String message, String detailJson) {
        jdbc.update("""
                INSERT INTO plan_event(voyage_id, plan_id, event_type, message, detail_json)
                VALUES (?,?,?,?,?)
                """, voyageId, planId, type, message, detailJson);
    }

    public List<java.util.Map<String, Object>> listEvents(Long voyageId) {
        String sql = """
                SELECT id, voyage_id, plan_id, event_type, message, detail_json, created_at
                FROM plan_event
                WHERE (? IS NULL OR voyage_id=?)
                ORDER BY id
                """;
        return jdbc.queryForList(sql, voyageId, voyageId);
    }

    // ================= 作业记录（不可变） =================

    public long insertWorkRecord(long voyageId, long planId, Long segmentId, String type,
                                 String operator, int workMinutes, int movedUnits,
                                 String note, LocalDateTime startedAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO work_record(voyage_id, plan_id, segment_id, record_type,
                                            operator_name, work_minutes, moved_units,
                                            result_note, started_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, voyageId);
            ps.setLong(2, planId);
            if (segmentId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, segmentId);
            ps.setString(4, type);
            ps.setString(5, operator);
            ps.setInt(6, workMinutes);
            ps.setInt(7, movedUnits);
            ps.setString(8, note);
            setTs(ps, 9, startedAt);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public List<java.util.Map<String, Object>> listWorkRecords(long voyageId) {
        return jdbc.queryForList("""
                SELECT id, voyage_id, plan_id, segment_id, record_type, operator_name,
                       work_minutes, moved_units, result_note, started_at, completed_at
                FROM work_record WHERE voyage_id=? ORDER BY id
                """, voyageId);
    }

    public int sumHandlingMinutes(long voyageId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(work_minutes),0) FROM work_record " +
                        "WHERE voyage_id=? AND record_type='CARGO_HANDLING'",
                Integer.class, voyageId);
        return v == null ? 0 : v;
    }

    // ================= 扰动与影响 =================

    public long insertDisruption(String dtype, Long refId, String payloadJson, String summary) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO disruption(dtype, ref_id, payload_json, summary)
                    VALUES (?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, dtype);
            if (refId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, refId);
            ps.setString(3, payloadJson);
            ps.setString(4, summary);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public long insertImpact(Long disruptionId, Long planId, Long voyageId, Long segmentId,
                             String severity, String code, String message,
                             LocalDateTime start, LocalDateTime end) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO impact_item(disruption_id, plan_id, voyage_id, segment_id,
                                           severity, code, message, start_at, end_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, disruptionId);
            if (planId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, planId);
            if (voyageId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, voyageId);
            if (segmentId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, segmentId);
            ps.setString(5, severity);
            ps.setString(6, code);
            ps.setString(7, message);
            setTs(ps, 8, start);
            setTs(ps, 9, end);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public List<java.util.Map<String, Object>> listImpacts(Long disruptionId) {
        return jdbc.queryForList("""
                SELECT id, disruption_id, plan_id, voyage_id, segment_id, severity, code,
                       message, start_at, end_at, handled
                FROM impact_item WHERE disruption_id=? ORDER BY id
                """, disruptionId);
    }

    public List<java.util.Map<String, Object>> listDisruptions() {
        return jdbc.queryForList(
                "SELECT id, dtype, ref_id, payload_json, summary, created_at " +
                        "FROM disruption ORDER BY id DESC");
    }
}
