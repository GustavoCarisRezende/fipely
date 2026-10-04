SET search_path TO fipe;

CREATE TABLE sync_jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_job_id bigint REFERENCES sync_jobs (id),
    scope text NOT NULL CHECK (scope IN ('period', 'vehicleType', 'brand', 'model', 'variant')),
    reference_month date NOT NULL,
    vehicle_type smallint CHECK (vehicle_type IN (1, 2, 3)),
    brand_code text,
    model_code integer,
    model_year integer,
    fuel_code text,
    refresh_old_records boolean NOT NULL,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'failed')),
    phase text NOT NULL,
    current_brand text,
    current_model text,
    brands_discovered bigint NOT NULL DEFAULT 0 CHECK (brands_discovered >= 0),
    models_discovered bigint NOT NULL DEFAULT 0 CHECK (models_discovered >= 0),
    vehicles_discovered bigint NOT NULL DEFAULT 0 CHECK (vehicles_discovered >= 0),
    vehicles_processed bigint NOT NULL DEFAULT 0 CHECK (vehicles_processed >= 0),
    vehicles_synced bigint NOT NULL DEFAULT 0 CHECK (vehicles_synced >= 0),
    vehicles_skipped bigint NOT NULL DEFAULT 0 CHECK (vehicles_skipped >= 0),
    discovery_complete boolean NOT NULL DEFAULT false,
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    error_message text,
    CHECK (vehicles_processed <= vehicles_discovered),
    CHECK (vehicles_synced + vehicles_skipped = vehicles_processed),
    CHECK ((status IN ('queued', 'running') AND finished_at IS NULL)
        OR (status IN ('completed', 'failed') AND finished_at IS NOT NULL))
);

CREATE INDEX sync_jobs_status_updated_idx ON sync_jobs (status, updated_at DESC);
CREATE INDEX sync_jobs_parent_idx ON sync_jobs (parent_job_id);
