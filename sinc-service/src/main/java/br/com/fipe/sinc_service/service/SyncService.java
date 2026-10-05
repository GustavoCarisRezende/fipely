package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.domain.Catalog;
import br.com.fipe.sinc_service.dto.CatalogSyncRequest;
import br.com.fipe.sinc_service.dto.FipeResponses;
import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.dto.SyncResult;
import br.com.fipe.sinc_service.repository.CatalogRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncService implements DisposableBean {
    public enum Scope { PERIOD, BRAND, MODEL, VARIANT }

    private static final Pattern YEAR_FUEL = Pattern.compile("^(\\d+)-(\\d+)$");
    private static final Pattern MONEY = Pattern.compile("^R\\$\\s*([0-9.]+),([0-9]{2})$");
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM/uuuu", Locale.forLanguageTag("pt-BR"));
    private static final DateTimeFormatter INPUT_MONTH = DateTimeFormatter.ofPattern("MM/uuuu");

    private final CatalogRepository repository;
    private final FipeClient client;
    private final SyncProgressService progress;
    private final SingleInstanceJobGuard jobGuard;
    private final int ageDays;
    private final long referencePeriodCacheMinutes;
    private volatile CachedPeriods periodsCache;
    private final Semaphore concurrency;
    // Bounded striped locks prevent overlapping scopes from fetching the same FIPE resource.
    private final Object[] resourceLocks = new Object[256];
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ThreadPoolExecutor dispatcher;
    private final ThreadPoolExecutor periodDispatcher;
    private final int totalQueueWorkers;
    private final int totalQueueCapacity;
    private final ConcurrentMap<String, Long> activeKeys = new ConcurrentHashMap<>();
    private final Set<Long> scheduledIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean accepting = true;
    private final ConcurrentHashMap<String, CompletableFuture<SyncResult>> jobs = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SyncService.class);

    @Autowired
    public SyncService(CatalogRepository repository, FipeClient client, SyncProgressService progress,
                       @Value("${app.sync.min-age-days:365}") int ageDays,
                       @Value("${app.fipe.max-concurrent-syncs:2}") int threads,
                       @Value("${app.fipe.reference-period-cache-minutes:1440}") long cacheMinutes,
                       @Value("${app.sync.queue-workers:2}") int queueWorkers,
                       @Value("${app.sync.queue-capacity:48}") int queueCapacity,
                       SingleInstanceJobGuard jobGuard) {
        if (ageDays < 1 || threads < 1 || cacheMinutes < 1 || queueWorkers < 2 || queueCapacity < 1)
            throw new IllegalArgumentException("Invalid sync configuration");
        this.repository = repository;
        this.client = client;
        this.progress = progress;
        this.jobGuard = jobGuard;
        this.ageDays = ageDays;
        this.referencePeriodCacheMinutes = cacheMinutes;
        this.totalQueueWorkers = queueWorkers;
        this.totalQueueCapacity = queueCapacity;
        this.dispatcher = new ThreadPoolExecutor(Math.max(1, queueWorkers - 1), Math.max(1, queueWorkers - 1), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("sync-dispatch-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.periodDispatcher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("sync-period-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.concurrency = new Semaphore(threads);
        for (int i = 0; i < resourceLocks.length; i++) resourceLocks[i] = new Object();
    }

    SyncService(CatalogRepository repository, FipeClient client, SyncProgressService progress,
                int ageDays, int threads, long cacheMinutes, int queueWorkers, int queueCapacity) {
        this(repository, client, progress, ageDays, threads, cacheMinutes, queueWorkers, queueCapacity, null);
    }

    SyncService(CatalogRepository repository, FipeClient client, SyncProgressService progress,
                int ageDays, int threads, long cacheMinutes) {
        this(repository, client, progress, ageDays, threads, cacheMinutes, 2, 48);
    }

    public SyncResult sync(Scope scope, SyncRequest request, boolean refreshOldRecords) {
        validate(scope, request);
        YearMonth month = parseMonth(request.referenceMonth());
        String key = scope + ":" + month + ":" + request.vehicleType() + ":" + request.brandCode()
                + ":" + request.modelCode() + ":" + request.modelYear() + ":" + request.fuelCode()
                + ":" + refreshOldRecords;
        // Coordinators do not consume a worker slot; child vehicle-type syncs can run concurrently.
        if (scope == Scope.PERIOD) return await(sharedFuture(key,
                () -> runJob(scope, month, request, refreshOldRecords), false));
        return await(sharedFuture(key, () -> runJob(scope, month, request, refreshOldRecords), true));
    }

    public record Accepted(long jobId, String status) {}

    public Accepted submit(Scope scope, SyncRequest request, boolean refresh) {
        validate(scope, request);
        YearMonth month = parseMonth(request.referenceMonth());
        String key = scope + ":" + month + ":" + request.vehicleType() + ":" + request.brandCode()
                + ":" + request.modelCode() + ":" + request.modelYear() + ":" + request.fuelCode() + ":" + refresh;
        return submit(key, () -> progress.enqueue(scope.name().toLowerCase(Locale.ROOT), request,
                month.atDay(1), refresh), id -> runExistingJob(scope, month, request, refresh, id),
                () -> progress.activeEquivalent(scope.name().toLowerCase(Locale.ROOT), month.atDay(1),
                        request.vehicleType(), request.brandCode(), request.modelCode(), request.modelYear(),
                        request.fuelCode(), refresh, false));
    }

    public Accepted submitCatalog(CatalogSyncRequest body, boolean refresh) {
        CatalogSyncRequest request = body == null ? new CatalogSyncRequest(null, null, null) : body;
        if (request.vehicleType() != null && (request.vehicleType() < 1 || request.vehicleType() > 3))
            throw new IllegalArgumentException("vehicleType must be 1..3");
        YearMonth month;
        if (request.referenceMonth() == null || request.referenceMonth().isBlank()) {
            // Never make the HTTP request wait on the external FIPE period endpoint.
            month = repository.localMonths().stream().map(YearMonth::from).max(YearMonth::compareTo)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "No local reference month; provide referenceMonth explicitly"));
            request = new CatalogSyncRequest(month.toString(), request.vehicleType(), request.includeVariants());
        } else month = parseMonth(request.referenceMonth());
        String key = "catalog:" + month + ":" + request.vehicleType() + ":" + request.variantsRequested() + ":" + refresh;
        YearMonth resolvedMonth = month;
        CatalogSyncRequest resolvedRequest = request;
        return submit(key, () -> progress.enqueueCatalog(resolvedRequest, resolvedMonth.atDay(1), refresh),
                id -> runExistingCatalog(resolvedRequest, resolvedMonth, refresh, id),
                () -> progress.activeEquivalent("catalog", resolvedMonth.atDay(1), resolvedRequest.vehicleType(),
                        null, null, null, null, refresh, resolvedRequest.variantsRequested()));
    }

    private synchronized Accepted submit(String key, java.util.function.LongSupplier create,
                                         java.util.function.LongConsumer work,
                                         Supplier<java.util.Optional<Long>> activeLookup) {
        if (!accepting) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service is stopping");
        Long existing = activeKeys.get(key);
        if (existing != null) return new Accepted(existing, progress.find(existing).status());
        var equivalent = activeLookup.get();
        if (equivalent.isPresent()) {
            long existingId = equivalent.get();
            var current = progress.find(existingId);
            if (current.status().equals("queued")) dispatchExisting(current);
            return new Accepted(existingId, current.status());
        }
        boolean broad = key.startsWith("PERIOD:") || key.startsWith("catalog:");
        if (!hasCapacity(broad))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sync queue is full");
        long id = create.getAsLong(); // durable before the request can receive 202
        if (!dispatch(key, id, work, broad)) {
            progress.finish(id, "failed", "Sync queue is full");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sync queue is full");
        }
        return new Accepted(id, "queued");
    }

    private synchronized boolean dispatch(String key, long id, java.util.function.LongConsumer work, boolean broad) {
        if (!scheduledIds.add(id)) return true;
        activeKeys.put(key, id);
        try {
            (broad ? periodDispatcher : dispatcher).execute(() -> {
                try { work.accept(id); }
                catch (RuntimeException failure) {
                    logWorkerFailure(id, failure);
                    try { progress.requeue(id, "Worker failed before terminal state; scheduled for retry"); }
                    catch (RuntimeException persistFailure) { failure.addSuppressed(persistFailure); logWorkerFailure(id, failure); }
                } finally {
                    scheduledIds.remove(id);
                    activeKeys.remove(key, id);
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            activeKeys.remove(key, id);
            scheduledIds.remove(id);
            return false;
        }
    }

    private void dispatchExisting(br.com.fipe.sinc_service.dto.SyncProgress job) {
        String key = key(job);
        java.util.function.LongConsumer work;
        if (job.scope().equals("catalog")) {
            var request = new CatalogSyncRequest(job.referenceMonth().toString(), job.vehicleType(), job.includeVariants());
            work = id -> runExistingCatalog(request, YearMonth.from(job.referenceMonth()), job.refreshOldRecords(), id);
        } else {
            Scope scope = Scope.valueOf(job.scope().toUpperCase(Locale.ROOT));
            var request = new SyncRequest(job.referenceMonth().toString(), job.vehicleType(), job.brandCode(),
                    job.modelCode(), job.modelYear(), job.fuelCode());
            work = id -> runExistingJob(scope, YearMonth.from(job.referenceMonth()), request, job.refreshOldRecords(), id);
        }
        dispatch(key, job.jobId(), work, job.scope().equals("period") || job.scope().equals("catalog"));
    }

    private static String key(br.com.fipe.sinc_service.dto.SyncProgress job) {
        if (job.scope().equals("catalog")) return "catalog:" + job.referenceMonth() + ":" + job.vehicleType()
                + ":" + job.includeVariants() + ":" + job.refreshOldRecords();
        return Scope.valueOf(job.scope().toUpperCase(Locale.ROOT)) + ":" + job.referenceMonth() + ":"
                + job.vehicleType() + ":" + job.brandCode() + ":" + job.modelCode() + ":" + job.modelYear()
                + ":" + job.fuelCode() + ":" + job.refreshOldRecords();
    }

    private static void logWorkerFailure(long id, RuntimeException error) {
        log.error("Sync worker failed for job {} ({})", id, error.getClass().getSimpleName());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public synchronized void resumeQueuedJobs() {
        if (!accepting || recovery.isShutdown()) return;
        try { recovery.scheduleWithFixedDelay(this::pumpRecoverySafely, 0, 100, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) { /* shutdown won the race */ }
    }

    private void pumpRecoverySafely() {
        if (!accepting) return;
        try {
            for (var job : progress.queued(100)) {
                if (!scheduledIds.contains(job.jobId())) {
                    boolean broad = job.scope().equals("period") || job.scope().equals("catalog");
                    if (!hasCapacity(broad)) return;
                    dispatchExisting(job);
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not dispatch persisted sync backlog", e);
        }
    }

    private boolean hasCapacity(boolean broad) {
        int active = dispatcher.getActiveCount() + periodDispatcher.getActiveCount();
        int queued = dispatcher.getQueue().size() + periodDispatcher.getQueue().size();
        ThreadPoolExecutor lane = broad ? periodDispatcher : dispatcher;
        return active + queued < totalQueueWorkers + totalQueueCapacity
                && (lane.getActiveCount() < lane.getMaximumPoolSize() || lane.getQueue().remainingCapacity() > 0);
    }

    public List<LocalDate> absentPeriods() {
        Set<LocalDate> local = new HashSet<>(repository.localMonths());
        return referencePeriods().stream().map(p -> parseSourceMonth(p.month()))
                .filter(month -> !local.contains(month)).distinct().sorted().toList();
    }

    public SyncResult syncCatalog(CatalogSyncRequest body, boolean refreshOldRecords) {
        CatalogSyncRequest request = body == null ? new CatalogSyncRequest(null, null, null) : body;
        if (request.vehicleType() != null && (request.vehicleType() < 1 || request.vehicleType() > 3))
            throw new IllegalArgumentException("vehicleType must be 1..3");
        YearMonth month = request.referenceMonth() == null || request.referenceMonth().isBlank()
                ? latestPeriod() : parseMonth(request.referenceMonth());
        String key = "catalog:" + month + ":" + request.vehicleType() + ":"
                + request.variantsRequested() + ":" + refreshOldRecords;
        return await(sharedFuture(key, () -> runCatalog(request, month, refreshOldRecords), false));
    }

    private YearMonth latestPeriod() {
        return referencePeriods().stream().map(p -> YearMonth.from(parseSourceMonth(p.month())))
                .max(YearMonth::compareTo)
                .orElseThrow(() -> new IllegalStateException("FIPE did not return any reference periods"));
    }

    private List<FipeResponses.Period> referencePeriods() {
        CachedPeriods cached = periodsCache;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.data();
        synchronized (this) {
            cached = periodsCache;
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.data();
            List<FipeResponses.Period> response = client.periods();
            if (response == null || response.isEmpty())
                throw new IllegalStateException("FIPE did not return any reference periods");
            periodsCache = new CachedPeriods(List.copyOf(response),
                    Instant.now().plusSeconds(referencePeriodCacheMinutes * 60));
            return periodsCache.data();
        }
    }

    private record CachedPeriods(List<FipeResponses.Period> data, Instant expiresAt) {}

    public List<CatalogRepository.IncompletePeriod> incompletePeriods() {
        return repository.incompletePeriods();
    }

    private SyncResult runJob(Scope scope, YearMonth month, SyncRequest request, boolean refresh) {
        long jobId = progress.create(scope.name().toLowerCase(Locale.ROOT), request,
                month.atDay(1), refresh, null, false);
        return runExistingJob(scope, month, request, refresh, jobId);
    }

    private SyncResult runExistingJob(Scope scope, YearMonth month, SyncRequest request, boolean refresh, long jobId) {
        progress.beginRoot(jobId);
        if (scope == Scope.PERIOD) progress.restartChildren(jobId);
        try {
            SyncResult result = scope == Scope.PERIOD ? fullPeriod(month, refresh, jobId)
                    : scoped(scope, month, request, refresh, jobId);
            progress.finish(jobId, "completed", null);
            return result;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                progress.requeue(jobId, "Worker interrupted during shutdown; queued for restart");
                throw e;
            }
            if (scope == Scope.PERIOD) progress.failQueuedChildren(jobId, e.getMessage());
            progress.finish(jobId, "failed", e.getMessage());
            throw e;
        }
    }

    private SyncResult runCatalog(CatalogSyncRequest request, YearMonth month, boolean refresh) {
        long parentId = progress.createCatalog("catalog", request, month.atDay(1),
                request.vehicleType(), refresh, null, false);
        return runExistingCatalog(request, month, refresh, parentId);
    }

    private SyncResult runExistingCatalog(CatalogSyncRequest request, YearMonth month, boolean refresh, long parentId) {
        progress.beginRoot(parentId);
        progress.restartChildren(parentId);
        try {
            Catalog.Period period = period(month);
            progress.activity(parentId, "vehicleTypes", null, null, null);
            List<CompletableFuture<SyncResult>> children = new ArrayList<>();
            for (int type = 1; type <= 3; type++) {
                if (request.vehicleType() != null && request.vehicleType() != type) continue;
                int vehicleType = type;
                long childId = progress.createCatalog("catalogType", request, month.atDay(1),
                        type, refresh, parentId, true);
                children.add(sharedFuture("catalogType:" + childId,
                        () -> syncCatalogType(period, vehicleType, request.variantsRequested(), refresh, childId), true));
            }
            int brands = 0, models = 0, variants = 0;
            RuntimeException firstError = null;
            for (CompletableFuture<SyncResult> future : children) {
                try {
                    SyncResult result = await(future);
                    brands += result.brands();
                    models += result.models();
                    variants += result.variants();
                } catch (RuntimeException e) {
                    if (firstError == null) firstError = e;
                }
            }
            if (firstError != null) throw firstError;
            progress.finish(parentId, "completed", null);
            return new SyncResult(parentId, month.toString(), "catalog", children.size(),
                    brands, models, variants, 0);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                progress.requeue(parentId, "Worker interrupted during shutdown; queued for restart");
                throw e;
            }
            progress.failQueuedChildren(parentId, e.getMessage());
            progress.finish(parentId, "failed", e.getMessage());
            throw e;
        }
    }

    private SyncResult syncCatalogType(Catalog.Period period, int type, boolean includeVariants,
                                       boolean refresh, long jobId) {
        progress.start(jobId);
        Counters count = new Counters();
        try {
            List<Catalog.Brand> availableBrands = brands(period, type, refresh, count, jobId);
            progress.discovered(jobId, availableBrands.size(), 0, 0);
            for (Catalog.Brand brand : availableBrands) {
                List<Catalog.Model> availableModels = models(period, brand, refresh, count, jobId);
                progress.discovered(jobId, 0, availableModels.size(), 0);
                if (!includeVariants) continue;
                for (Catalog.Model model : availableModels) {
                    int before = count.variants;
                    List<Catalog.Variant> years = variants(period, brand, model, refresh, count, jobId);
                    progress.discovered(jobId, 0, 0, years.size());
                    int fetched = Math.min(years.size(), count.variants - before);
                    progress.processedBatch(jobId, fetched, true);
                    progress.processedBatch(jobId, years.size() - fetched, false);
                }
            }
            progress.finish(jobId, "completed", null);
            return count.result(jobId, period.month(), "catalogType", 1);
        } catch (RuntimeException e) {
            progress.finish(jobId, "failed", e.getMessage());
            throw e;
        }
    }

    private SyncResult fullPeriod(YearMonth month, boolean refresh, long parentJobId) {
        Catalog.Period period = period(month);
        progress.activity(parentJobId, "vehicleTypes", null, null, null);
        List<CompletableFuture<SyncResult>> futures = new ArrayList<>();
        for (int type = 1; type <= 3; type++) {
            int vehicleType = type;
            SyncRequest child = new SyncRequest(month.toString(), type, null, null, null, null);
            long childJobId = progress.create("vehicleType", child, month.atDay(1),
                    refresh, parentJobId, true);
            futures.add(sharedFuture("type:" + childJobId,
                    () -> syncType(period, vehicleType, refresh, childJobId), true));
        }
        int brands = 0, models = 0, variants = 0, prices = 0;
        RuntimeException firstError = null;
        for (CompletableFuture<SyncResult> future : futures) {
            try {
                SyncResult result = await(future);
                brands += result.brands();
                models += result.models();
                variants += result.variants();
                prices += result.prices();
            } catch (RuntimeException e) {
                if (firstError == null) firstError = e;
            }
        }
        if (firstError != null) throw firstError;
        return new SyncResult(parentJobId, month.toString(), "period", 3, brands, models, variants, prices);
    }

    private SyncResult syncType(Catalog.Period period, int type, boolean refresh, long jobId) {
        progress.start(jobId);
        Long run = null;
        Counters count = new Counters();
        try {
            run = repository.startRun(period.id(), type);
            List<Catalog.Brand> availableBrands = brands(period, type, refresh, count, jobId);
            progress.discovered(jobId, availableBrands.size(), 0, 0);
            for (Catalog.Brand brand : availableBrands) {
                List<Catalog.Model> availableModels = models(period, brand, refresh, count, jobId);
                progress.discovered(jobId, 0, availableModels.size(), 0);
                for (Catalog.Model model : availableModels) {
                    List<Catalog.Variant> availableVariants = variants(period, brand, model, refresh, count, jobId);
                    progress.discovered(jobId, 0, 0, availableVariants.size());
                    priceModel(period, brand, model, availableVariants, refresh, count, jobId);
                }
            }
            repository.finishRun(run, "completed", null);
            progress.finish(jobId, "completed", null);
            return count.result(jobId, period.month(), "vehicleType", 1);
        } catch (RuntimeException e) {
            try {
                if (run != null) repository.finishRun(run, "failed", e.getMessage());
            } finally {
                progress.finish(jobId, "failed", e.getMessage());
            }
            throw e;
        }
    }

    private SyncResult scoped(Scope scope, YearMonth month, SyncRequest request, boolean refresh, long jobId) {
        if (scope == Scope.VARIANT && !due(repository.existingPriceStamp(month.atDay(1),
                request.vehicleType(), request.brandCode(), request.modelCode(),
                request.modelYear(), request.fuelCode()), refresh)) {
            progress.activity(jobId, "cached", request.vehicleType(), request.brandCode(),
                    String.valueOf(request.modelCode()));
            progress.discovered(jobId, 1, 1, 1);
            progress.discoveryComplete(jobId);
            progress.processed(jobId, false);
            return new Counters().result(jobId, month.atDay(1), "variant", 1);
        }
        Catalog.Period period = period(month);
        Counters count = new Counters();
        Catalog.Brand brand = brands(period, request.vehicleType(), refresh, count, jobId).stream()
                .filter(b -> b.code().equals(request.brandCode())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Brand not listed in this period"));
        progress.discovered(jobId, 1, 0, 0);
        if (scope == Scope.BRAND) {
            List<Catalog.Model> availableModels = models(period, brand, refresh, count, jobId);
            progress.discovered(jobId, 0, availableModels.size(), 0);
            for (Catalog.Model model : availableModels) {
                List<Catalog.Variant> availableVariants = variants(period, brand, model, refresh, count, jobId);
                progress.discovered(jobId, 0, 0, availableVariants.size());
                priceModel(period, brand, model, availableVariants, refresh, count, jobId);
            }
        } else {
            Catalog.Model model = models(period, brand, refresh, count, jobId).stream()
                    .filter(m -> m.code() == request.modelCode()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Model not listed in this period"));
            progress.discovered(jobId, 0, 1, 0);
            List<Catalog.Variant> years = variants(period, brand, model, refresh, count, jobId);
            if (scope == Scope.MODEL) {
                progress.discovered(jobId, 0, 0, years.size());
                progress.discoveryComplete(jobId);
                priceModel(period, brand, model, years, refresh, count, jobId);
            } else {
                Catalog.Variant variant = years.stream()
                        .filter(v -> v.year() == request.modelYear() && v.fuelCode().equals(request.fuelCode()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Variant not listed in this period"));
                progress.discovered(jobId, 0, 0, 1);
                progress.discoveryComplete(jobId);
                price(period, brand, model, variant, refresh, count, jobId);
            }
        }
        return count.result(jobId, period.month(), scope.name().toLowerCase(Locale.ROOT), 1);
    }

    private Catalog.Period period(YearMonth month) {
        return repository.period(month.atDay(1)).orElseGet(() -> referencePeriods().stream()
                .filter(p -> parseSourceMonth(p.month()).equals(month.atDay(1)))
                .findFirst()
                .map(p -> repository.savePeriod(p.code(), p.month(), month.atDay(1)))
                .orElseThrow(() -> new IllegalArgumentException("Reference month unavailable at FIPE: " + month)));
    }

    private List<Catalog.Brand> brands(Catalog.Period period, int type, boolean refresh,
                                        Counters count, long jobId) {
        progress.activity(jobId, "brands", type, null, null);
        synchronized (resourceLock("brands", period.id(), type)) {
            if (due(repository.brandListStamp(period.id(), type), refresh)) {
                FipeClient.Response<List<FipeResponses.Option>> response = client.brands(period.code(), type);
                if (response.data() == null) throw new IllegalStateException("Empty FIPE brand response");
                Instant now = Instant.now();
                List<CatalogRepository.BrandInput> inputs = response.data().stream()
                        .map(option -> new CatalogRepository.BrandInput(option.value(), option.label())).toList();
                List<Catalog.Brand> saved = repository.persistBrandResponse(period.id(), type, inputs, response.raw(), now);
                count.brands += saved.size();
            }
            return repository.brands(period.id(), type);
        }
    }

    private List<Catalog.Model> models(Catalog.Period period, Catalog.Brand brand,
                                        boolean refresh, Counters count, long jobId) {
        progress.activity(jobId, "models", brand.type(), brand.code(), null);
        synchronized (resourceLock("models", period.id(), brand.id())) {
            if (due(repository.modelListStamp(period.id(), brand.id()), refresh)) {
                FipeClient.Response<FipeResponses.Models> response =
                        client.models(period.code(), brand.type(), brand.code());
                if (response.data() == null || response.data().models() == null || response.data().years() == null) {
                    throw new IllegalStateException("Invalid FIPE model response");
                }
                Instant now = Instant.now();
                List<CatalogRepository.ModelInput> inputs = response.data().models().stream()
                        .map(option -> new CatalogRepository.ModelInput(option.value(), option.label())).toList();
                List<Catalog.Model> saved = repository.persistModelResponse(period.id(), brand.id(), inputs,
                        response.raw(), now);
                count.models += saved.size();
            }
            return repository.models(period.id(), brand.id());
        }
    }

    private List<Catalog.Variant> variants(Catalog.Period period, Catalog.Brand brand,
                                            Catalog.Model model, boolean refresh, Counters count, long jobId) {
        progress.activity(jobId, "variants", brand.type(), brand.code(), model.name());
        synchronized (resourceLock("years", period.id(), model.id())) {
            if (due(repository.yearListStamp(period.id(), model.id()), refresh)) {
                FipeClient.Response<List<FipeResponses.Option>> response =
                        client.years(period.code(), brand.type(), brand.code(), model.code());
                if (response.data() == null) throw new IllegalStateException("Empty FIPE year response");
                Instant now = Instant.now();
                List<CatalogRepository.VariantInput> inputs = new ArrayList<>();
                for (FipeResponses.Option option : response.data()) {
                    Matcher parsed = YEAR_FUEL.matcher(option.value());
                    if (!parsed.matches()) throw new IllegalStateException("Unknown FIPE year/fuel: " + option.value());
                    int year = Integer.parseInt(parsed.group(1));
                    inputs.add(new CatalogRepository.VariantInput(option.value(), year, parsed.group(2), option.label()));
                }
                List<Catalog.Variant> saved = repository.persistYearResponse(period.id(), model.id(), inputs,
                        response.raw(), now);
                count.variants += saved.size();
            }
            return repository.variants(period.id(), model.id());
        }
    }

    private void price(Catalog.Period period, Catalog.Brand brand, Catalog.Model model,
                       Catalog.Variant variant, boolean refresh, Counters count, long jobId) {
        synchronized (resourceLock("price", period.id(), variant.id())) {
            if (!due(repository.priceStamp(period.id(), variant.id()), refresh)) {
                progress.processed(jobId, false);
                return;
            }
            progress.activity(jobId, "prices", brand.type(), brand.code(), model.name());
            FipeClient.Response<FipeResponses.Price> response = client.price(period.code(), brand.type(),
                    brand.code(), model.code(), variant.year(), variant.fuelCode());
            FipeResponses.Price data = response.data();
            if (data == null || data.vehicleType() != brand.type() || data.modelYear() != variant.year()
                    || data.fipeCode() == null || data.fipeCode().isBlank()) {
                throw new IllegalStateException("Inconsistent FIPE price response");
            }
            Matcher money = MONEY.matcher(data.value() == null ? "" : data.value());
            if (!money.matches()) throw new IllegalStateException("Unknown FIPE monetary format: " + data.value());
            BigDecimal value = new BigDecimal(money.group(1).replace(".", "") + "." + money.group(2));
            repository.savePrice(period.id(), variant.id(), value, data.fipeCode(), response.raw(), Instant.now());
            count.prices++;
            progress.processed(jobId, true);
        }
    }

    private void priceModel(Catalog.Period period, Catalog.Brand brand, Catalog.Model model,
                            List<Catalog.Variant> variants, boolean refresh, Counters count, long jobId) {
        if (variants.isEmpty()) return;
        Set<Long> fresh = repository.freshPriceVariantIds(period.id(), model.id(),
                Instant.now().minusSeconds(86400L * ageDays), refresh);
        long skipped = variants.stream().filter(v -> fresh.contains(v.id())).count();
        progress.processedBatch(jobId, skipped, false);
        for (Catalog.Variant variant : variants) {
            if (!fresh.contains(variant.id())) price(period, brand, model, variant, refresh, count, jobId);
        }
    }

    private Object resourceLock(String kind, long periodId, long id) {
        return resourceLocks[Math.floorMod(java.util.Objects.hash(kind, periodId, id), resourceLocks.length)];
    }

    private boolean due(java.util.Optional<Instant> stamp, boolean refresh) {
        return stamp.isEmpty() || (refresh && !stamp.get().isAfter(Instant.now().minusSeconds(86400L * ageDays)));
    }

    private CompletableFuture<SyncResult> sharedFuture(String key, Supplier<SyncResult> work, boolean limited) {
        CompletableFuture<SyncResult> result = jobs.computeIfAbsent(key, unused ->
                CompletableFuture.supplyAsync(() -> {
                    if (limited) {
                        try {
                            concurrency.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Sync interrupted", e);
                        }
                    }
                    try {
                        return work.get();
                    } finally {
                        if (limited) concurrency.release();
                    }
                }, executor));
        result.whenComplete((value, error) -> jobs.remove(key, result));
        return result;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    private static YearMonth parseMonth(String value) {
        if (value == null) throw new IllegalArgumentException("referenceMonth must be yyyy-MM or MM/yyyy");
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException invalidIso) {
            try {
                return YearMonth.parse(value, INPUT_MONTH);
            } catch (DateTimeParseException invalidBrazilian) {
                throw new IllegalArgumentException("referenceMonth must be yyyy-MM or MM/yyyy", invalidBrazilian);
            }
        }
    }

    private static LocalDate parseSourceMonth(String label) {
        try {
            return YearMonth.parse(label.trim().toLowerCase(Locale.forLanguageTag("pt-BR")), MONTH_LABEL).atDay(1);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unknown FIPE month: " + label, e);
        }
    }

    private static void validate(Scope scope, SyncRequest request) {
        if (request == null) throw new IllegalArgumentException("JSON request body is required");
        parseMonth(request.referenceMonth());
        if (scope == Scope.PERIOD) return;
        if (request.vehicleType() == null || request.vehicleType() < 1 || request.vehicleType() > 3
                || request.brandCode() == null || request.brandCode().isBlank()) {
            throw new IllegalArgumentException("vehicleType (1..3) and brandCode are required");
        }
        if (scope == Scope.BRAND) return;
        if (request.modelCode() == null || request.modelCode() < 0) {
            throw new IllegalArgumentException("modelCode is required");
        }
        if (scope == Scope.VARIANT && (request.modelYear() == null || request.modelYear() < 0
                || request.fuelCode() == null || request.fuelCode().isBlank())) {
            throw new IllegalArgumentException("modelYear and fuelCode are required");
        }
    }

    @Override
    public synchronized void destroy() {
        accepting = false;
        recovery.shutdownNow();
        dispatcher.shutdownNow();
        periodDispatcher.shutdownNow();
        executor.shutdownNow();

        // Do not relinquish the DB ownership lock on a timeout: an interrupt-insensitive
        // worker may still be changing job state. Keep shutdown (and the lock) pending.
        boolean interrupted = awaitTerminationUninterruptibly(recovery);
        interrupted |= awaitTerminationUninterruptibly(dispatcher);
        interrupted |= awaitTerminationUninterruptibly(periodDispatcher);
        interrupted |= awaitTerminationUninterruptibly(executor);

        progress.flushPendingForShutdown();
        for (Long jobId : scheduledIds) {
            try { progress.requeue(jobId, "Service stopping; queued for restart"); }
            catch (RuntimeException e) { log.error("Could not requeue sync job {} during shutdown", jobId); }
        }
        if (jobGuard != null) jobGuard.releaseAfterWorkers();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitTerminationUninterruptibly(ExecutorService service) {
        boolean interrupted = false;
        while (!service.isTerminated()) {
            try { service.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        return interrupted;
    }

    private static final class Counters {
        int brands, models, variants, prices;
        SyncResult result(long jobId, LocalDate month, String scope, int types) {
            return new SyncResult(jobId, YearMonth.from(month).toString(), scope,
                    types, brands, models, variants, prices);
        }
    }
}
