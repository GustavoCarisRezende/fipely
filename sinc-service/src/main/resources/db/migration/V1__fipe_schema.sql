SET search_path TO fipe;

CREATE TABLE reference_periods (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_code integer NOT NULL UNIQUE,
    source_month_label text NOT NULL,
    reference_month date NOT NULL UNIQUE,
    CONSTRAINT reference_month_first_day CHECK (EXTRACT(DAY FROM reference_month) = 1)
);

CREATE TABLE brands (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_type smallint NOT NULL CHECK (vehicle_type IN (1, 2, 3)),
    external_code text NOT NULL,
    name text NOT NULL,
    synced_at timestamptz NOT NULL,
    UNIQUE (vehicle_type, external_code)
);

CREATE TABLE models (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_id bigint NOT NULL REFERENCES brands (id),
    external_code integer NOT NULL,
    name text NOT NULL,
    synced_at timestamptz NOT NULL,
    UNIQUE (brand_id, external_code),
    UNIQUE (brand_id, id)
);

CREATE TABLE model_variants (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_id bigint NOT NULL REFERENCES models (id),
    source_value text NOT NULL,
    model_year integer NOT NULL CHECK (model_year >= 0),
    fuel_code text NOT NULL,
    synced_at timestamptz NOT NULL,
    UNIQUE (model_id, source_value),
    UNIQUE (model_id, model_year, fuel_code),
    UNIQUE (model_id, id)
);

CREATE TABLE period_brands (
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    brand_id bigint NOT NULL REFERENCES brands (id),
    display_name text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, brand_id)
);

-- These list records also represent successful empty responses.
CREATE TABLE brand_list_responses (
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    vehicle_type smallint NOT NULL CHECK (vehicle_type IN (1, 2, 3)),
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'array'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, vehicle_type)
);

CREATE TABLE model_list_responses (
    period_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'object'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, brand_id),
    FOREIGN KEY (period_id, brand_id) REFERENCES period_brands (period_id, brand_id)
);

CREATE TABLE period_models (
    period_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    model_id bigint NOT NULL,
    display_name text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, model_id),
    FOREIGN KEY (period_id, brand_id) REFERENCES period_brands (period_id, brand_id),
    FOREIGN KEY (brand_id, model_id) REFERENCES models (brand_id, id)
);

CREATE TABLE year_list_responses (
    period_id bigint NOT NULL,
    model_id bigint NOT NULL,
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'array'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, model_id),
    FOREIGN KEY (period_id, model_id) REFERENCES period_models (period_id, model_id)
);

CREATE TABLE period_variants (
    period_id bigint NOT NULL,
    model_id bigint NOT NULL,
    variant_id bigint NOT NULL,
    display_label text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, variant_id),
    FOREIGN KEY (period_id, model_id) REFERENCES period_models (period_id, model_id),
    FOREIGN KEY (model_id, variant_id) REFERENCES model_variants (model_id, id)
);

CREATE TABLE vehicle_prices (
    period_id bigint NOT NULL,
    variant_id bigint NOT NULL,
    price_brl numeric(14, 2) NOT NULL CHECK (price_brl > 0),
    fipe_code text NOT NULL,
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'object'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, variant_id),
    FOREIGN KEY (period_id, variant_id) REFERENCES period_variants (period_id, variant_id)
);

CREATE TABLE sync_runs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    vehicle_type smallint NOT NULL CHECK (vehicle_type IN (1, 2, 3)),
    status text NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    error_message text,
    CHECK ((status = 'running' AND finished_at IS NULL)
        OR (status <> 'running' AND finished_at IS NOT NULL))
);

CREATE INDEX vehicle_prices_variant_period_idx ON vehicle_prices (variant_id, period_id);
CREATE INDEX period_variants_variant_period_idx ON period_variants (variant_id, period_id);
CREATE INDEX sync_runs_period_type_started_idx
    ON sync_runs (period_id, vehicle_type, started_at DESC);
