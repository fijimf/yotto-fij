-- Tournament identification fields on games. Populated by TournamentClassifier from the
-- ESPN scoreboard `note` and `seasonType` fields. NULL tournament_type = regular season.
ALTER TABLE games
    ADD COLUMN tournament_type   VARCHAR(32),
    ADD COLUMN tournament_name   VARCHAR(160),
    ADD COLUMN tournament_round  VARCHAR(64),
    ADD COLUMN tournament_region VARCHAR(32),
    ADD COLUMN espn_season_type  INTEGER,
    ADD COLUMN espn_note_raw     VARCHAR(255);

CREATE INDEX idx_games_tournament_type ON games(tournament_type);
CREATE INDEX idx_games_season_tournament ON games(season_id, tournament_type);
