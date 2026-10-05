package br.com.fipe.sinc_service.controller;

import br.com.fipe.sinc_service.dto.SyncProgress;
import br.com.fipe.sinc_service.service.SyncProgressService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.beans.factory.DisposableBean;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProgressWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketHandler.class);
    private static final int SENDERS = 32;
    private static final int MAX_PENDING_JOBS_PER_CLIENT = 256;
    private final SyncProgressService progress;
    private final ObjectMapper json;
    private final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Pending> latestByJob = new ConcurrentHashMap<>();
    // Short-lived fence only: bounds memory and filters delayed in-flight updates.
    private final ConcurrentMap<Long, Long> terminalFenceUntil = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor senders;
    private final java.util.concurrent.ScheduledExecutorService ticker;

    private static final class Client {
        final WebSocketSession session;
        final ConcurrentMap<Long, String> pending = new ConcurrentHashMap<>();
        final AtomicBoolean sending = new AtomicBoolean();
        Client(WebSocketSession session) { this.session = session; }
    }
    private record Pending(String payload, boolean terminal) {}

    @Autowired
    public ProgressWebSocketHandler(SyncProgressService progress, ObjectMapper json) {
        this(progress, json, SENDERS);
    }

    ProgressWebSocketHandler(SyncProgressService progress, ObjectMapper json, int senderThreads) {
        this.progress = progress;
        this.json = json;
        this.senders = new ThreadPoolExecutor(senderThreads, senderThreads, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());
        this.ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::broadcastLatest, 500, 500, TimeUnit.MILLISECONDS);
    }

    @Override public List<String> getSubProtocols() { return List.of("fipely-progress"); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 1_000, 64 * 1024);
        Client client = new Client(safe);
        clients.put(session.getId(), client);
        try {
            safe.sendMessage(new TextMessage(json.writeValueAsString(Map.of(
                    "type", "snapshot", "data", progress.list(null, 100)))));
        } catch (IOException | RuntimeException e) {
            disconnect(session.getId(), client, "snapshot failed", e);
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        Client client = clients.remove(session.getId());
        if (client != null) disconnect(session.getId(), client, "transport error", error);
    }

    @EventListener
    public synchronized void changed(SyncProgressService.Changed change) {
        if (clients.isEmpty()) return;
        long now = System.nanoTime();
        terminalFenceUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        SyncProgress p = change.progress();
        boolean terminal = !p.status().equals("queued") && !p.status().equals("running");
        if (terminal) terminalFenceUntil.put(p.jobId(), now + TimeUnit.SECONDS.toNanos(2));
        else if (terminalFenceUntil.getOrDefault(p.jobId(), 0L) > now) return;
        Pending existing = latestByJob.get(p.jobId());
        // A late non-terminal event must not replace a terminal state awaiting delivery.
        if (existing != null && existing.terminal() && !terminal) return;
        latestByJob.put(p.jobId(), new Pending(
                json.writeValueAsString(Map.of("type", "progress", "data", p)), terminal));
        if (terminal) broadcastLatest();
    }

    private void broadcastLatest() {
        long now = System.nanoTime();
        terminalFenceUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (latestByJob.isEmpty()) return;
        if (clients.isEmpty()) { latestByJob.clear(); return; }
        Map<Long, Pending> batch = Map.copyOf(latestByJob);
        batch.forEach((job, pending) -> latestByJob.remove(job, pending));
        clients.forEach((id, client) -> {
            batch.forEach((job, pending) -> client.pending.put(job, pending.payload()));
            if (client.pending.size() > MAX_PENDING_JOBS_PER_CLIENT) {
                disconnect(id, client, "client progress buffer capacity exhausted", null);
                return;
            }
            dispatch(id, client);
        });
    }

    private void dispatch(String id, Client client) {
        if (client.pending.isEmpty() || !client.sending.compareAndSet(false, true)) return;
        try { senders.execute(() -> sendPending(id, client)); }
        catch (RejectedExecutionException saturated) {
            client.sending.set(false);
            disconnect(id, client, "slow-client sender capacity exhausted", saturated);
        }
    }

    private void sendPending(String id, Client client) {
        try {
            while (!client.pending.isEmpty()) {
                var batch = Map.copyOf(client.pending);
                batch.forEach((job, payload) -> {
                    if (!client.pending.remove(job, payload)) return;
                    try {
                        if (client.session.isOpen()) client.session.sendMessage(new TextMessage(payload));
                        else disconnect(id, client, "session closed", null);
                    } catch (IOException | RuntimeException e) { disconnect(id, client, "send failed", e); }
                });
            }
        } finally {
            client.sending.set(false);
            if (!client.pending.isEmpty() && clients.get(id) == client) dispatch(id, client);
        }
    }

    private void disconnect(String id, Client client, String reason, Throwable error) {
        clients.remove(id, client);
        if (error != null) log.warn("Disconnecting WebSocket progress session {}: {}", id, reason, error);
        try { if (client.session.isOpen()) client.session.close(CloseStatus.SERVER_ERROR); }
        catch (IOException e) { log.debug("Failed to close WebSocket session {}", id, e); }
    }

    @Override
    public void destroy() {
        ticker.shutdownNow();
        senders.shutdown();
        try { if (!senders.awaitTermination(2, TimeUnit.SECONDS)) senders.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); senders.shutdownNow(); }
    }
}
