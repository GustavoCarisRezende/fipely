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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgressWebSocketIntegrationTest {
    @LocalServerPort
    private int port;

    @Value("${app.security.token}")
    private String token;

    @Autowired
    private SyncProgressService progress;

    @Autowired
    private JdbcTemplate jdbc;

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
