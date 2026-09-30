package br.com.fipe.sinc_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.fipe.sinc_service.domain.Catalog;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SyncServiceTest {
    private CatalogRepository repository;
    private FipeClient client;
    private SyncService service;
    private SyncRequest request;

    @BeforeEach
    void setup() {
        repository = mock(CatalogRepository.class);
        client = mock(FipeClient.class);
        service = new SyncService(repository, client, 365, 2);
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
    void fullPeriodRecordsCompletionForEachVehicleType() {
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
