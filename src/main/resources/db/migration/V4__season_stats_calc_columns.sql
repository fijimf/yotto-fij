-- Calculated stat columns (derived from game data)
ALTER TABLE season_statistics ADD COLUMN calc_wins INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_losses INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_conference_wins INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_conference_losses INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_home_wins INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_home_losses INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_road_wins INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_road_losses INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_points_for INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_points_against INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_streak INTEGER;
ALTER TABLE season_statistics ADD COLUMN calc_last_updated TIMESTAMP;

-- Performance indexes
CREATE INDEX idx_games_season_status ON games(season_id, status);
CREATE INDEX idx_games_home_team_season ON games(home_team_id, season_id);
CREATE INDEX idx_games_away_team_season ON games(away_team_id, season_id);
