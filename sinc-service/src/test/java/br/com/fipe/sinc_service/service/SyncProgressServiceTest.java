package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import br.com.fipe.sinc_service.dto.SyncProgress;
import br.com.fipe.sinc_service.repository.SyncJobRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SyncProgressServiceTest {
    @Test
    void failedAtomicWriteRetainsCountersForExactlyOneSuccessfulRetry() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("DB down")).doNothing()
                .when(repository).progressDelta(52, 3, 4, 5, 6, 0);
        service.discovered(52, 3, 4, 5);
        service.processedBatch(52, 6, false);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.find(52));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.find(52));
        verify(repository, org.mockito.Mockito.times(2)).progressDelta(52, 3, 4, 5, 6, 0);
        service.shutdown();
    }

    @Test
    void publicationFailureAfterWriteDoesNotRequeueCounters() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(repository.find(53)).thenReturn(Optional.of(job(53, null, "vehicleType", 0, 0, false)));
        doThrow(new IllegalStateException("listener failed")).when(events).publishEvent(
                org.mockito.ArgumentMatchers.any(Object.class));
        SyncProgressService service = new SyncProgressService(repository, events);
        service.processedBatch(53, 7, true);
        service.shutdown();
        verify(repository).progressDelta(53, 0, 0, 0, 7, 7);
    }

    @Test
    void finishFlushesAllCountersBeforeWritingTerminalStatus() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        when(repository.find(54)).thenReturn(Optional.of(job(54, null, "vehicleType", 0, 0, false)));
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        service.discovered(54, 1, 2, 3);
        service.processedBatch(54, 4, true);
        service.finish(54, "completed", null);
        var order = inOrder(repository);
        order.verify(repository).progressDelta(54, 1, 2, 3, 4, 4);
        order.verify(repository).finish(54, "completed", null);
        service.shutdown();
    }

    @Test
    void finishFlushIncludesConcurrentProcessedUpdates() throws Exception {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        when(repository.find(55)).thenReturn(Optional.of(job(55, null, "vehicleType", 0, 0, false)));
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) futures.add(pool.submit(() -> {
                for (int i = 0; i < 250; i++) service.processed(55, true);
            }));
            for (var future : futures) future.get();
            service.finish(55, "completed", null);
            verify(repository).progressDelta(55, 0, 0, 0, 2_000, 2_000);
            verify(repository).finish(55, "completed", null);
        } finally { pool.shutdownNow(); service.shutdown(); }
    }

    @Test
    void batchesThousandsOfProcessedVariantsUntilReadOrShutdown() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        for (int i = 0; i < 5_000; i++) service.processed(41, i % 2 == 0);
        verify(repository, never()).progressDelta(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        service.shutdown();
        verify(repository).progressDelta(41, 0, 0, 0, 5_000, 2_500);
    }

    @Test
    void aggregatesThreeVehicleTypesWithoutReportingIncompleteTotalAsFinal() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        when(repository.find(99)).thenReturn(Optional.of(job(99, null, "period", 0, 0, false)));
        when(repository.children(99)).thenReturn(List.of(
                job(100, 99L, "vehicleType", 10, 7, false),
                job(101, 99L, "vehicleType", 4, 4, true),
                job(102, 99L, "vehicleType", 6, 3, false)));

        SyncProgress result = service.find(99);

        assertEquals(20, result.vehiclesDiscovered());
        assertEquals(14, result.vehiclesProcessed());
        assertEquals(6, result.remainingKnown());
        assertFalse(result.discoveryComplete());
        assertEquals(3, result.children().size());
    }

    @Test
    void resumedParentExcludesArchivedRestartChildrenFromTotalsAndCompletion() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        when(repository.find(199)).thenReturn(Optional.of(job(199, null, "period", 0, 0, false)));
        SyncProgress archived = job(190, 199L, "vehicleType", 90, 90, true);
        archived = new SyncProgress(archived.jobId(), archived.parentJobId(), archived.scope(), archived.referenceMonth(),
                archived.vehicleType(), archived.brandCode(), archived.modelCode(), archived.modelYear(), archived.fuelCode(),
                archived.refreshOldRecords(), archived.includeVariants(), "failed", "failed", null, null,
                0, 0, 90, 90, 90, 0, 0, true, archived.startedAt(), archived.updatedAt(), archived.finishedAt(),
                "Child job restarted; parent will resume safely", List.of());
        when(repository.children(199)).thenReturn(List.of(archived,
                job(200, 199L, "vehicleType", 2, 2, true),
                job(201, 199L, "vehicleType", 3, 3, true),
                job(202, 199L, "vehicleType", 4, 4, true)));

        SyncProgress result = service.find(199);

        assertEquals(9, result.vehiclesDiscovered());
        assertEquals(3, result.children().size());
        assertTrue(result.discoveryComplete());
        service.shutdown();
    }

    @Test
    void catalogForOneTypeReportsDiscoveryCompleteAfterItsOnlyChild() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        SyncProgressService service = new SyncProgressService(repository, mock(ApplicationEventPublisher.class));
        SyncProgress parent = job(77, null, "catalog", 0, 0, false);
        parent = new SyncProgress(parent.jobId(), null, "catalog", parent.referenceMonth(), 2,
                null, null, null, null, false, true, "completed", "completed", null, null,
                0, 0, 0, 0, 0, 0, 0, false, parent.startedAt(), parent.updatedAt(),
                parent.finishedAt(), null, List.of());
        when(repository.find(77)).thenReturn(Optional.of(parent));
        when(repository.children(77)).thenReturn(List.of(job(78, 77L, "catalogType", 3, 3, true)));

        SyncProgress result = service.find(77);

        assertTrue(result.includeVariants());
        assertTrue(result.discoveryComplete());
        assertEquals(3, result.vehiclesDiscovered());
        assertEquals(1, result.children().size());
    }

    private static SyncProgress job(long id, Long parent, String scope,
                                     long discovered, long processed, boolean complete) {
        Instant now = Instant.now();
        return new SyncProgress(id, parent, scope, LocalDate.of(2026, 7, 1), null,
                null, null, null, null, false, false, "running", "prices", null, null,
                1, 2, discovered, processed, processed, 0, discovered - processed, complete,
                now, now, null, null, List.of());
    }
}
