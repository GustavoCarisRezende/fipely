package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.dto.FipeResponses;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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

    public FipeClient(ObjectMapper mapper,
                      @Value("${app.fipe.base-url}") String baseUrl,
                      @Value("${app.fipe.min-request-interval-ms}") long intervalMs,
                      @Value("${app.fipe.max-attempts}") int attempts,
                      @Value("${app.fipe.retry-backoff-ms}") long backoffMs,
                      @Value("${app.fipe.request-timeout-seconds}") long timeoutSeconds) {
        if (intervalMs < 0 || attempts < 1 || backoffMs < 0 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid FIPE rate-limit configuration");
        }
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.intervalMs = intervalMs;
        this.attempts = attempts;
        this.backoffMs = backoffMs;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
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
            reserveSlot();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                if (attempt == attempts || (status != 429 && status < 500)) {
                    throw new IllegalStateException("FIPE " + endpoint + " returned HTTP " + status);
                }
                long retryAfter = response.headers().firstValue("Retry-After")
                        .map(this::retryAfterMillis).orElse(0L);
                long delay = Math.max(retryAfter, backoffMs * attempt);
                log.warn("FIPE {} returned HTTP {}; retry {}/{} in {}ms",
                        endpoint, status, attempt + 1, attempts, delay);
                pause(delay);
            } catch (IOException e) {
                if (attempt == attempts) throw new IllegalStateException("FIPE " + endpoint + " unavailable", e);
                log.warn("FIPE {} network error; retry {}/{}", endpoint, attempt + 1, attempts);
                pause(backoffMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FIPE request interrupted", e);
            }
        }
        throw new IllegalStateException("FIPE retries exhausted");
    }

    private void reserveSlot() {
        long now = System.currentTimeMillis();
        long reserved = nextRequestAt.getAndUpdate(previous -> Math.max(previous, System.currentTimeMillis()) + intervalMs);
        pause(Math.max(0, reserved - now));
    }

    private long retryAfterMillis(String value) {
        try {
            return Math.max(0, Long.parseLong(value.trim()) * 1000);
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
