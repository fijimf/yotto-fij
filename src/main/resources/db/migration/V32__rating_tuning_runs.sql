-- Walk-forward hyperparameter tuning runs for rating models (spec W4: the adjusted-
-- efficiency lambda sweep; model_type-keyed so future models can reuse the table).
-- params holds the sweep inputs (grid, sigma); results the per-lambda/per-season
-- metrics report. Promotion is manual: the sweep never mutates ratings or config.
CREATE TABLE rating_tuning_runs (
    id          BIGSERIAL PRIMARY KEY,
    model_type  VARCHAR(40) NOT NULL,
    status      VARCHAR(20) NOT NULL,          -- RUNNING / COMPLETED / FAILED
    started_at  TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    params      JSONB,
    results     JSONB,
    error       TEXT
);

CREATE INDEX idx_rating_tuning_runs_started ON rating_tuning_runs (started_at DESC);
