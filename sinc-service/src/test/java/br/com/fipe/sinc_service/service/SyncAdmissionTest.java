package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.repository.CatalogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SyncAdmissionTest {
    private final CatalogRepository repository = mock(CatalogRepository.class);
    private final FipeClient client = mock(FipeClient.class);
    private final SyncProgressService progress = mock(SyncProgressService.class);
    private final SyncService service = new SyncService(repository, client, progress, 365, 2, 1440);

    @AfterEach void close() { service.destroy(); }

    @Test void repeatedActiveRequestReturnsPersistedJobIdAndRunsWithThatId() throws Exception {
        SyncRequest request = new SyncRequest("2026-07", 2, "80", 10378, 2023, "5");
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(progress.enqueue("variant", request, LocalDate.of(2026, 7, 1), false)).thenReturn(77L);
        when(progress.find(77L)).thenReturn(queuedVariant(77L));
        when(repository.existingPriceStamp(LocalDate.of(2026, 7, 1), 2, "80", 10378, 2023, "5"))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                    return Optional.of(Instant.now());
                });
        doAnswer(invocation -> { if (invocation.getArgument(0, Long.class) == 77L) done.countDown(); return null; })
                .when(progress).finish(eq(77L), eq("completed"), isNull());

        var first = service.submit(SyncService.Scope.VARIANT, request, false);
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
        var duplicate = service.submit(SyncService.Scope.VARIANT, request, false);

        assertEquals(77L, first.jobId());
        assertEquals(first.jobId(), duplicate.jobId());
        verify(progress, times(1)).enqueue("variant", request, LocalDate.of(2026, 7, 1), false);
        release.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(done.await(3, TimeUnit.SECONDS));
        verify(progress).beginRoot(77L);
        verify(progress).finish(77L, "completed", null);
    }

    @Test void ownershipLockIsNotReleasedUntilInterruptResistantWorkerHasExited() throws Exception {
        var guard = mock(SingleInstanceJobGuard.class);
        var guarded = new SyncService(repository, client, progress, 365, 2, 1440, 2, 48, guard);
        SyncRequest request = new SyncRequest("2026-07", 2, "80", 10378, 2023, "5");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch destroyed = new CountDownLatch(1);
        when(progress.enqueue("variant", request, LocalDate.of(2026, 7, 1), false)).thenReturn(93L);
        when(repository.existingPriceStamp(LocalDate.of(2026, 7, 1), 2, "80", 10378, 2023, "5"))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    boolean done = false;
                    while (!done) {
                        try { done = release.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* emulate a slow non-interruptible operation */ }
                    }
                    return Optional.of(Instant.now());
                });

        guarded.submit(SyncService.Scope.VARIANT, request, false);
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread shutdown = Thread.ofPlatform().start(() -> {
            guarded.destroy();
            destroyed.countDown();
        });

        try {
            assertFalse(destroyed.await(200, TimeUnit.MILLISECONDS));
            verify(guard, never()).releaseAfterWorkers();
        } finally {
            release.countDown();
        }
        org.junit.jupiter.api.Assertions.assertTrue(destroyed.await(3, TimeUnit.SECONDS));
        verify(progress).flushPendingForShutdown();
        verify(guard).releaseAfterWorkers();
        shutdown.join();
    }

    @Test void recoveryDrainsPersistedBacklogLargerThanExecutorCapacity() throws Exception {
        service.destroy();
        SyncService smallQueue = new SyncService(repository, client, progress, 365, 2, 1440, 2, 1);
        int amount = 55;
        CountDownLatch completed = new CountDownLatch(amount);
        var states = new java.util.concurrent.ConcurrentHashMap<Long, String>();
        var records = new ArrayList<br.com.fipe.sinc_service.dto.SyncProgress>();
        for (long id = 1; id <= amount; id++) {
            states.put(id, "queued");
            records.add(queuedVariant(id));
        }
        when(progress.queued(100)).thenAnswer(invocation -> records.stream()
                .filter(job -> states.get(job.jobId()).equals("queued")).toList());
        doAnswer(invocation -> { states.put(invocation.getArgument(0), "running"); return null; })
                .when(progress).beginRoot(anyLong());
        doAnswer(invocation -> {
            states.put(invocation.getArgument(0), "completed");
            completed.countDown();
            return null;
        }).when(progress).finish(anyLong(), eq("completed"), isNull());
        when(repository.existingPriceStamp(any(), anyInt(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.of(Instant.now()));

        smallQueue.resumeQueuedJobs();

        org.junit.jupiter.api.Assertions.assertTrue(completed.await(10, TimeUnit.SECONDS), states.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(value -> value, java.util.stream.Collectors.counting())).toString());
        assertEquals(amount, states.values().stream().filter("completed"::equals).count());
        smallQueue.destroy();
    }

    @Test void submissionDeduplicatesPersistedQueuedJobAfterProcessRestart() throws Exception {
        SyncRequest request = new SyncRequest("2026-07", 2, "80", 1088, 2023, "5");
        var persisted = queuedVariant(88L);
        var done = new CountDownLatch(1);
        when(progress.activeEquivalent("variant", LocalDate.of(2026, 7, 1), 2, "80", 1088, 2023, "5", false, false))
                .thenReturn(Optional.of(88L));
        when(progress.find(88L)).thenReturn(persisted);
        when(repository.existingPriceStamp(LocalDate.of(2026, 7, 1), 2, "80", 1088, 2023, "5"))
                .thenReturn(Optional.of(Instant.now()));
        doAnswer(invocation -> { done.countDown(); return null; })
                .when(progress).finish(88L, "completed", null);

        var response = service.submit(SyncService.Scope.VARIANT, request, false);

        assertEquals(88L, response.jobId());
        verify(progress, never()).enqueue(anyString(), any(), any(), anyBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(done.await(3, TimeUnit.SECONDS));
        verify(progress).beginRoot(88L);
    }

    @Test void fullQueueReturns503WithoutCreatingAnOrphanJob() throws Exception {
        service.destroy();
        SyncService smallQueue = new SyncService(repository, client, progress, 365, 2, 1440, 2, 1);
        var nextId = new AtomicLong(300);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        when(progress.enqueue(anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> nextId.incrementAndGet());
        when(repository.existingPriceStamp(any(), anyInt(), anyString(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                    return Optional.of(Instant.now());
                });
        doAnswer(invocation -> { finished.countDown(); return null; })
                .when(progress).finish(anyLong(), eq("completed"), isNull());

        var first = smallQueue.submit(SyncService.Scope.VARIANT,
                new SyncRequest("2026-07", 2, "80", 2001, 2023, "5"), false);
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
        var second = smallQueue.submit(SyncService.Scope.VARIANT,
                new SyncRequest("2026-07", 2, "80", 2002, 2023, "5"), false);
        var rejected = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> smallQueue.submit(SyncService.Scope.VARIANT,
                        new SyncRequest("2026-07", 2, "80", 2003, 2023, "5"), false));

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatusCode());
        verify(progress, times(2)).enqueue(anyString(), any(), any(), anyBoolean());
        release.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(finished.await(3, TimeUnit.SECONDS));
        smallQueue.destroy();
    }

    @Test void catalogWithoutLocalMonthDoesNotCallFipeWhileHandlingSubmission() {
        when(repository.localMonths()).thenReturn(List.of());
        var failure = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.submitCatalog(null, false));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        verify(client, never()).periods();
    }

    private static br.com.fipe.sinc_service.dto.SyncProgress queuedVariant(long id) {
        var now = Instant.now();
        return new br.com.fipe.sinc_service.dto.SyncProgress(id, null, "variant", LocalDate.of(2026, 7, 1),
                2, "80", 1000 + (int) id, 2023, "5", false, false, "queued", "waiting", null, null,
                0, 0, 0, 0, 0, 0, 0, false, now, now, null, null, java.util.List.of());
    }
}
