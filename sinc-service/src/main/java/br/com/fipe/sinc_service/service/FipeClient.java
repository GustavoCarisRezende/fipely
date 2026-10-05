package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.dto.FipeResponses;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class FipeClient {
    public record Response<T>(T data, String raw) {}
    private static final Logger log = LoggerFactory.getLogger(FipeClient.class);

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final long intervalMs;
    private final int attempts;
    private final long backoffMs;
    private final Duration timeout;
    private final AtomicLong nextRequestAt = new AtomicLong();
    private final AtomicLong adaptiveIntervalMs;
    private final long maxBackoffMs;
    private final long maxRetryAfterMs;
    private final MeterRegistry metrics;
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger();
    private SharedFipeRateLimiter sharedLimiter;

    @Autowired
    public FipeClient(ObjectMapper mapper, FipeClientProperties properties,
                      ObjectProvider<MeterRegistry> metrics, JdbcTemplate jdbc) {
        this(mapper, properties.baseUrl(), properties.minRequestIntervalMs(), properties.maxAttempts(),
                properties.retryBackoffMs(), properties.requestTimeoutSeconds(), properties.maxBackoffMs(),
                properties.maxRetryAfterMs(), metrics);
        if (properties.sharedRateLimit()) {
            this.sharedLimiter = new SharedFipeRateLimiter(jdbc, properties.minRequestIntervalMs(),
                    Math.max(properties.minRequestIntervalMs(), properties.sharedMaxIntervalMs()));
        }
    }

    private FipeClient(ObjectMapper mapper, String baseUrl, long intervalMs, int attempts,
                       long backoffMs, long timeoutSeconds, long maxBackoffMs, long maxRetryAfterMs,
                       ObjectProvider<MeterRegistry> metrics) {
        if (intervalMs < 0 || attempts < 1 || backoffMs < 0 || timeoutSeconds < 1
                || maxBackoffMs < backoffMs || maxRetryAfterMs < 0) {
            throw new IllegalArgumentException("Invalid FIPE rate-limit configuration");
        }
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.intervalMs = intervalMs;
        this.adaptiveIntervalMs = new AtomicLong(intervalMs);
        this.attempts = attempts;
        this.backoffMs = backoffMs;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.maxBackoffMs = maxBackoffMs;
        this.maxRetryAfterMs = maxRetryAfterMs;
        this.metrics = metrics == null ? null : metrics.getIfAvailable();
    }

    // Kept package-visible for isolated clients that do not need Spring metrics/configuration.
    FipeClient(ObjectMapper mapper, String baseUrl, long intervalMs, int attempts,
               long backoffMs, long timeoutSeconds) {
        this(mapper, baseUrl, intervalMs, attempts, backoffMs, timeoutSeconds,
                Math.max(backoffMs, 60_000), 300_000, (ObjectProvider<MeterRegistry>) null);
    }

    FipeClient(ObjectMapper mapper, String baseUrl, long intervalMs, int attempts, long backoffMs,
               long timeoutSeconds, long maxBackoffMs, long maxRetryAfterMs) {
        this(mapper, baseUrl, intervalMs, attempts, backoffMs, timeoutSeconds,
                maxBackoffMs, maxRetryAfterMs, (ObjectProvider<MeterRegistry>) null);
    }

    public List<FipeResponses.Period> periods() {
        return mapper.readValue(post("ConsultarTabelaDeReferencia", Map.of()), new TypeReference<>() {});
    }

    public Response<List<FipeResponses.Option>> brands(int period, int type) {
        String raw = post("ConsultarMarcas", Map.of("codigoTabelaReferencia", period, "codigoTipoVeiculo", type));
        return new Response<>(mapper.readValue(raw, new TypeReference<>() {}), raw);
    }

    public Response<FipeResponses.Models> models(int period, int type, String brand) {
        String raw = post("ConsultarModelos", Map.of("codigoTabelaReferencia", period,
                "codigoTipoVeiculo", type, "codigoMarca", brand, "ano", ""));
        return new Response<>(mapper.readValue(raw, FipeResponses.Models.class), raw);
    }

    public Response<List<FipeResponses.Option>> years(int period, int type, String brand, int model) {
        String raw = post("ConsultarAnoModelo", Map.of("codigoTabelaReferencia", period,
                "codigoTipoVeiculo", type, "codigoMarca", brand, "codigoModelo", model));
        return new Response<>(mapper.readValue(raw, new TypeReference<>() {}), raw);
    }

    public Response<FipeResponses.Price> price(int period, int type, String brand, int model,
                                                int year, String fuel) {
        String vehicle = switch (type) {
            case 1 -> "carro";
            case 2 -> "moto";
            case 3 -> "caminhao";
            default -> throw new IllegalArgumentException("Invalid vehicleType");
        };
        String raw = post("ConsultarValorComTodosParametros", Map.of(
                "codigoTabelaReferencia", period, "codigoMarca", brand,
                "codigoModelo", model, "codigoTipoVeiculo", type, "anoModelo", year,
                "codigoTipoCombustivel", fuel, "tipoVeiculo", vehicle, "tipoConsulta", "tradicional"));
        return new Response<>(mapper.readValue(raw, FipeResponses.Price.class), raw);
    }

    private String post(String endpoint, Map<String, ?> form) {
        long started = System.nanoTime();
        try {
            return request(endpoint, form);
        } finally {
            if (metrics != null) {
                Timer.builder("fipe.request.duration").tag("endpoint", endpoint)
                        .register(metrics).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
    }

    private String request(String endpoint, Map<String, ?> form) {
        long queuedNanos;
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().toString()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", "https://veiculos.fipe.org.br")
                .header("Referer", "https://veiculos.fipe.org.br/")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            queuedNanos = sharedLimiter == null ? reserveSlot() : sharedLimiter.acquire() * 1_000_000;
            if (metrics != null) Timer.builder("fipe.rate_limit.wait").tag("endpoint", endpoint)
                    .register(metrics)
                    .record(queuedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    record("fipe.responses", endpoint, Integer.toString(status), 1);
                    record("fipe.calls", endpoint, "success", 1);
                    recoverRateLimit();
                    if (sharedLimiter != null) sharedLimiter.success();
                    return response.body();
                }
                record("fipe.responses", endpoint, Integer.toString(status), 1);
                if (status == 429) {
                    consecutiveSuccesses.set(0);
                    adaptiveIntervalMs.updateAndGet(old -> Math.min(Math.max(old + 1, old * 2), Math.max(intervalMs, 60_000)));
                }
                long retryAfter = response.headers().firstValue("Retry-After")
                        .map(this::retryAfterMillis).orElse(0L);
                if (retryAfter > maxRetryAfterMs) {
                    record("fipe.calls", endpoint, "retry_after_exceeds_limit", 1);
                    throw new IllegalStateException("FIPE Retry-After exceeds configured safe limit");
                }
                if (status == 429 && sharedLimiter != null) sharedLimiter.backoff(retryAfter);
                else if (sharedLimiter != null) sharedLimiter.defer(retryAfter);
                if (attempt == attempts || (status != 429 && status < 500)) {
                    record("fipe.calls", endpoint, "failure", 1);
                    throw new IllegalStateException("FIPE " + endpoint + " returned HTTP " + status);
                }
                long exponential = Math.min(maxBackoffMs, backoffMs * (1L << Math.min(attempt - 1, 30)));
                long delay = Math.max(retryAfter, jitter(exponential));
                log.warn("FIPE {} returned HTTP {}; retry {}/{} in {}ms",
                        endpoint, status, attempt + 1, attempts, delay);
                pause(delay);
            } catch (HttpTimeoutException e) {
                record("fipe.timeouts", endpoint, "timeout", 1);
                if (attempt == attempts) {
                    record("fipe.calls", endpoint, "timeout", 1);
                    throw new IllegalStateException("FIPE " + endpoint + " timed out", e);
                }
                pause(jitter(Math.min(maxBackoffMs, backoffMs * (1L << Math.min(attempt - 1, 30)))));
            } catch (IOException e) {
                if (attempt == attempts) {
                    record("fipe.calls", endpoint, "failure", 1);
                    throw new IllegalStateException("FIPE " + endpoint + " unavailable", e);
                }
                log.warn("FIPE {} network error; retry {}/{}", endpoint, attempt + 1, attempts);
                pause(backoffMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FIPE request interrupted", e);
            } finally {
                record("fipe.attempts", endpoint, "attempt", 1);
            }
        }
        throw new IllegalStateException("FIPE retries exhausted");
    }

    private void recoverRateLimit() {
        if (adaptiveIntervalMs.get() <= intervalMs) return;
        if (consecutiveSuccesses.incrementAndGet() >= 5) {
            consecutiveSuccesses.set(0);
            adaptiveIntervalMs.updateAndGet(current -> Math.max(intervalMs, current - Math.max(1, (current - intervalMs) / 10)));
        }
    }

    long currentRateLimitIntervalMs() { return adaptiveIntervalMs.get(); }

    private long reserveSlot() {
        long now = System.currentTimeMillis();
        long interval = adaptiveIntervalMs.get();
        long reserved = nextRequestAt.getAndUpdate(previous -> Math.max(previous, now) + interval);
        long wait = Math.max(0, reserved - now);
        pause(wait);
        return wait * 1_000_000;
    }

    private static long jitter(long delay) {
        if (delay <= 1) return delay;
        return ThreadLocalRandom.current().nextLong(Math.max(1, delay / 2), delay + 1);
    }

    private void record(String name, String endpoint, String result, double amount) {
        if (metrics != null) metrics.counter(name, "endpoint", endpoint, "result", result).increment(amount);
    }

    private long retryAfterMillis(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) return 0;
            return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
        } catch (NumberFormatException ignored) {
            try {
                Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(Instant.now(), date).toMillis());
            } catch (RuntimeException invalid) {
                return 0;
            }
        }
    }

    private static void pause(long ms) {
        try {
            if (ms > 0) Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sync interrupted", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
