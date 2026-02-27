-- Time-series snapshots: one row per team × season × date (cumulative stats through that date)
CREATE TABLE team_season_stat_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    team_id                 BIGINT NOT NULL REFERENCES teams(id),
    season_id               BIGINT NOT NULL REFERENCES seasons(id),
    snapshot_date           DATE NOT NULL,
    games_played            INTEGER NOT NULL DEFAULT 0,
    wins                    INTEGER NOT NULL DEFAULT 0,
    losses                  INTEGER NOT NULL DEFAULT 0,
    win_pct                 DOUBLE PRECISION,
    mean_pts_for            DOUBLE PRECISION,
    stddev_pts_for          DOUBLE PRECISION,
    mean_pts_against        DOUBLE PRECISION,
    stddev_pts_against      DOUBLE PRECISION,
    correlation_pts         DOUBLE PRECISION,    -- Pearson r(ptsFor, ptsAgainst); NULL if < 2 games
    mean_margin             DOUBLE PRECISION,    -- mean point differential (ptsFor - ptsAgainst)
    stddev_margin           DOUBLE PRECISION,
    rolling_wins            INTEGER,             -- last 10 games
    rolling_losses          INTEGER,
    rolling_mean_pts_for    DOUBLE PRECISION,
    rolling_mean_pts_against DOUBLE PRECISION,
    -- League-wide z-scores
    zscore_win_pct          DOUBLE PRECISION,
    zscore_mean_pts_for     DOUBLE PRECISION,
    zscore_mean_pts_against DOUBLE PRECISION,
    zscore_mean_margin      DOUBLE PRECISION,
    zscore_correlation_pts  DOUBLE PRECISION,
    -- Conference z-scores
    conf_zscore_win_pct          DOUBLE PRECISION,
    conf_zscore_mean_pts_for     DOUBLE PRECISION,
    conf_zscore_mean_pts_against DOUBLE PRECISION,
    conf_zscore_mean_margin      DOUBLE PRECISION,
    UNIQUE (team_id, season_id, snapshot_date)
);

CREATE INDEX idx_snapshots_season_date ON team_season_stat_snapshots (season_id, snapshot_date);
CREATE INDEX idx_snapshots_team_season  ON team_season_stat_snapshots (team_id, season_id);

-- Population distributions: one row per season × date × stat_name (optionally scoped to a conference)
CREATE TABLE season_population_stats (
    id          BIGSERIAL PRIMARY KEY,
    season_id   BIGINT NOT NULL REFERENCES seasons(id),
    conference_id BIGINT REFERENCES conferences(id),   -- NULL = league-wide
    stat_date   DATE NOT NULL,
    stat_name   VARCHAR(64) NOT NULL,
    pop_mean    DOUBLE PRECISION,
    pop_stddev  DOUBLE PRECISION,
    pop_min     DOUBLE PRECISION,
    pop_max     DOUBLE PRECISION,
    team_count  INTEGER NOT NULL DEFAULT 0
);

-- Two partial unique indexes to handle the nullable conference_id
CREATE UNIQUE INDEX idx_pop_stats_league
    ON season_population_stats (season_id, stat_date, stat_name)
    WHERE conference_id IS NULL;

CREATE UNIQUE INDEX idx_pop_stats_conference
    ON season_population_stats (season_id, conference_id, stat_date, stat_name)
    WHERE conference_id IS NOT NULL;

CREATE INDEX idx_pop_stats_season_date ON season_population_stats (season_id, stat_date);
