package br.com.fipe.sinc_service.repository;

import br.com.fipe.sinc_service.dto.SyncProgress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SyncJobRepository {
    private static final String SELECT = """
            SELECT id, parent_job_id, scope, reference_month, vehicle_type, brand_code,
                   model_code, model_year, fuel_code, refresh_old_records, include_variants, status, phase,
                   current_brand, current_model, brands_discovered, models_discovered,
                   vehicles_discovered, vehicles_processed, vehicles_synced, vehicles_skipped,
                   discovery_complete, started_at, updated_at, finished_at, error_message
            FROM fipe.sync_jobs
            """;

    private static final RowMapper<SyncProgress> JOB = (rs, row) -> new SyncProgress(
            rs.getLong("id"), nullableLong(rs, "parent_job_id"), rs.getString("scope"),
            rs.getDate("reference_month").toLocalDate(), nullableInt(rs, "vehicle_type"),
            rs.getString("brand_code"), nullableInt(rs, "model_code"),
            nullableInt(rs, "model_year"), rs.getString("fuel_code"),
            rs.getBoolean("refresh_old_records"), rs.getBoolean("include_variants"),
            rs.getString("status"), rs.getString("phase"),
            rs.getString("current_brand"), rs.getString("current_model"),
            rs.getLong("brands_discovered"), rs.getLong("models_discovered"),
            rs.getLong("vehicles_discovered"), rs.getLong("vehicles_processed"),
            rs.getLong("vehicles_synced"), rs.getLong("vehicles_skipped"),
            rs.getLong("vehicles_discovered") - rs.getLong("vehicles_processed"),
            rs.getBoolean("discovery_complete"), rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), instant(rs, "finished_at"),
            rs.getString("error_message"), List.of());

    private final JdbcTemplate jdbc;

    public SyncJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String scope, java.time.LocalDate month, Integer vehicleType, String brandCode,
                       Integer modelCode, Integer modelYear, String fuelCode,
                       boolean refresh, boolean includeVariants, Long parentId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fipe.sync_jobs
                    (parent_job_id, scope, reference_month, vehicle_type, brand_code,
                     model_code, model_year, fuel_code, refresh_old_records, include_variants, status, phase)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, parentId, scope, month, vehicleType,
                brandCode, modelCode, modelYear, fuelCode,
                refresh, includeVariants, status, status.equals("queued") ? "waiting" : "referencePeriod");
    }

    public Optional<SyncProgress> find(long id) {
        return jdbc.query(SELECT + " WHERE id=?", JOB, id).stream().findFirst();
    }

    public List<SyncProgress> children(long parentId) {
        return jdbc.query(SELECT + " WHERE parent_job_id=? ORDER BY id", JOB, parentId);
    }

    public List<SyncProgress> topLevel(String status, int limit) {
        if (status == null) {
            return jdbc.query(SELECT + " WHERE parent_job_id IS NULL AND status IN ('queued', 'running')"
                    + " ORDER BY id DESC LIMIT ?", JOB, limit);
        }
        return jdbc.query(SELECT + " WHERE parent_job_id IS NULL AND status=? ORDER BY id DESC LIMIT ?",
                JOB, status, limit);
    }

    public void start(long id) {
        jdbc.update("UPDATE fipe.sync_jobs SET status='running', phase='referencePeriod', updated_at=now() WHERE id=?", id);
    }

    public void activity(long id, String phase, Integer type, String brand, String model) {
        jdbc.update("""
                UPDATE fipe.sync_jobs SET phase=?, vehicle_type=COALESCE(?, vehicle_type),
                    current_brand=?, current_model=?, updated_at=now()
                WHERE id=?
                """, phase, type, brand, model, id);
    }

    public void discovered(long id, long brands, long models, long vehicles) {
        jdbc.update("""
                UPDATE fipe.sync_jobs SET brands_discovered=brands_discovered+?,
                    models_discovered=models_discovered+?, vehicles_discovered=vehicles_discovered+?,
                    updated_at=now() WHERE id=?
                """, brands, models, vehicles, id);
    }

    public void processed(long id, boolean fetched) {
        processedBatch(id, 1, fetched);
    }

    public void processedBatch(long id, long quantity, boolean fetched) {
        if (quantity == 0) return;
        jdbc.update("""
                UPDATE fipe.sync_jobs SET vehicles_processed=vehicles_processed+?,
                    vehicles_synced=vehicles_synced+?, vehicles_skipped=vehicles_skipped+?,
                    updated_at=now() WHERE id=?
                """, quantity, fetched ? quantity : 0, fetched ? 0 : quantity, id);
    }

    public void discoveryComplete(long id) {
        jdbc.update("UPDATE fipe.sync_jobs SET discovery_complete=true, updated_at=now() WHERE id=?", id);
    }

    public void finish(long id, String status, String error) {
        jdbc.update("""
                UPDATE fipe.sync_jobs SET status=?, phase=?,
                    discovery_complete=CASE WHEN ?='completed' THEN true ELSE discovery_complete END,
                    finished_at=now(), updated_at=now(), error_message=? WHERE id=?
                """, status, status, status, error, id);
    }

    public void failInterrupted() {
        jdbc.update("""
                UPDATE fipe.sync_jobs SET status='failed', phase='failed',
                    finished_at=now(), updated_at=now(),
                    error_message='Service restarted before sync completion'
                WHERE status IN ('queued', 'running')
                """);
    }

    public void failQueuedChildren(long parentId, String error) {
        jdbc.update("""
                UPDATE fipe.sync_jobs SET status='failed', phase='failed',
                    finished_at=now(), updated_at=now(), error_message=?
                WHERE parent_job_id=? AND status='queued'
                """, error, parentId);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
