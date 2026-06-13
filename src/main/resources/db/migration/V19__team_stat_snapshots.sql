-- Long-format per-team derived stat time series: one row per team × season × date ×
-- stat. New stats are registry entries in code, not schema migrations. Population
-- distributions for these stats reuse season_population_stats (already long format).
CREATE TABLE team_stat_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES teams(id),
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    snapshot_date DATE NOT NULL,
    stat_name     VARCHAR(64) NOT NULL,
    value         DOUBLE PRECISION NOT NULL,
    games_played  INTEGER NOT NULL DEFAULT 0,
    rank          INTEGER,
    zscore        DOUBLE PRECISION,
    conf_zscore   DOUBLE PRECISION,
    UNIQUE (team_id, season_id, snapshot_date, stat_name)
);

CREATE INDEX idx_team_stat_snaps_season_stat_date
    ON team_stat_snapshots (season_id, stat_name, snapshot_date);

CREATE INDEX idx_team_stat_snaps_team_season_stat
    ON team_stat_snapshots (team_id, season_id, stat_name);
