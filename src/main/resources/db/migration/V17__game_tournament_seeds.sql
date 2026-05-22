-- Tournament seeds for the home / away team, parsed from ESPN scoreboard
-- competitors[i].tournamentMatchup.seed. Populated for NCAA Tournament games only;
-- conference tournaments and NIT do not expose seeds on this endpoint.
ALTER TABLE games
    ADD COLUMN home_seed INTEGER,
    ADD COLUMN away_seed INTEGER;
