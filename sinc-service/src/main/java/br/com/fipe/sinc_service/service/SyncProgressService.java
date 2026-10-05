package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.dto.CatalogSyncRequest;
import br.com.fipe.sinc_service.dto.SyncProgress;
import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.repository.SyncJobRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SyncProgressService {
    private static final Logger log = LoggerFactory.getLogger(SyncProgressService.class);
    public record Changed(SyncProgress progress) {}

    private final SyncJobRepository jobs;
    private final ApplicationEventPublisher events;
    private final Map<Long, Counters> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();

    private static final class Counters {
        long brands, models, vehicles, processed, fetched;
        synchronized void discovered(long b, long m, long v) { brands += b; models += m; vehicles += v; }
        synchronized void processed(long n, boolean didFetch) { processed += n; if (didFetch) fetched += n; }
        synchronized long[] take() { long[] v = {brands, models, vehicles, processed, fetched}; brands=models=vehicles=processed=fetched=0; return v; }
    }

    public SyncProgressService(SyncJobRepository jobs, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.events = events;
        flusher.scheduleWithFixedDelay(this::flushAllSafely, 500, 500, TimeUnit.MILLISECONDS);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void recoverJobsInterruptedByRestart() {
        jobs.requeueInterrupted();
    }

    public List<SyncProgress> resumable() { return jobs.resumable(); }
    public List<SyncProgress> queued(int limit) { return jobs.queued(limit); }

    public java.util.Optional<Long> activeEquivalent(String scope, LocalDate month, Integer type, String brand,
            Integer model, Integer year, String fuel, boolean refresh, boolean variants) {
        return jobs.activeEquivalent(scope, month, type, brand, model, year, fuel, refresh, variants);
    }

    public void requeue(long id, String reason) { jobs.requeue(id, reason); }

    public long enqueue(String scope, SyncRequest request, LocalDate month, boolean refresh) {
        return create(scope, request, month, refresh, null, true);
    }

    public long enqueueCatalog(CatalogSyncRequest request, LocalDate month, boolean refresh) {
        return createCatalog("catalog", request, month, request.vehicleType(), refresh, null, true);
    }

    public void restartChildren(long parentId) { jobs.restartChildren(parentId); }

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

    public void beginRoot(long id) {
        jobs.beginRoot(id);
        publish(id);
    }

    public void activity(long id, String phase, Integer type, String brand, String model) {
        jobs.activity(id, phase, type, brand, model);
        publish(id);
    }

    public synchronized void discovered(long id, long brands, long models, long vehicles) {
        if (brands == 0 && models == 0 && vehicles == 0) return;
        pending.computeIfAbsent(id, ignored -> new Counters()).discovered(brands, models, vehicles);
    }

    public void processed(long id, boolean fetched) {
        processedBatch(id, 1, fetched);
    }

    public synchronized void processedBatch(long id, long quantity, boolean fetched) {
        if (quantity == 0) return;
        pending.computeIfAbsent(id, ignored -> new Counters()).processed(quantity, fetched);
    }

    public void discoveryComplete(long id) {
        jobs.discoveryComplete(id);
        publish(id);
    }

    public synchronized void finish(long id, String status, String error) {
        flush(id);
        jobs.finish(id, status, error);
        pending.remove(id);
        publish(id);
    }

    public void failQueuedChildren(long parentId, String error) {
        jobs.failQueuedChildren(parentId, error);
        jobs.children(parentId).stream().filter(job -> job.status().equals("failed"))
                .forEach(job -> events.publishEvent(new Changed(job)));
    }

    public SyncProgress find(long id) {
        flush(id);
        return jobs.find(id).map(this::aggregate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync job not found"));
    }

    public List<SyncProgress> list(String status, int limit) {
        flushAll();
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

    private void flushAllSafely() {
        try { flushAll(); } catch (RuntimeException e) { log.error("Could not flush sync progress counters; pending deltas retained", e); }
    }

    private void flushAll() { for (Long id : pending.keySet()) flush(id); }

    private synchronized void flush(long id) {
        Counters counters = pending.get(id);
        if (counters == null) return;
        long[] d = counters.take();
        if (d[0]+d[1]+d[2]+d[3] == 0) return;
        try {
            jobs.progressDelta(id, d[0], d[1], d[2], d[3], d[4]);
        } catch (RuntimeException e) {
            counters.discovered(d[0], d[1], d[2]);
            synchronized (counters) { counters.processed += d[3]; counters.fetched += d[4]; }
            throw e;
        }
        // Do not requeue for read/event failures: the database delta is committed already.
        try { publish(id); } catch (RuntimeException e) { log.warn("Progress persisted but event publication failed for job {}", id, e); }
    }

    @PreDestroy
    public void shutdown() {
        flusher.shutdown();
        try { if (!flusher.awaitTermination(2, TimeUnit.SECONDS)) flusher.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); flusher.shutdownNow(); }
        flushAll();
    }

    /** Called by SyncService while its ownership lock is still held and workers have stopped. */
    public void flushPendingForShutdown() {
        flushAll();
    }

    private SyncProgress aggregate(SyncProgress job) {
        if (!job.scope().equals("period") && !job.scope().equals("catalog")) return job;
        List<SyncProgress> children = jobs.children(job.jobId()).stream()
                .filter(child -> !restartedArchive(child)).toList();
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

    private static boolean restartedArchive(SyncProgress child) {
        return child.status().equals("failed") && ("Child job restarted; parent will resume safely".equals(child.errorMessage())
                || "Child replaced during parent restart".equals(child.errorMessage()));
    }
}
