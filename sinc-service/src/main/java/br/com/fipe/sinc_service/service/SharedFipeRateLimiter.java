package br.com.fipe.sinc_service.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** Database-backed request slots. Every database operation ends before sleeping or doing HTTP. */
public final class SharedFipeRateLimiter {
    private final JdbcTemplate jdbc;
    private final long minimumIntervalMs;
    private final long maximumIntervalMs;

    public SharedFipeRateLimiter(JdbcTemplate jdbc, long minimumIntervalMs, long maximumIntervalMs) {
        this.jdbc = jdbc;
        this.minimumIntervalMs = minimumIntervalMs;
        this.maximumIntervalMs = maximumIntervalMs;
    }

    public long acquire() {
        long wait = jdbc.queryForObject("""
                UPDATE fipe.fipe_rate_limit
                SET interval_ms=GREATEST(interval_ms, ?),
                    next_request_at = GREATEST(next_request_at, retry_after_until, clock_timestamp())
                    + GREATEST(interval_ms, ?) * interval '1 millisecond'
                WHERE id=1
                RETURNING GREATEST(0, (extract(epoch FROM
                    (next_request_at - clock_timestamp())) * 1000)::bigint)
                """, Long.class, minimumIntervalMs, minimumIntervalMs);
        pause(wait);
        return wait;
    }

    public void backoff(long retryAfterMs) {
        jdbc.update("""
                UPDATE fipe.fipe_rate_limit
                SET interval_ms=LEAST(?, GREATEST(interval_ms+1, interval_ms*2)),
                    retry_after_until=GREATEST(retry_after_until,
                        clock_timestamp() + ? * interval '1 millisecond'),
                    success_streak=0 WHERE id=1
                """, maximumIntervalMs, retryAfterMs);
    }

    public void defer(long retryAfterMs) {
        if (retryAfterMs <= 0) return;
        jdbc.update("UPDATE fipe.fipe_rate_limit SET retry_after_until=GREATEST(retry_after_until, clock_timestamp() + ? * interval '1 millisecond') WHERE id=1",
                retryAfterMs);
    }

    public void success() {
        jdbc.update("""
                UPDATE fipe.fipe_rate_limit SET
                    interval_ms=CASE WHEN success_streak=4
                        THEN GREATEST(?, interval_ms - GREATEST(1, (interval_ms-?)/10))
                        ELSE interval_ms END,
                    success_streak=CASE WHEN success_streak=4 THEN 0 ELSE success_streak+1 END
                WHERE id=1
                """, minimumIntervalMs, minimumIntervalMs);
    }

    private static void pause(long ms) {
        try {
            if (ms > 0) Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FIPE rate-limit wait interrupted", e);
        }
    }
}
