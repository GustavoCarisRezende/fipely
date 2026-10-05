package br.com.fipe.sinc_service.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;

import br.com.fipe.sinc_service.service.SyncProgressService;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import br.com.fipe.sinc_service.dto.SyncProgress;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class ProgressWebSocketHandlerTest {
    @Test
    void doesNotRetainJobIdsWithoutClientsAndFencesLateRunningAfterTerminal() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenReturn(List.of());
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper(), 2);
        try {
            for (int i = 0; i < 5_000; i++) handler.changed(new SyncProgressService.Changed(job(i + 1, "completed", i)));
            var latest = ProgressWebSocketHandler.class.getDeclaredField("latestByJob");
            latest.setAccessible(true);
            var fence = ProgressWebSocketHandler.class.getDeclaredField("terminalFenceUntil");
            fence.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertTrue(((java.util.Map<?, ?>) latest.get(handler)).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(((java.util.Map<?, ?>) fence.get(handler)).isEmpty());

            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("fenced"); when(session.isOpen()).thenReturn(true);
            ConcurrentLinkedQueue<String> payloads = new ConcurrentLinkedQueue<>();
            doAnswer(call -> { payloads.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                    .when(session).sendMessage(org.mockito.ArgumentMatchers.any());
            handler.afterConnectionEstablished(session);
            payloads.clear();
            handler.changed(new SyncProgressService.Changed(job(77, "completed", 5)));
            handler.changed(new SyncProgressService.Changed(job(77, "running", 4)));
            org.junit.jupiter.api.Assertions.assertTrue(waitFor(() -> payloads.stream().anyMatch(p -> p.contains("completed")), 500));
            Thread.sleep(100);
            org.junit.jupiter.api.Assertions.assertTrue(payloads.stream().noneMatch(p -> p.contains("running")));
        } finally { handler.destroy(); }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return true; Thread.sleep(5); }
        return condition.getAsBoolean();
    }

    @Test
    void coalescesThousandsOfUpdatesAndDeliversTerminalPromptly() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenReturn(List.of());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("coalesced");
        when(session.isOpen()).thenReturn(true);
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper(), 2);
        try {
            handler.afterConnectionEstablished(session);
            AtomicInteger sent = new AtomicInteger();
            doAnswer(call -> { sent.incrementAndGet(); return null; }).when(session).sendMessage(org.mockito.ArgumentMatchers.any());
            for (int i = 0; i < 1_000; i++) handler.changed(new SyncProgressService.Changed(job(91, "running", i)));
            Thread.sleep(750);
            org.junit.jupiter.api.Assertions.assertTrue(sent.get() <= 3, "updates should coalesce per job/window");
            long started = System.nanoTime();
            handler.changed(new SyncProgressService.Changed(job(91, "completed", 1_000)));
            verify(session, timeout(300)).sendMessage(org.mockito.ArgumentMatchers.argThat(m ->
                    m instanceof TextMessage text && text.getPayload().contains("completed")));
            org.junit.jupiter.api.Assertions.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 300);
            handler.changed(new SyncProgressService.Changed(job(91, "running", 1_001)));
            verify(session, timeout(600)).sendMessage(org.mockito.ArgumentMatchers.argThat(m ->
                    m instanceof TextMessage text && text.getPayload().contains("completed")));
        } finally { handler.destroy(); }
    }

    @Test
    void blockedClientDoesNotBlockHealthySession() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenReturn(List.of());
        WebSocketSession slow = mock(WebSocketSession.class), healthy = mock(WebSocketSession.class);
        when(slow.getId()).thenReturn("slow"); when(healthy.getId()).thenReturn("healthy");
        when(slow.isOpen()).thenReturn(true); when(healthy.isOpen()).thenReturn(true);
        CountDownLatch blocked = new CountDownLatch(1), allowSlow = new CountDownLatch(1), healthyReceived = new CountDownLatch(1);
        doAnswer(call -> { blocked.countDown(); allowSlow.await(3, TimeUnit.SECONDS); return null; })
                .when(slow).sendMessage(org.mockito.ArgumentMatchers.any());
        doAnswer(call -> { healthyReceived.countDown(); return null; })
                .when(healthy).sendMessage(org.mockito.ArgumentMatchers.any());
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper(), 2);
        try {
            handler.afterConnectionEstablished(slow); handler.afterConnectionEstablished(healthy);
            handler.changed(new SyncProgressService.Changed(job(92, "completed", 1)));
            org.junit.jupiter.api.Assertions.assertTrue(blocked.await(1, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(healthyReceived.await(1, TimeUnit.SECONDS));
        } finally { allowSlow.countDown(); handler.destroy(); }
    }

    private static SyncProgress job(long id, String status, long processed) {
        Instant now = Instant.now();
        return new SyncProgress(id, null, "vehicleType", LocalDate.of(2026, 7, 1), 1,
                null, null, null, null, false, false, status, status, null, null,
                0, 0, processed, processed, processed, 0, 0, true, now, now,
                status.equals("completed") ? now : null, null, List.of());
    }

    @Test
    void sendsInitialSnapshotAfterConnection() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        when(progress.list(null, 100)).thenReturn(List.of());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session");
        when(session.isOpen()).thenReturn(true);
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
        when(session.isOpen()).thenReturn(true);
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(progress, new ObjectMapper());
        try {
            handler.afterConnectionEstablished(session);
            verify(session).close(CloseStatus.SERVER_ERROR);
        } finally {
            handler.destroy();
        }
    }
}
