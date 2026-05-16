-- Per-game, per-team box score statistics scraped from ESPN's summary endpoint.
-- Two rows per game (home + away). Idempotent upsert keyed on (game_id, team_id).
CREATE TABLE team_game_stats (
    id              BIGSERIAL PRIMARY KEY,
    game_id         BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    team_id         BIGINT NOT NULL REFERENCES teams(id),
    home_away       VARCHAR(4) NOT NULL,
    fg_made         INTEGER,
    fg_attempted    INTEGER,
    fg3_made        INTEGER,
    fg3_attempted   INTEGER,
    ft_made         INTEGER,
    ft_attempted    INTEGER,
    offensive_reb   INTEGER,
    defensive_reb   INTEGER,
    total_reb       INTEGER,
    assists         INTEGER,
    steals          INTEGER,
    blocks          INTEGER,
    turnovers       INTEGER,
    fouls           INTEGER,
    technical_fouls INTEGER,
    flagrant_fouls  INTEGER,
    largest_lead    INTEGER,
    points_in_paint INTEGER,
    fast_break_pts  INTEGER,
    turnover_pts    INTEGER,
    scrape_date     TIMESTAMP NOT NULL,
    CONSTRAINT uk_team_game_stats_game_team UNIQUE (game_id, team_id)
);

CREATE INDEX idx_team_game_stats_team ON team_game_stats(team_id);
CREATE INDEX idx_team_game_stats_game ON team_game_stats(game_id);
