SET search_path TO fipe;

-- Supports the period+brand model lookup and period+model variant lookup.
CREATE INDEX period_models_period_brand_idx ON period_models (period_id, brand_id);
CREATE INDEX period_variants_period_model_idx ON period_variants (period_id, model_id);
