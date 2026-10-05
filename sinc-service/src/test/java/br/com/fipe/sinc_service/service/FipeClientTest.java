package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.fipe.sinc_service.dto.FipeResponses;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FipeClientTest {
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mapsObservedPayloadsWithoutLosingCodeTypes() {
        server.createContext("/ConsultarTabelaDeReferencia", exchange ->
                reply(exchange, 200, "[{\"Codigo\":335,\"Mes\":\"julho/2026 \"}]"));
        server.createContext("/ConsultarMarcas", exchange ->
                reply(exchange, 200, "[{\"Label\":\"HONDA\",\"Value\":\"80\"}]"));
        server.createContext("/ConsultarModelos", exchange -> reply(exchange, 200,
                "{\"Modelos\":[{\"Label\":\"CB 300F Twister Flex\",\"Value\":10378}],"
                        + "\"Anos\":[{\"Label\":\"32000\",\"Value\":\"32000-5\"}]}"));
        server.createContext("/ConsultarAnoModelo", exchange ->
                reply(exchange, 200, "[{\"Label\":\"2023\",\"Value\":\"2023-5\"}]"));
        server.createContext("/ConsultarValorComTodosParametros", exchange -> reply(exchange, 200,
                "{\"Valor\":\"R$ 23.519,00\",\"Marca\":\"HONDA\",\"Modelo\":\"CB 300F Twister Flex\","
                        + "\"AnoModelo\":2023,\"Combustivel\":\"Flex\",\"CodigoFipe\":\"811174-0\","
                        + "\"MesReferencia\":\"julho de 2026 \",\"Autenticacao\":\"abc\","
                        + "\"TipoVeiculo\":2,\"SiglaCombustivel\":\"F\",\"DataConsulta\":\"texto\"}"));
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 2, 0, 5);

        assertEquals(335, client.periods().getFirst().code());
        assertEquals("80", client.brands(335, 2).data().getFirst().value());
        assertEquals(10378, client.models(335, 2, "80").data().models().getFirst().value());
        assertEquals("32000-5", client.models(335, 2, "80").data().years().getFirst().value());
        assertEquals("2023-5", client.years(335, 2, "80", 10378).data().getFirst().value());
        FipeResponses.Price price = client.price(335, 2, "80", 10378, 2023, "5").data();
        assertEquals("811174-0", price.fipeCode());
        assertEquals(2023, price.modelYear());
    }

    @Test
    void retries429AndHonorsRetryAfterHeader() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                reply(exchange, 429, "limit");
            } else reply(exchange, 200, "[{\"Label\":\"HONDA\",\"Value\":\"80\"}]");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 2, 0, 5);

        assertEquals(1, client.brands(335, 2).data().size());
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryClientErrorsOtherThan429() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 400, "bad request");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 3, 0, 5);

        assertThrows(IllegalStateException.class, () -> client.brands(335, 2));
        assertEquals(1, calls.get());
    }

    @Test
    void retriesServerErrorsButDoesNotExposeResponseBody() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            if (calls.incrementAndGet() == 1) reply(exchange, 503, "private response contents");
            else reply(exchange, 200, "[{\"Label\":\"HONDA\",\"Value\":\"80\"}]");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 2, 0, 5);
        assertEquals(1, client.brands(335, 2).data().size());
        assertEquals(2, calls.get());
    }

    @Test
    void recoversAdaptiveIntervalAfterConsecutiveSuccessesWithoutCrossingMinimum() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            if (calls.incrementAndGet() == 1) reply(exchange, 429, "limited");
            else reply(exchange, 200, "[]");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 2, 0, 5);
        assertEquals(0, client.currentRateLimitIntervalMs());
        client.brands(1, 1);
        assertEquals(1, client.currentRateLimitIntervalMs());
        for (int i = 0; i < 5; i++) client.brands(1, 1);
        assertEquals(0, client.currentRateLimitIntervalMs());
    }

    @Test
    void failsWithoutRetryWhenRetryAfterExceedsConfiguredCeiling() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "2");
            reply(exchange, 429, "limited");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 3, 0, 5, 1_000, 1_000);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.brands(1, 1));
        assertEquals("FIPE Retry-After exceeds configured safe limit", error.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void honorsRetryAfterWhenWithinConfiguredCeiling() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ConsultarMarcas", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                reply(exchange, 429, "limited");
            } else reply(exchange, 200, "[]");
        });
        server.start();
        FipeClient client = new FipeClient(new ObjectMapper(), base, 0, 2, 0, 5, 1_000, 1_000);
        assertEquals(0, client.brands(1, 1).data().size());
        assertEquals(2, calls.get());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
