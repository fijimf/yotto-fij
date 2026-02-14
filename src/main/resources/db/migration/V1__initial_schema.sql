-- V1__initial_schema.sql
-- Initial database schema for College Basketball Statistics

-- Conferences table
CREATE TABLE conferences (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    abbreviation VARCHAR(50),
    division VARCHAR(100)
);

-- Seasons table
CREATE TABLE seasons (
    id BIGSERIAL PRIMARY KEY,
    year INTEGER NOT NULL UNIQUE,
    start_date DATE,
    end_date DATE,
    description VARCHAR(255)
);

-- Teams table
CREATE TABLE teams (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    mascot VARCHAR(100),
    city VARCHAR(100),
    state VARCHAR(50)
);

-- Conference memberships table (tracks team-conference relationship by season)
CREATE TABLE conference_memberships (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    conference_id BIGINT NOT NULL REFERENCES conferences(id),
    season_id BIGINT NOT NULL REFERENCES seasons(id),
    UNIQUE(team_id, season_id)
);

-- Tournaments table
CREATE TABLE tournaments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    location VARCHAR(255),
    start_date DATE,
    end_date DATE,
    type VARCHAR(50),
    season_id BIGINT REFERENCES seasons(id)
);

-- Games table
CREATE TABLE games (
    id BIGSERIAL PRIMARY KEY,
    home_team_id BIGINT NOT NULL REFERENCES teams(id),
    away_team_id BIGINT NOT NULL REFERENCES teams(id),
    game_date TIMESTAMP NOT NULL,
    venue VARCHAR(255),
    home_score INTEGER,
    away_score INTEGER,
    status VARCHAR(50),
    attendance INTEGER,
    neutral_site BOOLEAN,
    conference_game BOOLEAN,
    season_id BIGINT REFERENCES seasons(id),
    tournament_id BIGINT REFERENCES tournaments(id)
);

-- Betting odds table
CREATE TABLE betting_odds (
    id BIGSERIAL PRIMARY KEY,
    game_id BIGINT UNIQUE REFERENCES games(id),
    spread DECIMAL(5,1),
    over_under DECIMAL(5,1),
    home_moneyline INTEGER,
    away_moneyline INTEGER,
    opening_spread DECIMAL(5,1),
    opening_over_under DECIMAL(5,1),
    last_updated TIMESTAMP,
    source VARCHAR(100)
);

-- Indexes for common queries
CREATE INDEX idx_conference_memberships_team ON conference_memberships(team_id);
CREATE INDEX idx_conference_memberships_conference ON conference_memberships(conference_id);
CREATE INDEX idx_conference_memberships_season ON conference_memberships(season_id);
CREATE INDEX idx_games_home_team ON games(home_team_id);
CREATE INDEX idx_games_away_team ON games(away_team_id);
CREATE INDEX idx_games_season ON games(season_id);
CREATE INDEX idx_games_date ON games(game_date);
CREATE INDEX idx_games_status ON games(status);
CREATE INDEX idx_tournaments_season ON tournaments(season_id);
