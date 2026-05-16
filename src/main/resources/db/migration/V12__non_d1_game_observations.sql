-- Records ESPN scoreboard events that reference a team we don't have in our
-- teams table. These are almost always non-Division-I opponents (D-II/D-III
-- schools that occasionally play a D-I team early in the season). We don't
-- persist them as Games — but we do count them so D-I season totals can be
-- tied out against ESPN's own season-stats page.
--
-- Idempotent: upsert keyed on espn_game_id; last_seen_at tracks re-encounters.
CREATE TABLE non_d1_game_observations (
    id                       BIGSERIAL PRIMARY KEY,
    espn_game_id             TEXT NOT NULL UNIQUE,
    season_year              INTEGER NOT NULL,
    scrape_date              DATE NOT NULL,
    game_date_utc            TIMESTAMP,
    home_espn_id             TEXT NOT NULL,
    away_espn_id             TEXT NOT NULL,
    unknown_team_espn_ids    TEXT NOT NULL,
    first_seen_at            TIMESTAMP NOT NULL,
    last_seen_at             TIMESTAMP NOT NULL
);

CREATE INDEX idx_non_d1_obs_season ON non_d1_game_observations (season_year);
