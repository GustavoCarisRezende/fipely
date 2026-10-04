SET search_path TO fipe;

ALTER TABLE sync_jobs DROP CONSTRAINT sync_jobs_scope_check;
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_scope_check
    CHECK (scope IN ('period', 'vehicleType', 'brand', 'model', 'variant', 'catalog', 'catalogType'));

ALTER TABLE sync_jobs ADD COLUMN include_variants boolean NOT NULL DEFAULT false;
