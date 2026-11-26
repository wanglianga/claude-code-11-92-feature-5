package com.port.berth.repo;

import com.port.berth.domain.Cat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**账号、模拟基础数据、船次的数据访问*/
@Repository
public class CatalogDao {

    private final JdbcTemplate jdbc;

    public CatalogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ---------------- 账号 / Token ----------------

    public Optional<UserRow> findUserByUsername(String username) {
        List<UserRow> list = jdbc.query(
                "SELECT id, username, password, display_name, role FROM app_user WHERE username=?",
                (rs, n) -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                username);
        return list.stream().findFirst();
    }

    public record UserRow(long id, String username, String password,
                          String displayName, String role) {}

    public void insertToken(String token, long userId) {
        jdbc.update("INSERT INTO auth_token(token, user_id) VALUES (?,?)", token, userId);
    }

    public Optional<Cat.AppUser> findUserByToken(String token) {
        return jdbc.query(
                """
                SELECT u.id, u.username, u.display_name, u.role
                FROM auth_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token = ?
                """,
                (rs, n) -> new Cat.AppUser(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)),
                token).stream().findFirst();
    }

    // ---------------- 船舶 / 船次 ----------------

    public List<Cat.Ship> listShips() {
        return jdbc.query("SELECT id, code, name, length_m, draft_m, cargo_type FROM ship ORDER BY id",
                (rs, n) -> new Cat.Ship(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6)));
    }

    public Optional<Cat.Ship> findShip(long id) {
        return listShips().stream().filter(s -> s.id() == id).findFirst();
    }

    public long createVoyage(String voyageNo, long shipId, String agentName,
                             LocalDateTime etaStart, LocalDateTime etaEnd,
                             int durationMin, int craneCount, String remark) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO voyage(voyage_no, ship_id, agent_name, eta_start, eta_end,
                                      work_duration_min, crane_count, status, remark)
                    VALUES (?,?,?,?,?,?,?, 'SUBMITTED', ?)
                    """, new String[]{"id"});
            ps.setString(1, voyageNo);
            ps.setLong(2, shipId);
            ps.setString(3, agentName);
            ps.setTimestamp(4, Timestamp.valueOf(etaStart));
            ps.setTimestamp(5, Timestamp.valueOf(etaEnd));
            ps.setInt(6, durationMin);
            ps.setInt(7, craneCount);
            ps.setString(8, remark);
            return ps;
        }, kh);
        return kh.getKeyAs(Long.class);
    }

    public Optional<Cat.Voyage> findVoyage(long id) {
        String sql = """
                SELECT v.id, v.voyage_no, v.ship_id, v.agent_name, v.eta_start, v.eta_end,
                       v.work_duration_min, v.crane_count, v.status, v.eta_revision, v.remark,
                       v.created_at,
                       s.id, s.code, s.name, s.length_m, s.draft_m, s.cargo_type
                FROM voyage v JOIN ship s ON s.id = v.ship_id
                WHERE v.id = ?
                """;
        return jdbc.query(sql, (rs, n) -> mapVoyage(rs), id).stream().findFirst();
    }

    public Optional<Cat.Voyage> findVoyageByNo(String no) {
        String sql = """
                SELECT v.id, v.voyage_no, v.ship_id, v.agent_name, v.eta_start, v.eta_end,
                       v.work_duration_min, v.crane_count, v.status, v.eta_revision, v.remark,
                       v.created_at,
                       s.id, s.code, s.name, s.length_m, s.draft_m, s.cargo_type
                FROM voyage v JOIN ship s ON s.id = v.ship_id
                WHERE v.voyage_no = ?
                """;
        return jdbc.query(sql, (rs, n) -> mapVoyage(rs), no).stream().findFirst();
    }

    private Cat.Voyage mapVoyage(java.sql.ResultSet rs) throws java.sql.SQLException {
        Cat.Ship ship = new Cat.Ship(rs.getLong(13), rs.getString(14), rs.getString(15),
                rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getString(18));
        return new Cat.Voyage(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class),
                rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getInt(10), rs.getString(11),
                rs.getObject(12, LocalDateTime.class), ship);
    }

    public List<Cat.Voyage> listVoyages() {
        String sql = """
                SELECT v.id, v.voyage_no, v.ship_id, v.agent_name, v.eta_start, v.eta_end,
                       v.work_duration_min, v.crane_count, v.status, v.eta_revision, v.remark,
                       v.created_at,
                       s.id, s.code, s.name, s.length_m, s.draft_m, s.cargo_type
                FROM voyage v JOIN ship s ON s.id = v.ship_id
                ORDER BY v.eta_start, v.id
                """;
        return jdbc.query(sql, (rs, n) -> mapVoyage(rs));
    }

    public void updateEta(long voyageId, LocalDateTime start, LocalDateTime end, String status) {
        jdbc.update("""
                UPDATE voyage SET eta_start=?, eta_end=?, status=?, eta_revision=eta_revision+1,
                                 updated_at=now()
                WHERE id=?
                """, Timestamp.valueOf(start), Timestamp.valueOf(end), status, voyageId);
    }

    public void updateVoyageStatus(long voyageId, String status) {
        jdbc.update("UPDATE voyage SET status=?, updated_at=now() WHERE id=?", status, voyageId);
    }

    // ---------------- 泊位 ----------------

    public List<Cat.Berth> listBerths() {
        List<BerthRow> rows = jdbc.query(
                "SELECT id, code, name, max_length_m, chart_depth_m, ukc_min_m FROM berth ORDER BY id",
                (rs, n) -> new BerthRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        List<Cat.Berth> result = new ArrayList<>();
        for (BerthRow b : rows) {
            List<String> cargo = jdbc.queryForList(
                    "SELECT cargo_type FROM berth_cargo_limit WHERE berth_id=? ORDER BY cargo_type",
                    String.class, b.id());
            result.add(new Cat.Berth(b.id(), b.code(), b.name(), b.maxLength(),
                    b.chartDepth(), b.ukcMin(), cargo));
        }
        return result;
    }

    public Optional<Cat.Berth> findBerth(long id) {
        return listBerths().stream().filter(b -> b.id() == id).findFirst();
    }

    private record BerthRow(long id, String code, String name,
                            java.math.BigDecimal maxLength,
                            java.math.BigDecimal chartDepth,
                            java.math.BigDecimal ukcMin) {}

    // ---------------- 潮汐 ----------------

    public List<Cat.TideSlot> listTides() {
        return jdbc.query(
                "SELECT id, slot_start, slot_end, height_m, revised, revision_reason " +
                        "FROM tide_slot ORDER BY slot_start",
                (rs, n) -> new Cat.TideSlot(rs.getLong(1),
                        rs.getObject(2, LocalDateTime.class),
                        rs.getObject(3, LocalDateTime.class),
                        rs.getBigDecimal(4), rs.getBoolean(5), rs.getString(6)));
    }

    // ---------------- 工作窗口 ----------------

    public List<Cat.WorkWindow> windows(String resourceType, long resourceId) {
        return jdbc.query(
                "SELECT resource_type, resource_id, start_at, end_at FROM work_window " +
                        "WHERE resource_type=? AND resource_id=? ORDER BY start_at",
                (rs, n) -> new Cat.WorkWindow(rs.getString(1), rs.getLong(2),
                        rs.getObject(3, LocalDateTime.class),
                        rs.getObject(4, LocalDateTime.class)),
                resourceType, resourceId);
    }

    // ---------------- 引航员 / 拖轮 / 岸桥 ----------------

    public List<Cat.Pilot> listPilots() {
        List<Cat.Pilot> pilots = jdbc.query(
                "SELECT id, code, name, qualification, max_length_m, max_draft_m, handover_min " +
                        "FROM pilot ORDER BY id",
                (rs, n) -> new Cat.Pilot(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getInt(7),
                        windows("PILOT", rs.getLong(1))));
        return pilots;
    }

    public List<Cat.Tug> listTugs() {
        return jdbc.query(
                "SELECT id, code, name, max_length_m, max_draft_m, bollard_pull_t, turnaround_min " +
                        "FROM tug ORDER BY id",
                (rs, n) -> new Cat.Tug(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getInt(7),
                        windows("TUG", rs.getLong(1))));
    }

    public List<Cat.Crane> listCranes() {
        return jdbc.query(
                "SELECT id, code, name, transfer_min, active FROM quay_crane ORDER BY id",
                (rs, n) -> new Cat.Crane(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getBoolean(5), windows("CRANE", rs.getLong(1))));
    }

    // ---------------- 故障 / 停用 ----------------

    public List<Cat.Fault> listFaults() {
        return jdbc.query(
                "SELECT id, resource_type, resource_id, start_at, end_at, reason " +
                        "FROM equipment_fault ORDER BY start_at DESC",
                (rs, n) -> new Cat.Fault(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, LocalDateTime.class),
                        rs.getObject(5, LocalDateTime.class), rs.getString(6)));
    }

    public List<Cat.Fault> faultsFor(String resourceType, long resourceId) {
        return jdbc.query(
                "SELECT id, resource_type, resource_id, start_at, end_at, reason " +
                        "FROM equipment_fault WHERE resource_type=? AND resource_id=?",
                (rs, n) -> new Cat.Fault(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, LocalDateTime.class),
                        rs.getObject(5, LocalDateTime.class), rs.getString(6)),
                resourceType, resourceId);
    }
}
