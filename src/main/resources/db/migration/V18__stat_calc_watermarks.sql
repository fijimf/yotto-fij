-- Audit column for change detection: bumped (via JPA lifecycle hooks) only when a
-- game row actually changes. games.scrape_date is day-granularity and too coarse
-- for 12-hour calc cycles.
ALTER TABLE games ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT now();

CREATE INDEX idx_games_season_updated ON games (season_id, updated_at);

-- One row per season: state of the world as of the last stats calculation, used to
-- skip recalculation entirely (no changes) or scope it to changed dates.
CREATE TABLE stat_calc_watermarks (
    id                   BIGSERIAL PRIMARY KEY,
    season_id            BIGINT NOT NULL UNIQUE REFERENCES seasons(id),
    last_calc_started_at TIMESTAMP NOT NULL,
    final_game_count     INTEGER NOT NULL
);
