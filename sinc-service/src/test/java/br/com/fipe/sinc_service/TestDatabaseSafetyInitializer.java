package br.com.fipe.sinc_service;

import java.net.URI;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Fails before Spring refresh (and before Flyway/JDBC) unless an isolated test DB was opted into. */
public final class TestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String marker = System.getenv("FIPELY_TEST_DB_ISOLATED");
        if (!"true".equals(marker)) {
            throw new IllegalStateException("Spring integration tests require FIPELY_TEST_DB_ISOLATED=true");
        }
        String jdbcUrl = required("FIPELY_TEST_DB_URL");
        required("FIPELY_TEST_DB_USERNAME");
        required("FIPELY_TEST_DB_PASSWORD");
        required("FIPELY_TEST_API_TOKEN");
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("FIPELY_TEST_DB_URL must be a PostgreSQL JDBC URL");
        }
        URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("FIPELY_TEST_DB_URL is not a valid PostgreSQL URL", invalid);
        }
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (database.equalsIgnoreCase("fipely")
                || (uri.getPort() == 5432 && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost())))) {
            throw new IllegalStateException("Refusing unsafe FIPELY_TEST_DB_URL targeting the default fipely database");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be explicitly set for integration tests");
        return value;
    }
}
