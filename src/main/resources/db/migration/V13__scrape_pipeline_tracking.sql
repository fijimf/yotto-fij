-- Pipeline tracking + live progress for scrape_batches.
--
-- pipeline_run_id: UUID shared across all child batches of one orchestrator run
--   (scrapeFullSeason / scrapeCurrentSeason). NULL for ad-hoc Advanced-button
--   runs that touch a single scraper.
-- pipeline_step_order: 1-based ordinal within a pipeline run, so children can
--   be displayed in execution order.
-- current_step: free-form label of what the scraper is currently doing
--   (e.g. "GAMES 2026-02-14", "ODDS 1234/2104"). Updated by the scraper as
--   it iterates; rendered live in the admin dashboard.
-- progress_total: denominator for the progress bar (e.g. total dates in
--   range, total games to backfill). Set once at batch start.
-- source: how the run was triggered. MANUAL = admin button; SCHEDULED =
--   the every-12h cron; AUTO_INITIALIZE = kicked off automatically when a
--   season was added (Phase 3).
ALTER TABLE scrape_batches
    ADD COLUMN pipeline_run_id     UUID,
    ADD COLUMN pipeline_step_order INTEGER,
    ADD COLUMN current_step        TEXT,
    ADD COLUMN progress_total      INTEGER,
    ADD COLUMN source              VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

CREATE INDEX idx_scrape_batches_pipeline_run_id
    ON scrape_batches (pipeline_run_id);
