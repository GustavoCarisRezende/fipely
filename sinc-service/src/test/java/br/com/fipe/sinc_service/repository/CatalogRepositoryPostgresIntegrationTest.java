package br.com.fipe.sinc_service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.fipe.sinc_service.TestDatabaseSafetyInitializer;
import br.com.fipe.sinc_service.domain.Catalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
class CatalogRepositoryPostgresIntegrationTest {
    @Autowired CatalogRepository catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void invalidSecondModelFailsMidBatchAndRollsBackModelsLinksAndListStamp() {
        Fixture fixture = fixture(57101, 57101, 2026, 7);
        try {
            List<CatalogRepository.ModelInput> inputs = List.of(
                    new CatalogRepository.ModelInput(57001, "model one"),
                    new CatalogRepository.ModelInput(57002, null));
            assertThrows(DataAccessException.class, () -> catalog.persistModelResponse(
                    fixture.period.id(), fixture.brand.id(), inputs, "{\"Modelos\":[],\"Anos\":[]}", Instant.now()));

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.models WHERE brand_id=?", Integer.class,
                    fixture.brand.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.period_models WHERE period_id=?", Integer.class,
                    fixture.period.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.model_list_responses WHERE period_id=?", Integer.class,
                    fixture.period.id()));
        } finally {
            cleanup(fixture, null);
        }
    }

    @Test
    void duplicateVariantLinksFailMidBatchAndRollbackVariantsLinksAndListStamp() {
        Fixture fixture = fixture(57102, 57102, 2026, 8);
        Catalog.Model model = catalog.saveModel(fixture.brand.id(), 57002, "model", Instant.now());
        catalog.savePeriodModel(fixture.period.id(), fixture.brand.id(), model.id(), model.name(), Instant.now());
        try {
            List<CatalogRepository.VariantInput> duplicate = List.of(
                    new CatalogRepository.VariantInput("2023-5", 2023, "5", "first"),
                    new CatalogRepository.VariantInput("2024-5", -1, "5", "invalid year"));
            assertThrows(DataAccessException.class, () -> catalog.persistYearResponse(
                    fixture.period.id(), model.id(), duplicate, "[]", Instant.now()));

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.model_variants WHERE model_id=?", Integer.class,
                    model.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.period_variants WHERE period_id=?", Integer.class,
                    fixture.period.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fipe.year_list_responses WHERE period_id=?", Integer.class,
                    fixture.period.id()));
        } finally {
            cleanup(fixture, model);
        }
    }

    @Test
    void modelPriceHistoryAndExistingPriceLookupAreIsolatedByReferencePeriod() {
        Fixture may = fixture(57103, 57103, 2026, 5);
        Catalog.Period junePeriod = catalog.savePeriod(57104, "junho/2026", LocalDate.of(2026, 6, 1));
        catalog.savePeriodBrand(junePeriod.id(), may.brand.id(), "brand", Instant.now());
        Catalog.Model model = catalog.saveModel(may.brand.id(), 57003, "model", Instant.now());
        catalog.savePeriodModel(may.period.id(), may.brand.id(), model.id(), "model", Instant.now());
        catalog.savePeriodModel(junePeriod.id(), may.brand.id(), model.id(), "model", Instant.now());
        Catalog.Variant variant = catalog.saveVariant(model.id(), "2023-5", 2023, "5", Instant.now());
        catalog.savePeriodVariant(may.period.id(), model.id(), variant.id(), "2023", Instant.now());
        catalog.savePeriodVariant(junePeriod.id(), model.id(), variant.id(), "2023", Instant.now());
        catalog.savePrice(may.period.id(), variant.id(), new BigDecimal("12000.00"), "MAY", "{}", Instant.now());
        catalog.savePrice(junePeriod.id(), variant.id(), new BigDecimal("13000.00"), "JUNE", "{}", Instant.now());

        try {
            var history = catalog.history(2, "57103", 57003, null, null);
            assertEquals(2, history.size());
            assertEquals(LocalDate.of(2026, 5, 1), history.get(0).referenceMonth());
            assertEquals(new BigDecimal("12000.00"), history.get(0).priceBrl());
            assertEquals(LocalDate.of(2026, 6, 1), history.get(1).referenceMonth());
            assertEquals(new BigDecimal("13000.00"), history.get(1).priceBrl());

            assertEquals(new BigDecimal("12000.00"), jdbc.queryForObject(
                    "SELECT price_brl FROM fipe.vehicle_prices WHERE period_id=? AND variant_id=?",
                    BigDecimal.class, may.period.id(), variant.id()));
            assertEquals(new BigDecimal("13000.00"), jdbc.queryForObject(
                    "SELECT price_brl FROM fipe.vehicle_prices WHERE period_id=? AND variant_id=?",
                    BigDecimal.class, junePeriod.id(), variant.id()));
            assertTrue(catalog.existingPriceStamp(LocalDate.of(2026, 5, 1), 2, "57103", 57003, 2023, "5").isPresent());
            assertTrue(catalog.existingPriceStamp(LocalDate.of(2026, 6, 1), 2, "57103", 57003, 2023, "5").isPresent());
        } finally {
            cleanup(may, model, junePeriod.id());
        }
    }

    private Fixture fixture(int periodCode, int brandCode, int year, int month) {
        LocalDate date = LocalDate.of(year, month, 1);
        Catalog.Period period = catalog.savePeriod(periodCode, "teste/" + date, date);
        Catalog.Brand brand = catalog.saveBrand(2, String.valueOf(brandCode), "brand", Instant.now());
        catalog.savePeriodBrand(period.id(), brand.id(), "brand", Instant.now());
        return new Fixture(period, brand);
    }

    private void cleanup(Fixture fixture, Catalog.Model model) {
        cleanup(fixture, model, null);
    }

    private void cleanup(Fixture fixture, Catalog.Model model, Long extraPeriodId) {
        Long[] periodIds = extraPeriodId == null ? new Long[]{fixture.period.id()}
                : new Long[]{fixture.period.id(), extraPeriodId};
        for (Long id : periodIds) {
            jdbc.update("DELETE FROM fipe.vehicle_prices WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.period_variants WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.year_list_responses WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.period_models WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.model_list_responses WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.period_brands WHERE period_id=?", id);
            jdbc.update("DELETE FROM fipe.brand_list_responses WHERE period_id=?", id);
        }
        if (model != null) {
            jdbc.update("DELETE FROM fipe.model_variants WHERE model_id=?", model.id());
            jdbc.update("DELETE FROM fipe.models WHERE id=?", model.id());
        }
        jdbc.update("DELETE FROM fipe.model_variants WHERE model_id IN (SELECT id FROM fipe.models WHERE brand_id=?)",
                fixture.brand.id());
        jdbc.update("DELETE FROM fipe.models WHERE brand_id=?", fixture.brand.id());
        for (Long id : periodIds) jdbc.update("DELETE FROM fipe.reference_periods WHERE id=?", id);
        jdbc.update("DELETE FROM fipe.brands WHERE id=?", fixture.brand.id());
    }

    private record Fixture(Catalog.Period period, Catalog.Brand brand) {}
}
