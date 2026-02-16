-- Drop tournament references
ALTER TABLE games DROP COLUMN IF EXISTS tournament_id;
DROP TABLE IF EXISTS tournaments;

-- Drop unused columns
ALTER TABLE teams DROP COLUMN IF EXISTS city;
ALTER TABLE teams DROP COLUMN IF EXISTS state;
ALTER TABLE games DROP COLUMN IF EXISTS attendance;

-- Add ESPN ID columns to existing tables
ALTER TABLE teams ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE teams ADD COLUMN abbreviation VARCHAR(20);
ALTER TABLE teams ADD COLUMN slug VARCHAR(255);
ALTER TABLE teams ADD COLUMN color VARCHAR(10);
ALTER TABLE teams ADD COLUMN alternate_color VARCHAR(10);
ALTER TABLE teams ADD COLUMN active BOOLEAN DEFAULT true;
ALTER TABLE teams ADD COLUMN logo_url VARCHAR(500);

ALTER TABLE conferences ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE conferences ADD COLUMN logo_url VARCHAR(500);

ALTER TABLE games ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE games ADD COLUMN scrape_date DATE;

-- Scrape batch tracking
CREATE TABLE scrape_batches (
    id BIGSERIAL PRIMARY KEY,
    season_year INTEGER NOT NULL,
    scrape_type VARCHAR(50) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    records_created INTEGER DEFAULT 0,
    records_updated INTEGER DEFAULT 0,
    dates_succeeded INTEGER DEFAULT 0,
    dates_failed INTEGER DEFAULT 0,
    error_message TEXT
);

-- Season statistics (from standings endpoint)
CREATE TABLE season_statistics (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    season_id BIGINT NOT NULL REFERENCES seasons(id),
    conference_id BIGINT NOT NULL REFERENCES conferences(id),
    wins INTEGER,
    losses INTEGER,
    conference_wins INTEGER,
    conference_losses INTEGER,
    home_wins INTEGER,
    home_losses INTEGER,
    road_wins INTEGER,
    road_losses INTEGER,
    points_for INTEGER,
    points_against INTEGER,
    streak INTEGER,
    conference_standing INTEGER,
    UNIQUE(team_id, season_id)
);

-- Admin users for scraping control panel
CREATE TABLE admin_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_must_change BOOLEAN DEFAULT false
);

-- Indexes
CREATE INDEX idx_scrape_batches_season ON scrape_batches(season_year);
CREATE INDEX idx_scrape_batches_status ON scrape_batches(status);
CREATE INDEX idx_season_statistics_team ON season_statistics(team_id);
CREATE INDEX idx_season_statistics_season ON season_statistics(season_id);
