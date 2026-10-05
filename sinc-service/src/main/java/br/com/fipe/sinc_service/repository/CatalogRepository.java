package br.com.fipe.sinc_service.repository;

import br.com.fipe.sinc_service.domain.Catalog;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogRepository {
    public record BrandInput(String code, String name) {}
    public record ModelInput(int code, String name) {}
    public record VariantInput(String sourceValue, int year, String fuel, String label) {}
    public record PriceHistory(LocalDate referenceMonth, int modelYear, String fuelCode,
                               String label, String fipeCode, BigDecimal priceBrl,
                               Instant syncedAt, JsonNode rawResponse) {}

    public record IncompletePeriod(LocalDate referenceMonth, int vehicleType) {}

    private static final RowMapper<Catalog.Period> PERIOD = (rs, row) -> new Catalog.Period(
            rs.getLong("id"), rs.getInt("external_code"), rs.getDate("reference_month").toLocalDate());
    private static final RowMapper<Catalog.Brand> BRAND = (rs, row) -> new Catalog.Brand(
            rs.getLong("id"), rs.getInt("vehicle_type"), rs.getString("external_code"),
            rs.getString("name"), rs.getTimestamp("synced_at").toInstant());
    private static final RowMapper<Catalog.Model> MODEL = (rs, row) -> new Catalog.Model(
            rs.getLong("id"), rs.getLong("brand_id"), rs.getInt("external_code"),
            rs.getString("name"), rs.getTimestamp("synced_at").toInstant());
    private static final RowMapper<Catalog.Variant> VARIANT = (rs, row) -> new Catalog.Variant(
            rs.getLong("id"), rs.getLong("model_id"), rs.getString("source_value"),
            rs.getInt("model_year"), rs.getString("fuel_code"), rs.getTimestamp("synced_at").toInstant());

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CatalogRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.stream().findFirst();
    }

    public Optional<Catalog.Period> period(LocalDate month) {
        return first(jdbc.query("SELECT id, external_code, reference_month FROM fipe.reference_periods WHERE reference_month=?",
                PERIOD, month));
    }

    public Catalog.Period savePeriod(int code, String sourceLabel, LocalDate month) {
        return jdbc.queryForObject("""
                INSERT INTO fipe.reference_periods (external_code, source_month_label, reference_month)
                VALUES (?, ?, ?) ON CONFLICT (reference_month) DO UPDATE
                SET external_code=EXCLUDED.external_code, source_month_label=EXCLUDED.source_month_label
                RETURNING id, external_code, reference_month
                """, PERIOD, code, sourceLabel, month);
    }

    public List<LocalDate> localMonths() {
        return jdbc.query("SELECT reference_month FROM fipe.reference_periods ORDER BY reference_month",
                (rs, row) -> rs.getDate(1).toLocalDate());
    }

    public List<IncompletePeriod> incompletePeriods() {
        return jdbc.query("""
                SELECT r.reference_month, t.vehicle_type
                FROM fipe.reference_periods r CROSS JOIN (VALUES (1), (2), (3)) AS t(vehicle_type)
                WHERE NOT EXISTS (SELECT 1 FROM fipe.sync_runs s
                    WHERE s.period_id=r.id AND s.vehicle_type=t.vehicle_type AND s.status='completed')
                ORDER BY r.reference_month, t.vehicle_type
                """, (rs, row) -> new IncompletePeriod(rs.getDate(1).toLocalDate(), rs.getInt(2)));
    }

    public Optional<Instant> brandListStamp(long periodId, int type) {
        return stamp("SELECT synced_at FROM fipe.brand_list_responses WHERE period_id=? AND vehicle_type=?",
                periodId, type);
    }

    public void saveBrandList(long periodId, int type, String json, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.brand_list_responses (period_id, vehicle_type, raw_response, synced_at)
                VALUES (?, ?, CAST(? AS jsonb), ?) ON CONFLICT (period_id, vehicle_type) DO UPDATE
                SET raw_response=EXCLUDED.raw_response, synced_at=EXCLUDED.synced_at
                """, periodId, type, json, Timestamp.from(now));
    }

    @Transactional
    public List<Catalog.Brand> persistBrandResponse(long periodId, int type, List<BrandInput> inputs,
                                                    String raw, Instant now) {
        List<Catalog.Brand> result = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        for (BrandInput input : inputs) {
            Catalog.Brand brand = saveBrand(type, input.code(), input.name(), now);
            result.add(brand);
            links.add(new Object[]{periodId, brand.id(), input.name(), Timestamp.from(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO fipe.period_brands (period_id, brand_id, display_name, synced_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (period_id, brand_id) DO UPDATE
                SET display_name=EXCLUDED.display_name, synced_at=EXCLUDED.synced_at
                """, links);
        saveBrandList(periodId, type, raw, now); // stamp is the final write in this transaction
        return result;
    }

    public Catalog.Brand saveBrand(int type, String code, String name, Instant now) {
        long id = jdbc.queryForObject("""
                INSERT INTO fipe.brands (vehicle_type, external_code, name, synced_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (vehicle_type, external_code) DO UPDATE
                SET name=EXCLUDED.name, synced_at=EXCLUDED.synced_at RETURNING id
                """, Long.class, type, code, name, Timestamp.from(now));
        return new Catalog.Brand(id, type, code, name, now);
    }

    public void savePeriodBrand(long periodId, long brandId, String name, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.period_brands (period_id, brand_id, display_name, synced_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (period_id, brand_id) DO UPDATE
                SET display_name=EXCLUDED.display_name, synced_at=EXCLUDED.synced_at
                """, periodId, brandId, name, Timestamp.from(now));
    }

    public List<Catalog.Brand> brands(long periodId, int type) {
        return jdbc.query("""
                SELECT b.id, b.vehicle_type, b.external_code, b.name, b.synced_at
                FROM fipe.period_brands pb JOIN fipe.brands b ON b.id=pb.brand_id
                WHERE pb.period_id=? AND b.vehicle_type=? ORDER BY b.id
                """, BRAND, periodId, type);
    }

    public Optional<Instant> modelListStamp(long periodId, long brandId) {
        return stamp("SELECT synced_at FROM fipe.model_list_responses WHERE period_id=? AND brand_id=?",
                periodId, brandId);
    }

    public void saveModelList(long periodId, long brandId, String json, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.model_list_responses (period_id, brand_id, raw_response, synced_at)
                VALUES (?, ?, CAST(? AS jsonb), ?) ON CONFLICT (period_id, brand_id) DO UPDATE
                SET raw_response=EXCLUDED.raw_response, synced_at=EXCLUDED.synced_at
                """, periodId, brandId, json, Timestamp.from(now));
    }

    @Transactional
    public List<Catalog.Model> persistModelResponse(long periodId, long brandId, List<ModelInput> inputs,
                                                     String raw, Instant now) {
        List<Catalog.Model> result = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        for (ModelInput input : inputs) {
            Catalog.Model model = saveModel(brandId, input.code(), input.name(), now);
            result.add(model);
            links.add(new Object[]{periodId, brandId, model.id(), input.name(), Timestamp.from(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO fipe.period_models (period_id, brand_id, model_id, display_name, synced_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (period_id, model_id) DO UPDATE
                SET display_name=EXCLUDED.display_name, synced_at=EXCLUDED.synced_at
                """, links);
        saveModelList(periodId, brandId, raw, now);
        return result;
    }

    public Catalog.Model saveModel(long brandId, int code, String name, Instant now) {
        long id = jdbc.queryForObject("""
                INSERT INTO fipe.models (brand_id, external_code, name, synced_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (brand_id, external_code) DO UPDATE
                SET name=EXCLUDED.name, synced_at=EXCLUDED.synced_at RETURNING id
                """, Long.class, brandId, code, name, Timestamp.from(now));
        return new Catalog.Model(id, brandId, code, name, now);
    }

    public void savePeriodModel(long periodId, long brandId, long modelId, String name, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.period_models (period_id, brand_id, model_id, display_name, synced_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (period_id, model_id) DO UPDATE
                SET display_name=EXCLUDED.display_name, synced_at=EXCLUDED.synced_at
                """, periodId, brandId, modelId, name, Timestamp.from(now));
    }

    public List<Catalog.Model> models(long periodId, long brandId) {
        return jdbc.query("""
                SELECT m.id, m.brand_id, m.external_code, m.name, m.synced_at
                FROM fipe.period_models pm JOIN fipe.models m ON m.id=pm.model_id
                WHERE pm.period_id=? AND pm.brand_id=? ORDER BY m.id
                """, MODEL, periodId, brandId);
    }

    public Optional<Instant> yearListStamp(long periodId, long modelId) {
        return stamp("SELECT synced_at FROM fipe.year_list_responses WHERE period_id=? AND model_id=?",
                periodId, modelId);
    }

    public void saveYearList(long periodId, long modelId, String json, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.year_list_responses (period_id, model_id, raw_response, synced_at)
                VALUES (?, ?, CAST(? AS jsonb), ?) ON CONFLICT (period_id, model_id) DO UPDATE
                SET raw_response=EXCLUDED.raw_response, synced_at=EXCLUDED.synced_at
                """, periodId, modelId, json, Timestamp.from(now));
    }

    @Transactional
    public List<Catalog.Variant> persistYearResponse(long periodId, long modelId, List<VariantInput> inputs,
                                                      String raw, Instant now) {
        List<Catalog.Variant> result = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        for (VariantInput input : inputs) {
            Catalog.Variant variant = saveVariant(modelId, input.sourceValue(), input.year(), input.fuel(), now);
            result.add(variant);
            links.add(new Object[]{periodId, modelId, variant.id(), input.label(), Timestamp.from(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO fipe.period_variants (period_id, model_id, variant_id, display_label, synced_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (period_id, variant_id) DO UPDATE
                SET display_label=EXCLUDED.display_label, synced_at=EXCLUDED.synced_at
                """, links);
        saveYearList(periodId, modelId, raw, now);
        return result;
    }

    public Catalog.Variant saveVariant(long modelId, String sourceValue, int year, String fuel, Instant now) {
        long id = jdbc.queryForObject("""
                INSERT INTO fipe.model_variants (model_id, source_value, model_year, fuel_code, synced_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (model_id, source_value) DO UPDATE
                SET model_year=EXCLUDED.model_year, fuel_code=EXCLUDED.fuel_code,
                    synced_at=EXCLUDED.synced_at RETURNING id
                """, Long.class, modelId, sourceValue, year, fuel, Timestamp.from(now));
        return new Catalog.Variant(id, modelId, sourceValue, year, fuel, now);
    }

    public void savePeriodVariant(long periodId, long modelId, long variantId, String label, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.period_variants (period_id, model_id, variant_id, display_label, synced_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (period_id, variant_id) DO UPDATE
                SET display_label=EXCLUDED.display_label, synced_at=EXCLUDED.synced_at
                """, periodId, modelId, variantId, label, Timestamp.from(now));
    }

    public List<Catalog.Variant> variants(long periodId, long modelId) {
        return jdbc.query("""
                SELECT v.id, v.model_id, v.source_value, v.model_year, v.fuel_code, v.synced_at
                FROM fipe.period_variants pv JOIN fipe.model_variants v ON v.id=pv.variant_id
                WHERE pv.period_id=? AND pv.model_id=? ORDER BY v.id
                """, VARIANT, periodId, modelId);
    }

    public Optional<Instant> priceStamp(long periodId, long variantId) {
        return stamp("SELECT synced_at FROM fipe.vehicle_prices WHERE period_id=? AND variant_id=?",
                periodId, variantId);
    }

    /** Existing prices that need no refresh, fetched in one roundtrip for the model. */
    public Set<Long> freshPriceVariantIds(long periodId, long modelId, Instant cutoff, boolean refresh) {
        return new HashSet<>(jdbc.query("""
                SELECT pv.variant_id FROM fipe.period_variants pv
                JOIN fipe.vehicle_prices p ON p.period_id=pv.period_id AND p.variant_id=pv.variant_id
                WHERE pv.period_id=? AND pv.model_id=? AND (?=false OR p.synced_at > ?)
                """, (rs, row) -> rs.getLong(1), periodId, modelId, refresh, Timestamp.from(cutoff)));
    }

    public Optional<Instant> existingPriceStamp(LocalDate month, int type, String brandCode,
                                                 int modelCode, int year, String fuelCode) {
        return stamp("""
                SELECT p.synced_at FROM fipe.vehicle_prices p
                JOIN fipe.reference_periods r ON r.id=p.period_id
                JOIN fipe.model_variants v ON v.id=p.variant_id
                JOIN fipe.models m ON m.id=v.model_id
                JOIN fipe.brands b ON b.id=m.brand_id
                WHERE r.reference_month=? AND b.vehicle_type=? AND b.external_code=?
                    AND m.external_code=? AND v.model_year=? AND v.fuel_code=?
                """, month, type, brandCode, modelCode, year, fuelCode);
    }

    public void savePrice(long periodId, long variantId, BigDecimal price, String fipeCode,
                          String json, Instant now) {
        jdbc.update("""
                INSERT INTO fipe.vehicle_prices
                    (period_id, variant_id, price_brl, fipe_code, raw_response, synced_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT (period_id, variant_id) DO UPDATE
                SET price_brl=EXCLUDED.price_brl, fipe_code=EXCLUDED.fipe_code,
                    raw_response=EXCLUDED.raw_response, synced_at=EXCLUDED.synced_at
                """, periodId, variantId, price, fipeCode, json, Timestamp.from(now));
    }

    public long startRun(long periodId, int type) {
        return jdbc.queryForObject("""
                INSERT INTO fipe.sync_runs (period_id, vehicle_type, status, started_at)
                VALUES (?, ?, 'running', now()) RETURNING id
                """, Long.class, periodId, type);
    }

    public void finishRun(long runId, String status, String error) {
        jdbc.update("UPDATE fipe.sync_runs SET status=?, error_message=?, finished_at=now() WHERE id=?",
                status, error, runId);
    }

    public List<PriceHistory> history(int type, String brand, int model, Integer year, String fuel) {
        String sql = """
                SELECT rp.reference_month, v.model_year, v.fuel_code, pv.display_label,
                    p.fipe_code, p.price_brl, p.synced_at, p.raw_response::text AS raw_response
                FROM fipe.vehicle_prices p
                JOIN fipe.reference_periods rp ON rp.id=p.period_id
                JOIN fipe.period_variants pv ON pv.period_id=p.period_id AND pv.variant_id=p.variant_id
                JOIN fipe.model_variants v ON v.id=p.variant_id
                JOIN fipe.models m ON m.id=v.model_id
                JOIN fipe.brands b ON b.id=m.brand_id
                WHERE b.vehicle_type=? AND b.external_code=? AND m.external_code=?
                """ + (year == null ? "" : " AND v.model_year=? AND v.fuel_code=?")
                + " ORDER BY rp.reference_month, v.model_year, v.fuel_code";
        RowMapper<PriceHistory> mapper = (rs, row) -> new PriceHistory(
                rs.getDate("reference_month").toLocalDate(), rs.getInt("model_year"),
                rs.getString("fuel_code"), rs.getString("display_label"), rs.getString("fipe_code"),
                rs.getBigDecimal("price_brl"), rs.getTimestamp("synced_at").toInstant(),
                json.readTree(rs.getString("raw_response")));
        return year == null ? jdbc.query(sql, mapper, type, brand, model)
                : jdbc.query(sql, mapper, type, brand, model, year, fuel);
    }

    private Optional<Instant> stamp(String sql, Object... args) {
        return first(jdbc.query(sql, (rs, row) -> rs.getTimestamp(1).toInstant(), args));
    }
}
