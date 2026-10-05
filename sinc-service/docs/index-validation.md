# Validating period catalog lookup indexes

V4 adds indexes for the repository's two catalog lookups. The existing primary
keys start with `(period_id, model_id)` for models and `(period_id, variant_id)`
for variants, so neither covers the queried `(period_id, brand_id)` nor
`(period_id, model_id)` variant mapping respectively.

On an isolated/staging database with representative data and current statistics,
compare these before/after (do not use `EXPLAIN ANALYZE` against production as it
executes the query):

```sql
EXPLAIN (COSTS, BUFFERS)
SELECT m.id, m.brand_id, m.external_code, m.name, m.synced_at
FROM fipe.period_models pm JOIN fipe.models m ON m.id=pm.model_id
WHERE pm.period_id=1 AND pm.brand_id=2 ORDER BY m.id;

EXPLAIN (COSTS, BUFFERS)
SELECT v.id, v.model_id, v.source_value, v.model_year, v.fuel_code, v.synced_at
FROM fipe.period_variants pv JOIN fipe.model_variants v ON v.id=pv.variant_id
WHERE pv.period_id=1 AND pv.model_id=3 ORDER BY v.id;
```

Keep the indexes when plans use them (or avoid a material increase in estimated
cost/buffers on representative large periods); small tables may correctly use
sequential scans. Migration is incremental and intentionally not run here.
