package br.com.fipe.sinc_service.controller;

import br.com.fipe.sinc_service.service.SyncProgressService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProgressWebSocketHandler extends TextWebSocketHandler
        implements SubProtocolCapable, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketHandler.class);
    private final SyncProgressService progress;
    private final ObjectMapper json;
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    public ProgressWebSocketHandler(SyncProgressService progress, ObjectMapper json) {
        this.progress = progress;
        this.json = json;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("fipely-progress");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024);
        connections.put(session.getId(), safe);
        try {
            safe.sendMessage(new TextMessage(json.writeValueAsString(Map.of(
                    "type", "snapshot", "data", progress.list(null, 100)))));
        } catch (IOException | RuntimeException e) {
            connections.remove(session.getId());
            log.error("Failed to send WebSocket progress snapshot for session {}", session.getId(), e);
            try {
                safe.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeError) {
                log.warn("Failed to close WebSocket session {} after snapshot error", session.getId(), closeError);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        connections.remove(session.getId());
        log.warn("WebSocket progress transport error for session {}", session.getId(), error);
    }

    @EventListener
    public void changed(SyncProgressService.Changed change) {
        if (connections.isEmpty()) return;
        String payload = json.writeValueAsString(Map.of("type", "progress", "data", change.progress()));
        delivery.execute(() -> connections.forEach((id, session) -> {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
                else connections.remove(id, session);
            } catch (IOException | IllegalStateException e) {
                connections.remove(id, session);
                log.warn("Failed to deliver WebSocket progress to session {}", id, e);
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException closeError) {
                    log.warn("Failed to close WebSocket session {} after delivery error", id, closeError);
                }
            }
        }));
    }

    @Override
    public void destroy() {
        delivery.shutdownNow();
    }
}
