package br.com.fipe.sinc_service.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SharedFipeRateLimiterTest {
    @Test
    void acquiresSlotAndSharesAdaptiveIntervalWithoutHoldingJdbcConnectionWhileWaiting() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq(1_000L))).thenReturn(0L);
        SharedFipeRateLimiter limiter = new SharedFipeRateLimiter(jdbc, 1_000, 60_000);

        limiter.acquire();
        limiter.backoff(5_000);
        for (int i = 0; i < 5; i++) limiter.success();

        verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("UPDATE fipe.fipe_rate_limit"),
                org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq(1_000L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("interval_ms=LEAST"),
                org.mockito.ArgumentMatchers.eq(60_000L), org.mockito.ArgumentMatchers.eq(5_000L));
        verify(jdbc, org.mockito.Mockito.times(5)).update(
                org.mockito.ArgumentMatchers.contains("success_streak=CASE"),
                org.mockito.ArgumentMatchers.eq(1_000L), org.mockito.ArgumentMatchers.eq(1_000L));
    }
}
