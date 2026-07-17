-- Admin-authored broadcast emails (downtime notices, feature announcements) sent
-- to every verified, unlocked user. One row per broadcast is the durable record;
-- the actual per-recipient delivery runs asynchronously on a dedicated executor.
CREATE TABLE email_broadcasts (
    id                BIGSERIAL PRIMARY KEY,
    subject           VARCHAR(255) NOT NULL,
    body_markdown     TEXT         NOT NULL,       -- as authored by the admin
    body_html         TEXT         NOT NULL,       -- rendered + sanitized HTML actually sent
    sent_by_username  VARCHAR(30),                 -- admin who triggered it
    status            VARCHAR(20)  NOT NULL,        -- SENDING / SENT / FAILED
    recipient_count   INTEGER      NOT NULL DEFAULT 0,
    sent_count        INTEGER      NOT NULL DEFAULT 0,
    failed_count      INTEGER      NOT NULL DEFAULT 0,
    attachment_count  INTEGER      NOT NULL DEFAULT 0,
    attachment_names  VARCHAR(1000),               -- comma-separated original filenames
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_email_broadcasts_created ON email_broadcasts (created_at DESC);
