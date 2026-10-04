package br.com.fipe.sinc_service.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.fipe.sinc_service.service.SyncProgressService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class ProgressWebSocketHandlerTest {
    @Test
    void sendsInitialSnapshotAfterConnection() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenReturn(List.of());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session");
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper());
        try {
            handler.afterConnectionEstablished(session);
            ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
            verify(session).sendMessage(message.capture());
            assertTrue(message.getValue().getPayload().contains("\"type\":\"snapshot\""));
        } finally {
            handler.destroy();
        }
    }

    @Test
    void closesWithServerErrorWhenSnapshotCannotBeRead() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenThrow(new IllegalStateException("Database unavailable"));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session");
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper());
        try {
            handler.afterConnectionEstablished(session);
            verify(session).close(CloseStatus.SERVER_ERROR);
        } finally {
            handler.destroy();
        }
    }
}
