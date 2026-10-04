package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.fipe.sinc_service.dto.SyncProgress;
import br.com.fipe.sinc_service.repository.SyncJobRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SyncProgressServiceTest {
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
