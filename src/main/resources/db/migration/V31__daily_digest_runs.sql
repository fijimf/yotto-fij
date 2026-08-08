-- Idempotence guard for the daily digest email job: one row per calendar day the
-- job has run. INSERT ... ON CONFLICT DO NOTHING claims the day atomically, so a
-- restart (or a second node) can't double-send.
CREATE TABLE daily_digest_runs (
    run_date   DATE PRIMARY KEY,
    sent_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
