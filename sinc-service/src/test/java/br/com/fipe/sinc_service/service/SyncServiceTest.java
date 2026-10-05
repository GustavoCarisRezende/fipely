package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.fipe.sinc_service.domain.Catalog;
import br.com.fipe.sinc_service.dto.CatalogSyncRequest;
import br.com.fipe.sinc_service.dto.FipeResponses;
import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.dto.SyncResult;
import br.com.fipe.sinc_service.repository.CatalogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SyncServiceTest {
    private CatalogRepository repository;
    private FipeClient client;
    private SyncProgressService progress;
    private SyncService service;
    private SyncRequest request;

    @BeforeEach
    void setup() {
        repository = mock(CatalogRepository.class);
        client = mock(FipeClient.class);
        progress = mock(SyncProgressService.class);
        service = new SyncService(repository, client, progress, 365, 2, 1440);
        request = new SyncRequest("2026-07", 2, "80", 10378, 2023, "5");
        Catalog.Period period = new Catalog.Period(1, 335, LocalDate.of(2026, 7, 1));
        Catalog.Brand brand = new Catalog.Brand(2, 2, "80", "HONDA", Instant.now());
        Catalog.Model model = new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now());
        Catalog.Variant variant = new Catalog.Variant(4, 3, "2023-5", 2023, "5", Instant.now());
        when(repository.period(period.month())).thenReturn(Optional.of(period));
        when(repository.brandListStamp(1, 2)).thenReturn(Optional.of(Instant.now()));
        when(repository.modelListStamp(1, 2)).thenReturn(Optional.of(Instant.now()));
        when(repository.yearListStamp(1, 3)).thenReturn(Optional.of(Instant.now()));
        when(repository.brands(1, 2)).thenReturn(List.of(brand));
        when(repository.models(1, 2)).thenReturn(List.of(model));
        when(repository.variants(1, 3)).thenReturn(List.of(variant));
        when(repository.freshPriceVariantIds(Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(java.util.Set.of());
        when(repository.persistBrandResponse(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyList(), Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(new Catalog.Brand(2, 2, "80", "HONDA", Instant.now())));
        when(repository.persistModelResponse(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList(), Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now())));
        when(repository.persistYearResponse(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList(), Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(new Catalog.Variant(4, 3, "2023-5", 2023, "5", Instant.now())));
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @Test
    void falseSkipsStoredPriceAndTrueRefetchesOnlyOldPrice() {
        when(repository.priceStamp(1, 4)).thenReturn(Optional.of(Instant.parse("2020-01-01T00:00:00Z")));
        when(client.price(335, 2, "80", 10378, 2023, "5")).thenReturn(price());

        assertEquals(0, service.sync(SyncService.Scope.VARIANT, request, false).prices());
        assertEquals(1, service.sync(SyncService.Scope.VARIANT, request, true).prices());
        verify(client, Mockito.times(1)).price(335, 2, "80", 10378, 2023, "5");
    }

    @Test
    void modelSyncBulkSkipsFreshPricesAndCountsThemTogether() {
        when(repository.freshPriceVariantIds(Mockito.eq(1L), Mockito.eq(3L), Mockito.any(), Mockito.eq(false)))
                .thenReturn(java.util.Set.of(4L));

        SyncResult result = service.sync(SyncService.Scope.MODEL, request, false);

        assertEquals(0, result.prices());
        verify(repository).freshPriceVariantIds(Mockito.eq(1L), Mockito.eq(3L), Mockito.any(), Mockito.eq(false));
        verify(progress).processedBatch(Mockito.anyLong(), Mockito.eq(1L), Mockito.eq(false));
        verify(repository, never()).priceStamp(1, 4);
        verify(client, never()).price(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void existingFreshVariantDoesNotRequestCatalogOrPriceAgain() {
        when(repository.existingPriceStamp(LocalDate.of(2026, 7, 1), 2, "80", 10378, 2023, "5"))
                .thenReturn(Optional.of(Instant.now()));

        SyncResult result = service.sync(SyncService.Scope.VARIANT, request, false);

        assertEquals(0, result.prices());
        verify(repository, never()).period(LocalDate.of(2026, 7, 1));
        verify(client, never()).periods();
        verify(client, never()).brands(Mockito.anyInt(), Mockito.anyInt());
        verify(client, never()).price(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        verify(repository, never()).startRun(Mockito.anyLong(), Mockito.anyInt());
        verify(progress).processed(Mockito.anyLong(), Mockito.eq(false));
    }

    @Test
    void catalogWithoutVariantsLoadsModelsButNeverRequestsYearsOrPrices() {
        LocalDate latest = LocalDate.of(2026, 9, 1);
        Catalog.Period period = new Catalog.Period(99, 337, latest);
        Catalog.Brand honda = new Catalog.Brand(2, 2, "80", "HONDA", Instant.now());
        Catalog.Model model = new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now());
        when(client.periods()).thenReturn(List.of(new FipeResponses.Period(337, "setembro/2026 ")));
        when(repository.period(latest)).thenReturn(Optional.of(period));
        when(progress.createCatalog(Mockito.eq("catalog"), Mockito.any(), Mockito.eq(latest),
                Mockito.isNull(), Mockito.eq(false), Mockito.isNull(), Mockito.eq(false))).thenReturn(99L);
        when(progress.createCatalog(Mockito.eq("catalogType"), Mockito.any(), Mockito.eq(latest),
                Mockito.anyInt(), Mockito.eq(false), Mockito.eq(99L), Mockito.eq(true)))
                .thenAnswer(invocation -> 100L + (Integer) invocation.getArgument(3));
        for (int type = 1; type <= 3; type++) {
            when(repository.brandListStamp(99, type)).thenReturn(Optional.of(Instant.now()));
            when(repository.brands(99, type)).thenReturn(type == 2 ? List.of(honda) : List.of());
        }
        when(repository.modelListStamp(99, 2)).thenReturn(Optional.empty());
        when(client.models(337, 2, "80")).thenReturn(new FipeClient.Response<>(
                new FipeResponses.Models(List.of(new FipeResponses.ModelOption("CB 300F", 10378)), List.of()),
                "{\"Modelos\":[],\"Anos\":[]}"));
        when(repository.saveModel(Mockito.eq(2L), Mockito.eq(10378), Mockito.anyString(), Mockito.any()))
                .thenReturn(model);
        when(repository.models(99, 2)).thenReturn(List.of(model));

        SyncResult result = service.syncCatalog(new CatalogSyncRequest(null, null, null), false);

        assertEquals("2026-09", result.referenceMonth());
        assertEquals(3, result.vehicleTypes());
        assertEquals(0, result.prices());
        verify(client).models(337, 2, "80");
        verify(client, never()).years(Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString(), Mockito.anyInt());
        verify(client, never()).price(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        verify(repository, never()).startRun(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void catalogWithVariantsFetchesOnlyMissingYearListsAndNeverRequestsPrices() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        when(progress.createCatalog(Mockito.eq("catalog"), Mockito.any(), Mockito.eq(month),
                Mockito.eq(2), Mockito.eq(false), Mockito.isNull(), Mockito.eq(false)))
                .thenAnswer(invocation -> ((CatalogSyncRequest) invocation.getArgument(1)).variantsRequested()
                        ? 77L : 66L);
        when(progress.createCatalog(Mockito.eq("catalogType"), Mockito.any(), Mockito.eq(month),
                Mockito.eq(2), Mockito.eq(false), Mockito.anyLong(), Mockito.eq(true)))
                .thenAnswer(invocation -> ((CatalogSyncRequest) invocation.getArgument(1)).variantsRequested()
                        ? 88L : 99L);
        when(repository.yearListStamp(1, 3)).thenReturn(Optional.empty());
        when(client.years(335, 2, "80", 10378)).thenReturn(new FipeClient.Response<>(
                List.of(new FipeResponses.Option("2023", "2023-5")),
                "[{\"Label\":\"2023\",\"Value\":\"2023-5\"}]"));
        Catalog.Variant variant = new Catalog.Variant(4, 3, "2023-5", 2023, "5", Instant.now());
        when(repository.saveVariant(Mockito.eq(3L), Mockito.eq("2023-5"), Mockito.eq(2023),
                Mockito.eq("5"), Mockito.any())).thenReturn(variant);

        SyncResult without = service.syncCatalog(new CatalogSyncRequest("2026-07", 2, false), false);
        SyncResult with = service.syncCatalog(new CatalogSyncRequest("2026-07", 2, true), false);

        assertEquals(0, without.variants());
        assertEquals(1, with.variants());
        assertEquals(0, with.prices());
        verify(client).years(335, 2, "80", 10378);
        verify(progress).processedBatch(88L, 1, true);
        verify(client, never()).price(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        verify(repository, never()).startRun(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void priceSyncAfterCatalogInSamePeriodFetchesOnlyMissingYearsAndPrice() {
        Catalog.Brand brand = new Catalog.Brand(2, 2, "80", "HONDA", Instant.now());
        Catalog.Model model = new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now());
        Catalog.Variant variant = new Catalog.Variant(4, 3, "2023-5", 2023, "5", Instant.now());
        AtomicReference<Optional<Instant>> brandsSaved = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<Instant>> modelsSaved = new AtomicReference<>(Optional.empty());
        when(repository.brandListStamp(1, 2)).thenAnswer(invocation -> brandsSaved.get());
        when(repository.modelListStamp(1, 2)).thenAnswer(invocation -> modelsSaved.get());
        when(repository.yearListStamp(1, 3)).thenReturn(Optional.empty());
        Mockito.doAnswer(invocation -> {
            brandsSaved.set(Optional.of(Instant.now()));
            return List.of(new Catalog.Brand(2, 2, "80", "HONDA", Instant.now()));
        }).when(repository).persistBrandResponse(Mockito.eq(1L), Mockito.eq(2), Mockito.anyList(), Mockito.anyString(), Mockito.any());
        Mockito.doAnswer(invocation -> {
            modelsSaved.set(Optional.of(Instant.now()));
            return List.of(new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now()));
        }).when(repository).persistModelResponse(Mockito.eq(1L), Mockito.eq(2L), Mockito.anyList(), Mockito.anyString(), Mockito.any());
        when(client.brands(335, 2)).thenReturn(new FipeClient.Response<>(
                List.of(new FipeResponses.Option("HONDA", "80")), "[]"));
        when(client.models(335, 2, "80")).thenReturn(new FipeClient.Response<>(
                new FipeResponses.Models(List.of(new FipeResponses.ModelOption("CB 300F", 10378)), List.of()),
                "{\"Modelos\":[],\"Anos\":[]}"));
        when(client.years(335, 2, "80", 10378)).thenReturn(new FipeClient.Response<>(
                List.of(new FipeResponses.Option("2023", "2023-5")), "[]"));
        when(client.price(335, 2, "80", 10378, 2023, "5")).thenReturn(price());
        when(repository.saveBrand(Mockito.eq(2), Mockito.eq("80"), Mockito.anyString(), Mockito.any()))
                .thenReturn(brand);
        when(repository.saveModel(Mockito.eq(2L), Mockito.eq(10378), Mockito.anyString(), Mockito.any()))
                .thenReturn(model);
        when(repository.saveVariant(Mockito.eq(3L), Mockito.anyString(), Mockito.eq(2023),
                Mockito.eq("5"), Mockito.any())).thenReturn(variant);

        SyncResult catalog = service.syncCatalog(new CatalogSyncRequest("2026-07", 2, false), false);
        SyncResult modelPrices = service.sync(SyncService.Scope.MODEL, request, false);

        assertEquals(0, catalog.prices());
        assertEquals(1, modelPrices.prices());
        verify(client).brands(335, 2);
        verify(client).models(335, 2, "80");
        verify(client).years(335, 2, "80", 10378);
        verify(client).price(335, 2, "80", 10378, 2023, "5");
        verify(client, never()).periods();
        verify(repository, never()).startRun(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void priceSyncAfterCatalogWithVariantsOnlyFetchesPriceForSamePeriod() {
        when(repository.priceStamp(1, 4)).thenReturn(Optional.empty());
        when(client.price(335, 2, "80", 10378, 2023, "5")).thenReturn(price());

        SyncResult catalog = service.syncCatalog(new CatalogSyncRequest("2026-07", 2, true), false);
        SyncResult variantPrice = service.sync(SyncService.Scope.VARIANT, request, false);

        assertEquals(0, catalog.prices());
        assertEquals(1, variantPrice.prices());
        verify(client, never()).periods();
        verify(client, never()).brands(Mockito.anyInt(), Mockito.anyInt());
        verify(client, never()).models(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        verify(client, never()).years(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(), Mockito.anyInt());
        verify(client).price(335, 2, "80", 10378, 2023, "5");
    }

    @Test
    void priceSyncInAnotherMonthDoesNotReuseCurrentMonthCatalogLists() {
        Catalog.Period older = new Catalog.Period(8, 334, LocalDate.of(2026, 6, 1));
        Catalog.Brand brand = new Catalog.Brand(2, 2, "80", "HONDA", Instant.now());
        Catalog.Model model = new Catalog.Model(3, 2, 10378, "CB 300F", Instant.now());
        Catalog.Variant variant = new Catalog.Variant(4, 3, "2023-5", 2023, "5", Instant.now());
        when(repository.period(older.month())).thenReturn(Optional.of(older));
        when(repository.brands(8, 2)).thenReturn(List.of(brand));
        when(repository.models(8, 2)).thenReturn(List.of(model));
        when(repository.variants(8, 3)).thenReturn(List.of(variant));
        when(repository.brandListStamp(8, 2)).thenReturn(Optional.empty());
        when(repository.modelListStamp(8, 2)).thenReturn(Optional.empty());
        when(repository.yearListStamp(8, 3)).thenReturn(Optional.empty());
        when(client.brands(334, 2)).thenReturn(new FipeClient.Response<>(
                List.of(new FipeResponses.Option("HONDA", "80")), "[]"));
        when(client.models(334, 2, "80")).thenReturn(new FipeClient.Response<>(
                new FipeResponses.Models(List.of(new FipeResponses.ModelOption("CB 300F", 10378)), List.of()),
                "{\"Modelos\":[],\"Anos\":[]}"));
        when(client.years(334, 2, "80", 10378)).thenReturn(new FipeClient.Response<>(
                List.of(new FipeResponses.Option("2023", "2023-5")), "[]"));
        when(client.price(334, 2, "80", 10378, 2023, "5")).thenReturn(price());
        when(repository.saveBrand(Mockito.eq(2), Mockito.eq("80"), Mockito.anyString(), Mockito.any()))
                .thenReturn(brand);
        when(repository.saveModel(Mockito.eq(2L), Mockito.eq(10378), Mockito.anyString(), Mockito.any()))
                .thenReturn(model);
        when(repository.saveVariant(Mockito.eq(3L), Mockito.anyString(), Mockito.eq(2023),
                Mockito.eq("5"), Mockito.any())).thenReturn(variant);

        service.syncCatalog(new CatalogSyncRequest("2026-07", 2, false), false);
        SyncResult result = service.sync(SyncService.Scope.VARIANT,
                new SyncRequest("2026-06", 2, "80", 10378, 2023, "5"), false);

        assertEquals(1, result.prices());
        verify(client).brands(334, 2);
        verify(client).models(334, 2, "80");
        verify(client).years(334, 2, "80", 10378);
        verify(client).price(334, 2, "80", 10378, 2023, "5");
        verify(client, never()).brands(335, 2);
        verify(repository).persistBrandResponse(Mockito.eq(8L), Mockito.eq(2), Mockito.anyList(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void completePeriodAfterCatalogWithVariantsReusesAllLists() {
        when(progress.createCatalog(Mockito.eq("catalogType"), Mockito.any(),
                Mockito.eq(LocalDate.of(2026, 7, 1)), Mockito.anyInt(),
                Mockito.eq(false), Mockito.eq(0L), Mockito.eq(true)))
                .thenAnswer(invocation -> 20L + (Integer) invocation.getArgument(3));
        when(progress.create("vehicleType", new SyncRequest("2026-07", 1, null, null, null, null),
                LocalDate.of(2026, 7, 1), false, 0L, true)).thenReturn(11L);
        when(progress.create("vehicleType", new SyncRequest("2026-07", 2, null, null, null, null),
                LocalDate.of(2026, 7, 1), false, 0L, true)).thenReturn(12L);
        when(progress.create("vehicleType", new SyncRequest("2026-07", 3, null, null, null, null),
                LocalDate.of(2026, 7, 1), false, 0L, true)).thenReturn(13L);
        for (int type : List.of(1, 3)) {
            when(repository.brandListStamp(1, type)).thenReturn(Optional.of(Instant.now()));
            when(repository.brands(1, type)).thenReturn(List.of());
        }
        when(repository.startRun(Mockito.eq(1L), Mockito.anyInt()))
                .thenAnswer(invocation -> ((Integer) invocation.getArgument(1)).longValue());
        when(client.price(335, 2, "80", 10378, 2023, "5")).thenReturn(price());

        service.syncCatalog(new CatalogSyncRequest("2026-07", null, true), false);
        SyncResult result = service.sync(SyncService.Scope.PERIOD,
                new SyncRequest("2026-07", null, null, null, null, null), false);

        assertEquals(1, result.prices());
        verify(client, never()).periods();
        verify(client, never()).brands(Mockito.anyInt(), Mockito.anyInt());
        verify(client, never()).models(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        verify(client, never()).years(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString(), Mockito.anyInt());
        verify(client).price(335, 2, "80", 10378, 2023, "5");
        for (int type = 1; type <= 3; type++) verify(repository).finishRun(type, "completed", null);
    }

    @Test
    void latestPeriodUsesNewestMonthAndCachesFipePeriodList() {
        LocalDate latest = LocalDate.of(2026, 9, 1);
        when(client.periods()).thenReturn(List.of(
                new FipeResponses.Period(337, "setembro/2026 "),
                new FipeResponses.Period(335, "julho/2026 ")));
        when(repository.period(latest)).thenReturn(Optional.of(new Catalog.Period(99, 337, latest)));
        when(repository.brands(99, 2)).thenReturn(List.of());
        when(repository.brandListStamp(99, 2)).thenReturn(Optional.of(Instant.now()));

        service.syncCatalog(new CatalogSyncRequest(null, 2, false), false);
        service.syncCatalog(new CatalogSyncRequest(null, 2, false), false);

        verify(client).periods();
        verify(client, never()).brands(Mockito.anyInt(), Mockito.anyInt());
        verify(repository, Mockito.times(2)).period(latest);
    }

    @Test
    void overlappingCatalogScopesFetchTheSameMissingListOnlyOnce() throws Exception {
        LocalDate month = LocalDate.of(2026, 7, 1);
        AtomicReference<Optional<Instant>> stamp = new AtomicReference<>(Optional.empty());
        AtomicInteger childIds = new AtomicInteger(100);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch secondCreated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(progress.createCatalog(Mockito.eq("catalogType"), Mockito.any(), Mockito.eq(month),
                Mockito.eq(2), Mockito.eq(false), Mockito.anyLong(), Mockito.eq(true)))
                .thenAnswer(invocation -> {
                    long id = childIds.incrementAndGet();
                    if (id == 102) secondCreated.countDown();
                    return id;
                });
        when(repository.brandListStamp(1, 2)).thenAnswer(invocation -> stamp.get());
        when(repository.brands(1, 2)).thenReturn(List.of());
        when(client.brands(335, 2)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            return new FipeClient.Response<>(List.of(), "[]");
        });
        Mockito.doAnswer(invocation -> {
            stamp.set(Optional.of(Instant.now()));
            return List.of();
        }).when(repository).persistBrandResponse(Mockito.eq(1L), Mockito.eq(2), Mockito.anyList(), Mockito.anyString(), Mockito.any());

        CompletableFuture<SyncResult> first = CompletableFuture.supplyAsync(() ->
                service.syncCatalog(new CatalogSyncRequest("2026-07", 2, false), false));
        CompletableFuture<SyncResult> second;
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            second = CompletableFuture.supplyAsync(() ->
                    service.syncCatalog(new CatalogSyncRequest("2026-07", 2, true), false));
            assertTrue(secondCreated.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        assertEquals(0, second.get(5, TimeUnit.SECONDS).prices());
        verify(client).brands(335, 2);
    }

    @Test
    void fullPeriodRecordsCompletionForEachVehicleType() {
        when(progress.create(Mockito.eq("period"), Mockito.any(), Mockito.any(), Mockito.eq(false),
                Mockito.isNull(), Mockito.eq(false))).thenReturn(99L);
        when(progress.create(Mockito.eq("vehicleType"), Mockito.any(), Mockito.any(), Mockito.eq(false),
                Mockito.eq(99L), Mockito.eq(true))).thenAnswer(invocation ->
                ((SyncRequest) invocation.getArgument(1)).vehicleType().longValue());
        for (int type = 1; type <= 3; type++) {
            when(repository.brandListStamp(1, type)).thenReturn(Optional.of(Instant.now()));
            when(repository.brands(1, type)).thenReturn(List.of());
        }
        when(repository.startRun(Mockito.eq(1L), Mockito.anyInt())).thenAnswer(invocation ->
                ((Integer) invocation.getArgument(1)).longValue());

        SyncResult result = service.sync(SyncService.Scope.PERIOD,
                new SyncRequest("07/2026", null, null, null, null, null), false);

        assertEquals(3, result.vehicleTypes());
        assertEquals("2026-07", result.referenceMonth());
        for (int type = 1; type <= 3; type++) {
            verify(repository).finishRun(type, "completed", null);
        }
        verify(client, never()).periods();
    }

    @Test
    void identicalConcurrentRequestsShareTheSameCollection() throws Exception {
        when(repository.priceStamp(1, 4)).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(client.price(335, 2, "80", 10378, 2023, "5")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            return price();
        });

        CompletableFuture<SyncResult> first = CompletableFuture.supplyAsync(() ->
                service.sync(SyncService.Scope.VARIANT, request, false));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<SyncResult> second = CompletableFuture.supplyAsync(() -> {
            secondStarted.countDown();
            return service.sync(SyncService.Scope.VARIANT, request, false);
        });
        assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse(second.isDone());
        release.countDown();

        assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    private static FipeClient.Response<FipeResponses.Price> price() {
        return new FipeClient.Response<>(new FipeResponses.Price("R$ 23.519,00", "HONDA",
                "CB 300F", 2023, "Flex", "811174-0", "julho de 2026 ", "abc", 2, "F", "texto"),
                "{\"Valor\":\"R$ 23.519,00\"}");
    }
}
