package br.com.fipe.sinc_service.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Typed FIPE HTTP and conservative rate/retry settings. */
@ConfigurationProperties(prefix = "app.fipe")
public record FipeClientProperties(String baseUrl, long minRequestIntervalMs, int maxAttempts,
                                   long retryBackoffMs, long requestTimeoutSeconds,
                                   long maxBackoffMs, long maxRetryAfterMs,
                                   boolean sharedRateLimit, long sharedMaxIntervalMs) {
    public FipeClientProperties(String baseUrl, long minRequestIntervalMs, int maxAttempts,
                                long retryBackoffMs, long requestTimeoutSeconds,
                                long maxBackoffMs, long maxRetryAfterMs) {
        this(baseUrl, minRequestIntervalMs, maxAttempts, retryBackoffMs, requestTimeoutSeconds,
                maxBackoffMs, maxRetryAfterMs, true, 60_000);
    }
    @ConstructorBinding
    public FipeClientProperties {
        if (baseUrl == null || baseUrl.isBlank() || minRequestIntervalMs < 0 || maxAttempts < 1
                || retryBackoffMs < 0 || requestTimeoutSeconds < 1 || maxBackoffMs < retryBackoffMs
                || maxRetryAfterMs < 0 || sharedMaxIntervalMs < minRequestIntervalMs) {
            throw new IllegalArgumentException("Invalid FIPE client configuration");
        }
    }
}
