-- Enrich non_d1_game_observations with the D-I team's perspective so per-team
-- non-DI W/L records can tie out ESPN's reported season totals against ours.
--
-- All new columns are nullable: existing rows from V12 don't carry this info,
-- and will fill in lazily on the next current-season re-scrape. The fields
-- are populated whenever GameScraper records (or re-records) an observation.
ALTER TABLE non_d1_game_observations
    ADD COLUMN d1_team_id     BIGINT REFERENCES teams(id) ON DELETE CASCADE,
    ADD COLUMN non_d1_espn_id TEXT,
    ADD COLUMN d1_was_home    BOOLEAN,
    ADD COLUMN neutral_site   BOOLEAN,
    ADD COLUMN d1_score       INTEGER,
    ADD COLUMN non_d1_score   INTEGER,
    ADD COLUMN game_status    TEXT,
    ADD COLUMN result         VARCHAR(1);

CREATE INDEX idx_non_d1_obs_team_season ON non_d1_game_observations (d1_team_id, season_year);
