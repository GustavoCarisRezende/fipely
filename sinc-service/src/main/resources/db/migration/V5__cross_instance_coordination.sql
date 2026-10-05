-- Never modify existing jobs during migration. Require explicit operator remediation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM fipe.sync_jobs
        WHERE parent_job_id IS NULL AND status IN ('queued', 'running')
        GROUP BY scope, reference_month, vehicle_type, brand_code, model_code,
                 model_year, fuel_code, refresh_old_records, include_variants
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V5 blocked: duplicate active top-level sync jobs exist; inspect fipe.sync_jobs and manually resolve equivalent queued/running jobs, then retry migration';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_sync_jobs_active_root_equivalence
    ON fipe.sync_jobs (scope, reference_month, vehicle_type, brand_code, model_code,
                       model_year, fuel_code, refresh_old_records, include_variants)
    NULLS NOT DISTINCT
    WHERE parent_job_id IS NULL AND status IN ('queued', 'running');

CREATE TABLE fipe.fipe_rate_limit (
    id smallint PRIMARY KEY CHECK (id = 1),
    next_request_at timestamptz NOT NULL DEFAULT now(),
    interval_ms bigint NOT NULL DEFAULT 1000 CHECK (interval_ms >= 0),
    retry_after_until timestamptz NOT NULL DEFAULT now(),
    success_streak integer NOT NULL DEFAULT 0 CHECK (success_streak BETWEEN 0 AND 4)
);
INSERT INTO fipe.fipe_rate_limit (id) VALUES (1);
