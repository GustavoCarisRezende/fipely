package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.dto.CatalogSyncRequest;
import br.com.fipe.sinc_service.dto.SyncProgress;
import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.repository.SyncJobRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncProgressService {
    public record Changed(SyncProgress progress) {}

    private final SyncJobRepository jobs;
    private final ApplicationEventPublisher events;

    public SyncProgressService(SyncJobRepository jobs, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failJobsInterruptedByRestart() {
        jobs.failInterrupted();
    }

    public long create(String scope, SyncRequest request, LocalDate month, boolean refresh,
                       Long parentId, boolean queued) {
        return create(scope, month, request.vehicleType(), request.brandCode(), request.modelCode(),
                request.modelYear(), request.fuelCode(), refresh, false, parentId, queued);
    }

    public long createCatalog(String scope, CatalogSyncRequest request, LocalDate month,
                              Integer type, boolean refresh, Long parentId, boolean queued) {
        return create(scope, month, type, null, null, null, null,
                refresh, request.variantsRequested(), parentId, queued);
    }

    private long create(String scope, LocalDate month, Integer type, String brand, Integer model,
                        Integer year, String fuel, boolean refresh, boolean includeVariants,
                        Long parentId, boolean queued) {
        long id = jobs.create(scope, month, type, brand, model, year, fuel,
                refresh, includeVariants, parentId, queued ? "queued" : "running");
        publish(id);
        return id;
    }

    public void start(long id) {
        jobs.start(id);
        publish(id);
    }

    public void activity(long id, String phase, Integer type, String brand, String model) {
        jobs.activity(id, phase, type, brand, model);
        publish(id);
    }

    public void discovered(long id, long brands, long models, long vehicles) {
        if (brands == 0 && models == 0 && vehicles == 0) return;
        jobs.discovered(id, brands, models, vehicles);
        publish(id);
    }

    public void processed(long id, boolean fetched) {
        jobs.processed(id, fetched);
        publish(id);
    }

    public void processedBatch(long id, long quantity, boolean fetched) {
        if (quantity == 0) return;
        jobs.processedBatch(id, quantity, fetched);
        publish(id);
    }

    public void discoveryComplete(long id) {
        jobs.discoveryComplete(id);
        publish(id);
    }

    public void finish(long id, String status, String error) {
        jobs.finish(id, status, error);
        publish(id);
    }

    public void failQueuedChildren(long parentId, String error) {
        jobs.failQueuedChildren(parentId, error);
        jobs.children(parentId).stream().filter(job -> job.status().equals("failed"))
                .forEach(job -> events.publishEvent(new Changed(job)));
    }

    public SyncProgress find(long id) {
        return jobs.find(id).map(this::aggregate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync job not found"));
    }

    public List<SyncProgress> list(String status, int limit) {
        if (status != null && !List.of("queued", "running", "completed", "failed").contains(status)) {
            throw new IllegalArgumentException("status must be queued, running, completed or failed");
        }
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        return jobs.topLevel(status, limit).stream().map(this::aggregate).toList();
    }

    private void publish(long id) {
        SyncProgress current = find(id);
        events.publishEvent(new Changed(current));
        if (current.parentJobId() != null) {
            events.publishEvent(new Changed(find(current.parentJobId())));
        }
    }

    private SyncProgress aggregate(SyncProgress job) {
        if (!job.scope().equals("period") && !job.scope().equals("catalog")) return job;
        List<SyncProgress> children = jobs.children(job.jobId());
        long brands = 0, models = 0, discovered = 0, processed = 0, synced = 0, skipped = 0;
        boolean complete = children.size() == (job.scope().equals("period") || job.vehicleType() == null ? 3 : 1);
        for (SyncProgress child : children) {
            brands += child.brandsDiscovered();
            models += child.modelsDiscovered();
            discovered += child.vehiclesDiscovered();
            processed += child.vehiclesProcessed();
            synced += child.vehiclesSynced();
            skipped += child.vehiclesSkipped();
            complete &= child.discoveryComplete();
        }
        return new SyncProgress(job.jobId(), job.parentJobId(), job.scope(), job.referenceMonth(),
                job.vehicleType(), job.brandCode(), job.modelCode(), job.modelYear(), job.fuelCode(),
                job.refreshOldRecords(), job.includeVariants(), job.status(), job.phase(),
                job.currentBrand(), job.currentModel(),
                brands, models, discovered, processed, synced, skipped, discovered - processed,
                complete, job.startedAt(), job.updatedAt(), job.finishedAt(), job.errorMessage(), children);
    }
}
