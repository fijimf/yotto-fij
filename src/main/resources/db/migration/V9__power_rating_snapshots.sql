-- Power rating snapshots: one row per team × season × model × date (cumulative ratings through that date)
CREATE TABLE team_power_rating_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES teams(id),
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    model_type    VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    rating        DOUBLE PRECISION NOT NULL,
    rank          INTEGER,
    games_played  INTEGER NOT NULL DEFAULT 0,
    calculated_at TIMESTAMP NOT NULL,
    UNIQUE (team_id, season_id, model_type, snapshot_date)
);

CREATE INDEX idx_power_snapshots_season_model_date
    ON team_power_rating_snapshots (season_id, model_type, snapshot_date);

CREATE INDEX idx_power_snapshots_team_season_model
    ON team_power_rating_snapshots (team_id, season_id, model_type);

-- Model-level scalar parameters per date (e.g., home court advantage α)
CREATE TABLE power_model_param_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    model_type    VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    param_name    VARCHAR(50) NOT NULL,
    param_value   DOUBLE PRECISION NOT NULL,
    calculated_at TIMESTAMP NOT NULL,
    UNIQUE (season_id, model_type, snapshot_date, param_name)
);

CREATE INDEX idx_power_params_season_model
    ON power_model_param_snapshots (season_id, model_type);
