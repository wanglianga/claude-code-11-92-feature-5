package com.port.berth.repo;

import com.port.berth.domain.Laytime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**滞期争议：合同、回执、账单版本、被更正区段的数据访问*/
@Repository
public class LaytimeDao {

    private final JdbcTemplate jdbc;

    public LaytimeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ================= 合同 =================

    public long insertContract(long voyageId, int freeMinutes, java.math.BigDecimal ratePerHour,
                               String exceptionRule, String remark) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO laytime_contract(voyage_id, free_minutes, rate_per_hour,
                                                 exception_rule, remark)
                    VALUES (?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, voyageId);
            ps.setInt(2, freeMinutes);
            ps.setBigDecimal(3, ratePerHour);
            ps.setString(4, exceptionRule);
            ps.setString(5, remark);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public Optional<Laytime.Contract> findContract(long voyageId) {
        return jdbc.query("""
                        SELECT id, voyage_id, free_minutes, rate_per_hour, exception_rule, remark
                        FROM laytime_contract WHERE voyage_id=?
                        """,
                (rs, n) -> new Laytime.Contract(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)),
                voyageId).stream().findFirst();
    }

    // ================= 回执 =================

    private final RowMapper<Laytime.Event> eventMapper = (rs, n) -> new Laytime.Event(
            rs.getLong("id"), rs.getLong("voyage_id"), rs.getString("event_type"),
            rs.getObject("occurred_at", LocalDateTime.class),
            rs.getObject("received_at", LocalDateTime.class),
            rs.getString("party"), rs.getBoolean("confirmed"), rs.getString("claim_cause"),
            rs.getObject("window_end", LocalDateTime.class),
            rs.getString("evidence_ref"), rs.getString("note"));

    public long insertEvent(Laytime.Event e) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO laytime_event(voyage_id, event_type, occurred_at, received_at,
                                              party, confirmed, claim_cause, window_end,
                                              evidence_ref, note)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, e.voyageId());
            ps.setString(2, e.eventType());
            ps.setTimestamp(3, Timestamp.valueOf(e.occurredAt()));
            ps.setTimestamp(4, Timestamp.valueOf(e.receivedAt()));
            ps.setString(5, e.party());
            ps.setBoolean(6, e.confirmed());
            ps.setString(7, e.claimCause());
            if (e.windowEnd() == null) ps.setNull(8, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(8, Timestamp.valueOf(e.windowEnd()));
            ps.setString(9, e.evidenceRef());
            ps.setString(10, e.note());
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public List<Laytime.Event> listEvents(long voyageId) {
        return jdbc.query("""
                SELECT id, voyage_id, event_type, occurred_at, received_at, party, confirmed,
                       claim_cause, window_end, evidence_ref, note
                FROM laytime_event WHERE voyage_id=? ORDER BY received_at, id
                """, eventMapper, voyageId);
    }

    public boolean hasEventOfType(long voyageId, String eventType) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM laytime_event WHERE voyage_id=? AND event_type=?",
                Integer.class, voyageId, eventType);
        return c != null && c > 0;
    }

    // ================= 账单版本 =================

    public long insertStatement(long voyageId, int versionNo, Laytime.Statement st,
                                String segmentsJson) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO laytime_statement(voyage_id, version_no, kind, as_of,
                        ready_at, count_from, end_at, billable_minutes, amount, segments_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, voyageId);
            ps.setInt(2, versionNo);
            ps.setString(3, st.kind());
            ps.setTimestamp(4, Timestamp.valueOf(st.asOf()));
            if (st.readyAt() == null) ps.setNull(5, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(5, Timestamp.valueOf(st.readyAt()));
            if (st.countFrom() == null) ps.setNull(6, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(6, Timestamp.valueOf(st.countFrom()));
            if (st.endAt() == null) ps.setNull(7, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(7, Timestamp.valueOf(st.endAt()));
            ps.setLong(8, st.billableMinutes());
            ps.setBigDecimal(9, st.amount());
            ps.setString(10, segmentsJson);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public int nextVersionNo(long voyageId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM laytime_statement WHERE voyage_id=?",
                Integer.class, voyageId);
        return v == null ? 1 : v;
    }

    public List<Map<String, Object>> listStatements(long voyageId) {
        return jdbc.query("""
                SELECT id, voyage_id, version_no, kind, as_of, ready_at, count_from, end_at,
                       billable_minutes, amount, segments_json, finalized, created_at
                FROM laytime_statement WHERE voyage_id=? ORDER BY version_no
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("voyage_id", rs.getLong("voyage_id"));
                    m.put("version_no", rs.getInt("version_no"));
                    m.put("kind", rs.getString("kind"));
                    m.put("as_of", rs.getObject("as_of", LocalDateTime.class));
                    m.put("ready_at", rs.getObject("ready_at", LocalDateTime.class));
                    m.put("count_from", rs.getObject("count_from", LocalDateTime.class));
                    m.put("end_at", rs.getObject("end_at", LocalDateTime.class));
                    m.put("billable_minutes", rs.getInt("billable_minutes"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("segments_json", rs.getString("segments_json"));
                    m.put("finalized", rs.getBoolean("finalized"));
                    m.put("created_at", rs.getObject("created_at", LocalDateTime.class));
                    return m;
                }, voyageId);
    }

    public Optional<Map<String, Object>> latestStatement(long voyageId) {
        return jdbc.query("""
                SELECT id, voyage_id, version_no, kind, as_of, ready_at, count_from, end_at,
                       billable_minutes, amount, segments_json, finalized, created_at
                FROM laytime_statement WHERE voyage_id=? ORDER BY version_no DESC LIMIT 1
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("voyage_id", rs.getLong("voyage_id"));
                    m.put("version_no", rs.getInt("version_no"));
                    m.put("kind", rs.getString("kind"));
                    m.put("as_of", rs.getObject("as_of", LocalDateTime.class));
                    m.put("billable_minutes", rs.getInt("billable_minutes"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("segments_json", rs.getString("segments_json"));
                    m.put("finalized", rs.getBoolean("finalized"));
                    return m;
                }, voyageId).stream().findFirst();
    }

    public void markFinalized(long statementId) {
        jdbc.update("UPDATE laytime_statement SET finalized=true WHERE id=?", statementId);
    }

    // ================= 被更正区段 =================

    public void insertCorrection(long voyageId, long statementId,
                                 LocalDateTime segStart, LocalDateTime segEnd,
                                 String fromClass, String toClass,
                                 Long causeEventId, String note) {
        jdbc.update("""
                INSERT INTO laytime_correction(voyage_id, statement_id, seg_start, seg_end,
                                               from_class, to_class, cause_event_id, note)
                VALUES (?,?,?,?,?,?,?,?)
                """, voyageId, statementId, Timestamp.valueOf(segStart),
                Timestamp.valueOf(segEnd), fromClass, toClass, causeEventId, note);
    }

    public List<Map<String, Object>> listCorrections(long voyageId) {
        return jdbc.query("""
                SELECT c.id, c.voyage_id, c.statement_id, c.seg_start, c.seg_end,
                       c.from_class, c.to_class, c.cause_event_id, c.note, c.created_at,
                       e.evidence_ref, e.party AS cause_party
                FROM laytime_correction c
                LEFT JOIN laytime_event e ON e.id = c.cause_event_id
                WHERE c.voyage_id=? ORDER BY c.id
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("voyage_id", rs.getLong("voyage_id"));
                    m.put("statement_id", rs.getLong("statement_id"));
                    m.put("seg_start", rs.getObject("seg_start", LocalDateTime.class));
                    m.put("seg_end", rs.getObject("seg_end", LocalDateTime.class));
                    m.put("from_class", rs.getString("from_class"));
                    m.put("to_class", rs.getString("to_class"));
                    long ce = rs.getLong("cause_event_id");
                    m.put("cause_event_id", rs.wasNull() ? null : ce);
                    m.put("note", rs.getString("note"));
                    m.put("created_at", rs.getObject("created_at", LocalDateTime.class));
                    m.put("evidence_ref", rs.getString("evidence_ref"));
                    m.put("cause_party", rs.getString("cause_party"));
                    return m;
                }, voyageId);
    }
}
