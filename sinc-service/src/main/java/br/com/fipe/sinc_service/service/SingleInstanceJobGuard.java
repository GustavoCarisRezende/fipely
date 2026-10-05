package br.com.fipe.sinc_service.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** Explicit safety gate until durable per-job leases are implemented. */
@Component
public final class SingleInstanceJobGuard implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SingleInstanceJobGuard.class);
    private static final long LOCK_KEY = 0x464950454A4F4253L;
    private final DataSource dataSource;
    private volatile Connection lockConnection;
    private volatile boolean running;

    public SingleInstanceJobGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        throw new IllegalStateException("Another sinc-service instance owns job processing; startup refused");
                    }
                }
            }
            lockConnection = connection;
            running = true;
            log.info("This instance owns the global sync-job processing lock");
        } catch (SQLException e) {
            lockConnection = null;
            running = false;
            if (connection != null) {
                try { connection.close(); }
                catch (SQLException closeFailure) { e.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("Could not acquire PostgreSQL job-processing advisory lock; startup refused", e);
        }
    }

    @Override
    public synchronized void stop() {
        // Keep ownership through worker shutdown; SyncService releases after joining its workers.
        running = false;
    }

    public synchronized void releaseAfterWorkers() {
        Connection connection = lockConnection;
        lockConnection = null;
        running = false;
        if (connection == null) return;
        try (connection; var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        } catch (SQLException e) {
            log.warn("Could not explicitly release PostgreSQL job-processing lock; closing its session releases it");
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MIN_VALUE; }
}

@Configuration(proxyBeanMethods = false)
class JobGuardMigrationConfiguration {
    @Bean
    FlywayMigrationStrategy guardedFlywayMigration(SingleInstanceJobGuard guard) {
        return (Flyway flyway) -> {
            guard.start();
            try {
                flyway.migrate();
            } catch (RuntimeException failure) {
                guard.releaseAfterWorkers();
                throw failure;
            }
        };
    }
}
