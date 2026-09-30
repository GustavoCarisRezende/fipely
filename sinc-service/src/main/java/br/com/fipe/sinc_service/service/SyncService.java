package br.com.fipe.sinc_service.service;

import br.com.fipe.sinc_service.domain.Catalog;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    private final int ageDays;
    private final Semaphore concurrency;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CompletableFuture<SyncResult>> jobs = new ConcurrentHashMap<>();

    public SyncService(CatalogRepository repository, FipeClient client,
                       @Value("${app.sync.min-age-days:365}") int ageDays,
                       @Value("${app.fipe.max-concurrent-syncs:2}") int threads) {
        if (ageDays < 1 || threads < 1) throw new IllegalArgumentException("Invalid sync configuration");
        this.repository = repository;
        this.client = client;
        this.ageDays = ageDays;
        this.concurrency = new Semaphore(threads);
    }

    public SyncResult sync(Scope scope, SyncRequest request, boolean refreshOldRecords) {
        validate(scope, request);
        YearMonth month = parseMonth(request.referenceMonth());
        String key = scope + ":" + month + ":" + request.vehicleType() + ":" + request.brandCode()
                + ":" + request.modelCode() + ":" + request.modelYear() + ":" + request.fuelCode()
                + ":" + refreshOldRecords;
        // Coordinators do not consume a worker slot; child vehicle-type syncs can run concurrently.
        if (scope == Scope.PERIOD) return await(sharedFuture(key,
                () -> fullPeriod(month, refreshOldRecords), false));
        return await(sharedFuture(key, () -> scoped(scope, month, request, refreshOldRecords), true));
    }

    public List<LocalDate> absentPeriods() {
        Set<LocalDate> local = new HashSet<>(repository.localMonths());
        return client.periods().stream().map(p -> parseSourceMonth(p.month()))
                .filter(month -> !local.contains(month)).distinct().sorted().toList();
    }

    public List<CatalogRepository.IncompletePeriod> incompletePeriods() {
        return repository.incompletePeriods();
    }

    private SyncResult fullPeriod(YearMonth month, boolean refresh) {
        Catalog.Period period = period(month);
        List<CompletableFuture<SyncResult>> futures = new ArrayList<>();
        for (int type = 1; type <= 3; type++) {
            int vehicleType = type;
            String key = "type:" + month + ":" + type + ":" + refresh;
            futures.add(sharedFuture(key, () -> syncType(period, vehicleType, refresh), true));
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
        return new SyncResult(month.toString(), "period", 3, brands, models, variants, prices);
    }

    private SyncResult syncType(Catalog.Period period, int type, boolean refresh) {
        long run = repository.startRun(period.id(), type);
        Counters count = new Counters();
        try {
            for (Catalog.Brand brand : brands(period, type, refresh, count)) {
                for (Catalog.Model model : models(period, brand, refresh, count)) {
                    for (Catalog.Variant variant : variants(period, brand, model, refresh, count)) {
                        price(period, brand, model, variant, refresh, count);
                    }
                }
            }
            repository.finishRun(run, "completed", null);
            return count.result(period.month(), "vehicleType", 1);
        } catch (RuntimeException e) {
            repository.finishRun(run, "failed", e.getMessage());
            throw e;
        }
    }

    private SyncResult scoped(Scope scope, YearMonth month, SyncRequest request, boolean refresh) {
        Catalog.Period period = period(month);
        Counters count = new Counters();
        Catalog.Brand brand = brands(period, request.vehicleType(), refresh, count).stream()
                .filter(b -> b.code().equals(request.brandCode())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Brand not listed in this period"));
        if (scope == Scope.BRAND) {
            for (Catalog.Model model : models(period, brand, refresh, count)) {
                for (Catalog.Variant variant : variants(period, brand, model, refresh, count)) {
                    price(period, brand, model, variant, refresh, count);
                }
            }
        } else {
            Catalog.Model model = models(period, brand, refresh, count).stream()
                    .filter(m -> m.code() == request.modelCode()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Model not listed in this period"));
            List<Catalog.Variant> years = variants(period, brand, model, refresh, count);
            if (scope == Scope.MODEL) {
                for (Catalog.Variant variant : years) price(period, brand, model, variant, refresh, count);
            } else {
                Catalog.Variant variant = years.stream()
                        .filter(v -> v.year() == request.modelYear() && v.fuelCode().equals(request.fuelCode()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Variant not listed in this period"));
                price(period, brand, model, variant, refresh, count);
            }
        }
        return count.result(period.month(), scope.name().toLowerCase(Locale.ROOT), 1);
    }

    private Catalog.Period period(YearMonth month) {
        return repository.period(month.atDay(1)).orElseGet(() -> client.periods().stream()
                .filter(p -> parseSourceMonth(p.month()).equals(month.atDay(1)))
                .findFirst()
                .map(p -> repository.savePeriod(p.code(), p.month(), month.atDay(1)))
                .orElseThrow(() -> new IllegalArgumentException("Reference month unavailable at FIPE: " + month)));
    }

    private List<Catalog.Brand> brands(Catalog.Period period, int type, boolean refresh, Counters count) {
        if (due(repository.brandListStamp(period.id(), type), refresh)) {
            FipeClient.Response<List<FipeResponses.Option>> response = client.brands(period.code(), type);
            if (response.data() == null) throw new IllegalStateException("Empty FIPE brand response");
            Instant now = Instant.now();
            for (FipeResponses.Option option : response.data()) {
                Catalog.Brand brand = repository.saveBrand(type, option.value(), option.label(), now);
                repository.savePeriodBrand(period.id(), brand.id(), option.label(), now);
                count.brands++;
            }
            repository.saveBrandList(period.id(), type, response.raw(), now);
        }
        return repository.brands(period.id(), type);
    }

    private List<Catalog.Model> models(Catalog.Period period, Catalog.Brand brand,
                                        boolean refresh, Counters count) {
        if (due(repository.modelListStamp(period.id(), brand.id()), refresh)) {
            FipeClient.Response<FipeResponses.Models> response =
                    client.models(period.code(), brand.type(), brand.code());
            if (response.data() == null || response.data().models() == null || response.data().years() == null) {
                throw new IllegalStateException("Invalid FIPE model response");
            }
            Instant now = Instant.now();
            for (FipeResponses.ModelOption option : response.data().models()) {
                Catalog.Model model = repository.saveModel(brand.id(), option.value(), option.label(), now);
                repository.savePeriodModel(period.id(), brand.id(), model.id(), option.label(), now);
                count.models++;
            }
            repository.saveModelList(period.id(), brand.id(), response.raw(), now);
        }
        return repository.models(period.id(), brand.id());
    }

    private List<Catalog.Variant> variants(Catalog.Period period, Catalog.Brand brand,
                                            Catalog.Model model, boolean refresh, Counters count) {
        if (due(repository.yearListStamp(period.id(), model.id()), refresh)) {
            FipeClient.Response<List<FipeResponses.Option>> response =
                    client.years(period.code(), brand.type(), brand.code(), model.code());
            if (response.data() == null) throw new IllegalStateException("Empty FIPE year response");
            Instant now = Instant.now();
            for (FipeResponses.Option option : response.data()) {
                Matcher parsed = YEAR_FUEL.matcher(option.value());
                if (!parsed.matches()) throw new IllegalStateException("Unknown FIPE year/fuel: " + option.value());
                int year = Integer.parseInt(parsed.group(1));
                Catalog.Variant variant = repository.saveVariant(model.id(), option.value(), year,
                        parsed.group(2), now);
                repository.savePeriodVariant(period.id(), model.id(), variant.id(), option.label(), now);
                count.variants++;
            }
            repository.saveYearList(period.id(), model.id(), response.raw(), now);
        }
        return repository.variants(period.id(), model.id());
    }

    private void price(Catalog.Period period, Catalog.Brand brand, Catalog.Model model,
                       Catalog.Variant variant, boolean refresh, Counters count) {
        if (!due(repository.priceStamp(period.id(), variant.id()), refresh)) return;
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
    public void destroy() {
        executor.shutdownNow();
    }

    private static final class Counters {
        int brands, models, variants, prices;
        SyncResult result(LocalDate month, String scope, int types) {
            return new SyncResult(YearMonth.from(month).toString(), scope,
                    types, brands, models, variants, prices);
        }
    }
}
