-- History of ML training runs triggered through the trainer service (admin "Train
-- Models" button). The trainer service holds run state in memory only; this table is
-- the durable record. run_id is the trainer-service UUID used for status polling.
CREATE TABLE ml_training_runs (
    id            BIGSERIAL PRIMARY KEY,
    run_id        VARCHAR(40) NOT NULL UNIQUE,
    status        VARCHAR(20) NOT NULL,          -- RUNNING / COMPLETED / FAILED
    train_seasons VARCHAR(200),                  -- comma-separated years
    test_season   INTEGER,
    started_at    TIMESTAMP NOT NULL,
    finished_at   TIMESTAMP,
    metrics_json  TEXT,                          -- metrics block from features.json
    log_tail      TEXT,                          -- last lines of trainer output
    error_message VARCHAR(1000),
    reloaded      BOOLEAN NOT NULL DEFAULT FALSE -- models hot-reloaded after this run
);

CREATE INDEX idx_ml_training_runs_started ON ml_training_runs (started_at DESC);
