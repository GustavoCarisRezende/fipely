package br.com.fipe.sinc_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.fipe.sinc_service.service.SharedFipeRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** Exercises PostgreSQL-specific V5 DDL and limiter SQL against the disposable test database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
class PostgresCoordinationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void v1ThroughV5AreInstalledAndActiveRootEquivalenceIsEnforcedByPostgres() {
        assertEquals("5", jdbc.queryForObject(
                "SELECT version FROM fipe.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        Long id = insertActiveRoot();
        try {
            assertThrows(DuplicateKeyException.class, this::insertActiveRoot,
                    "V5 NULLS NOT DISTINCT unique index must catch equivalent active roots");
        } finally {
            jdbc.update("DELETE FROM fipe.sync_jobs WHERE id=?", id);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fipe.fipe_rate_limit WHERE id=1", Integer.class));
    }

    @Test
    void sharedLimiterSerializesConcurrentSlotsUsingPostgres() throws Exception {
        jdbc.update("UPDATE fipe.fipe_rate_limit SET interval_ms=40, next_request_at=clock_timestamp(), retry_after_until=clock_timestamp(), success_streak=0 WHERE id=1");
        SharedFipeRateLimiter limiter = new SharedFipeRateLimiter(jdbc, 40, 1_000);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(limiter::acquire));
            List<Long> waits = new ArrayList<>();
            for (var future : futures) waits.add(future.get());
            assertEquals(6, waits.size());
            assertNotEquals(0L, waits.stream().mapToLong(Long::longValue).max().orElse(0),
                    "PostgreSQL should reserve distinct future request slots under contention");
        } finally {
            jdbc.update("UPDATE fipe.fipe_rate_limit SET interval_ms=1000, next_request_at=clock_timestamp(), retry_after_until=clock_timestamp(), success_streak=0 WHERE id=1");
        }
    }

    @Test
    void aSecondInstanceCannotAcquireTheGlobalAdvisoryLock() {
        var secondInstance = new br.com.fipe.sinc_service.service.SingleInstanceJobGuard(dataSource);
        assertThrows(IllegalStateException.class, secondInstance::start);
        org.junit.jupiter.api.Assertions.assertEquals(false, secondInstance.isRunning());
    }

    private Long insertActiveRoot() {
        return jdbc.queryForObject("""
                INSERT INTO fipe.sync_jobs(scope, reference_month, vehicle_type, brand_code, model_code,
                    model_year, fuel_code, refresh_old_records, status, phase)
                VALUES ('variant', DATE '2026-07-01', 2, 'test-brand', 983741, 2023, '5', false, 'queued', 'queued')
                RETURNING id
                """, Long.class);
    }
}
