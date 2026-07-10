-- Multi-model ML support: a registry of named model bundles (one directory per slug
-- under /models), each independently trainable and evaluable. Serving state lives here;
-- the ONNX files + features.json manifest live on the model_data volume.
CREATE TABLE ml_models (
    id            BIGSERIAL PRIMARY KEY,
    slug          VARCHAR(40) NOT NULL UNIQUE,
    display_name  VARCHAR(100),
    feature_set   VARCHAR(40),
    status        VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',  -- ACTIVE / CANDIDATE / RETIRED
    is_default    BOOLEAN NOT NULL DEFAULT FALSE,            -- fills PredictionResult.ml
    version       VARCHAR(40),                               -- manifest version (UTC timestamp)
    trained_at    TIMESTAMP,
    metrics_json  TEXT,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);

-- Evaluations are now written per ML model as 'ML:<slug>' (e.g. 'ML:baseline'), which
-- needs more than 20 chars. Retag any legacy single-model rows.
ALTER TABLE prediction_evaluations ALTER COLUMN model_type TYPE VARCHAR(40);
UPDATE prediction_evaluations SET model_type = 'ML:baseline' WHERE model_type = 'ML';

-- Training runs are per model slug now.
ALTER TABLE ml_training_runs ADD COLUMN model_slug VARCHAR(40);
