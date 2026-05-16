-- Per-season auto-refresh flag. When true, ScrapeScheduler picks up this
-- season during its cron tick and runs scrapeCurrentSeason on it. When
-- false, the season is left alone (e.g. completed historical years, or
-- temporarily paused during ESPN outages).
--
-- Defaults to FALSE: existing rows must be opted in explicitly by the
-- admin. The addSeason endpoint defaults new seasons to TRUE.
ALTER TABLE seasons
    ADD COLUMN auto_refresh BOOLEAN NOT NULL DEFAULT FALSE;
