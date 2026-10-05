package br.com.fipe.sinc_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.service.SyncProgressService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import br.com.fipe.sinc_service.TestDatabaseSafetyInitializer;
import br.com.fipe.sinc_service.repository.CatalogRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
class ProgressWebSocketIntegrationTest {
    @LocalServerPort
    private int port;

    @Value("${app.security.token}")
    private String token;

    @Autowired
    private SyncProgressService progress;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogRepository catalog;

    @Test
    void httpSubmissionReturns202AndProgressEndpointReachesTerminalStateWithoutFipeCalls() throws Exception {
        LocalDate month = LocalDate.of(2026, 7, 1);
        var period = catalog.savePeriod(55901, "julho/2026", month);
        var now = java.time.Instant.now();
        var brand = catalog.saveBrand(2, "test-http-brand", "TEST", now);
        catalog.savePeriodBrand(period.id(), brand.id(), "TEST", now);
        var model = catalog.saveModel(brand.id(), 55901, "test-http-model", now);
        catalog.savePeriodModel(period.id(), brand.id(), model.id(), "TEST MODEL", now);
        var variant = catalog.saveVariant(model.id(), "55901-5", 2023, "5", now);
        catalog.savePeriodVariant(period.id(), model.id(), variant.id(), "2023 Flex", now);
        catalog.savePrice(period.id(), variant.id(), new BigDecimal("12000.00"), "TEST-55901", "{}", now);

        HttpClient client = HttpClient.newHttpClient();
        String root = "http://127.0.0.1:" + port + "/fipely-sinc-service/api/v1/sync";
        String body = "{\"referenceMonth\":\"2026-07\",\"vehicleType\":2,\"brandCode\":\"test-http-brand\","
                + "\"modelCode\":55901,\"modelYear\":2023,\"fuelCode\":\"5\"}";
        HttpResponse<String> accepted = client.send(HttpRequest.newBuilder(URI.create(root + "/variants"))
                .header("X-API-Token", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        long jobId = new tools.jackson.databind.ObjectMapper().readTree(accepted.body()).get("jobId").asLong();

        String progressUrl = root + "/jobs/" + jobId;
        String status = "queued";
        for (int attempt = 0; attempt < 50 && !status.equals("completed"); attempt++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(progressUrl))
                    .header("X-API-Token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            status = new tools.jackson.databind.ObjectMapper().readTree(response.body()).get("status").asString();
            if (!status.equals("completed")) Thread.sleep(20);
        }
        assertEquals("completed", status);

        jdbc.update("DELETE FROM fipe.vehicle_prices WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.period_variants WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.year_list_responses WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.period_models WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.model_list_responses WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.period_brands WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.brand_list_responses WHERE period_id=?", period.id());
        jdbc.update("DELETE FROM fipe.reference_periods WHERE id=?", period.id());
        jdbc.update("DELETE FROM fipe.model_variants WHERE model_id=?", model.id());
        jdbc.update("DELETE FROM fipe.models WHERE id=?", model.id());
        jdbc.update("DELETE FROM fipe.brands WHERE id=?", brand.id());
    }

    @Test
    void browserSubprotocolAndHeaderBothReceiveSnapshotButAnonymousHandshakeFails() throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/fipely-sinc-service/api/v1/sync/progress");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newHttpClient();

        SnapshotListener browser = new SnapshotListener();
        WebSocket browserSocket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("fipely-progress", "token." + encoded)
                .buildAsync(uri, browser).join();
        assertEquals("fipely-progress", browserSocket.getSubprotocol());
        assertTrue(browser.received.await(5, TimeUnit.SECONDS));
        assertTrue(browser.message.get().contains("\"type\":\"snapshot\""));
        browserSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        SnapshotListener otherClient = new SnapshotListener();
        WebSocket headerSocket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("X-API-Token", token).buildAsync(uri, otherClient).join();
        assertTrue(otherClient.received.await(5, TimeUnit.SECONDS));
        headerSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(uri, new SnapshotListener()).join());
        assertTrue(failure.getCause() instanceof WebSocketHandshakeException);
        assertEquals(401, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
    }

    @Test
    void sendsLiveProgressEventsToConnectedBrowser() throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/fipely-sinc-service/api/v1/sync/progress");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
        SnapshotListener listener = new SnapshotListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("fipely-progress", "token." + encoded).buildAsync(uri, listener).join();
        long jobId = 0;
        try {
            assertTrue(listener.received.await(5, TimeUnit.SECONDS));
            jobId = progress.create("variant", new SyncRequest("2026-07", 2, "80", 10378, 2023, "5"),
                    LocalDate.of(2026, 7, 1), false, null, false);
            progress.discovered(jobId, 1, 1, 1);
            progress.processed(jobId, true);
            progress.finish(jobId, "completed", null);

            boolean found = false;
            for (int i = 0; i < 6; i++) {
                String message = listener.messages.poll(5, TimeUnit.SECONDS);
                if (message != null && message.contains("\"type\":\"progress\"")
                        && message.contains("\"jobId\":" + jobId)
                        && message.contains("\"vehiclesProcessed\":1")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            if (jobId != 0) jdbc.update("DELETE FROM fipe.sync_jobs WHERE id=?", jobId);
        }
    }

    private static final class SnapshotListener implements WebSocket.Listener {
        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<String> message = new AtomicReference<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            message.set(data.toString());
            messages.add(data.toString());
            received.countDown();
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
