-- Per-game per-model prediction evaluations: one row per FINAL game × model, holding
-- the model's pre-game prediction (recomputed from rating snapshots dated strictly
-- before the game) and its error vs. the actual result. Because rating snapshots are a
-- daily time series, rows can be built retroactively without leakage. model_type values:
-- MASSEY (spread), MASSEY_TOTALS (total), BRADLEY_TERRY / BRADLEY_TERRY_W (win prob),
-- ML (all three, tagged with model_version), BOOK (closing line benchmark).
CREATE TABLE prediction_evaluations (
    id                      BIGSERIAL PRIMARY KEY,
    game_id                 BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    season_id               BIGINT NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    model_type              VARCHAR(20) NOT NULL,
    game_date               DATE NOT NULL,
    predicted_spread        DOUBLE PRECISION,
    predicted_total         DOUBLE PRECISION,
    predicted_home_win_prob DOUBLE PRECISION,
    actual_margin           INTEGER NOT NULL,
    actual_total            INTEGER NOT NULL,
    home_won                BOOLEAN NOT NULL,
    spread_error            DOUBLE PRECISION,   -- actual_margin − predicted_spread
    total_error             DOUBLE PRECISION,   -- actual_total − predicted_total
    model_version           VARCHAR(40),        -- ML rows only: features.json version
    evaluated_at            TIMESTAMP NOT NULL,
    UNIQUE (game_id, model_type)
);

CREATE INDEX idx_pred_eval_season_model_date
    ON prediction_evaluations (season_id, model_type, game_date);
