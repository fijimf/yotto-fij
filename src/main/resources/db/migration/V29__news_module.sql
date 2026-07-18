-- News module (docs/NEWS_MODULE.md): RSS-fed article aggregation with
-- URL + SimHash dedup and gazetteer-based team/conference tagging.
-- Bodies are never stored — only link, title, snippet, image, hashes, tags.

CREATE TABLE news_sources (
    id                   BIGSERIAL PRIMARY KEY,
    name                 TEXT NOT NULL,
    domain               TEXT NOT NULL,
    feed_url             TEXT UNIQUE,
    source_type          VARCHAR(16) NOT NULL DEFAULT 'RSS',
    authority_weight     INT NOT NULL DEFAULT 50,
    dedicated_cbb        BOOLEAN NOT NULL DEFAULT FALSE,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    auto_disabled_at     TIMESTAMP,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_polled_at       TIMESTAMP,
    last_success_at      TIMESTAMP,
    etag                 TEXT,
    last_modified_header TEXT,
    notes                TEXT
);

CREATE INDEX idx_news_sources_domain ON news_sources (domain);

CREATE TABLE news_articles (
    id                       BIGSERIAL PRIMARY KEY,
    url_canonical            TEXT NOT NULL UNIQUE,
    url_original             TEXT NOT NULL,
    -- source_id = publisher (by final domain); discovered_via = the feed that
    -- surfaced the link. They differ for aggregator feeds; source_id may be
    -- null for domains with no news_sources row.
    source_id                BIGINT REFERENCES news_sources (id),
    discovered_via_source_id BIGINT REFERENCES news_sources (id),
    title                    TEXT NOT NULL,
    subtitle                 TEXT,
    image_url                TEXT,
    thumbnail_path           TEXT,
    published_at             TIMESTAMP NOT NULL,
    fetched_at               TIMESTAMP NOT NULL,
    -- simhash null = body too short to hash reliably (participates in URL dedup only)
    simhash                  BIGINT,
    body_token_count         INT NOT NULL DEFAULT 0,
    -- non-null = suppressed duplicate; points at the cluster representative
    duplicate_of_article_id  BIGINT REFERENCES news_articles (id) ON DELETE SET NULL,
    static_score             DOUBLE PRECISION NOT NULL DEFAULT 30,
    tag_status               VARCHAR(16) NOT NULL DEFAULT 'UNTAGGED',
    hidden                   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_news_articles_visible ON news_articles (published_at DESC)
    WHERE duplicate_of_article_id IS NULL AND NOT hidden;
CREATE INDEX idx_news_articles_source ON news_articles (source_id, published_at);
CREATE INDEX idx_news_articles_cluster ON news_articles (duplicate_of_article_id)
    WHERE duplicate_of_article_id IS NOT NULL;

CREATE TABLE news_article_teams (
    id          BIGSERIAL PRIMARY KEY,
    article_id  BIGINT NOT NULL REFERENCES news_articles (id) ON DELETE CASCADE,
    team_id     BIGINT NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    confidence  DOUBLE PRECISION NOT NULL,
    matched_via TEXT,
    manual      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (article_id, team_id)
);

CREATE INDEX idx_news_article_teams_team ON news_article_teams (team_id, article_id);

CREATE TABLE news_article_conferences (
    id            BIGSERIAL PRIMARY KEY,
    article_id    BIGINT NOT NULL REFERENCES news_articles (id) ON DELETE CASCADE,
    conference_id BIGINT NOT NULL REFERENCES conferences (id) ON DELETE CASCADE,
    confidence    DOUBLE PRECISION NOT NULL,
    matched_via   TEXT,
    manual        BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (article_id, conference_id)
);

CREATE INDEX idx_news_article_confs_conf ON news_article_conferences (conference_id, article_id);

-- Gazetteer aliases: exactly one of team_id / conference_id is set.
-- kind: AUTO (reseedable from teams/conferences), MANUAL (admin), BLOCK (never match).
CREATE TABLE news_aliases (
    id             BIGSERIAL PRIMARY KEY,
    alias          TEXT NOT NULL,
    team_id        BIGINT REFERENCES teams (id) ON DELETE CASCADE,
    conference_id  BIGINT REFERENCES conferences (id) ON DELETE CASCADE,
    kind           VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ambiguous      BOOLEAN NOT NULL DEFAULT FALSE,
    case_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by     TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CHECK ((team_id IS NULL) <> (conference_id IS NULL))
);

CREATE UNIQUE INDEX uq_news_aliases_target
    ON news_aliases (alias, COALESCE(team_id, 0), COALESCE(conference_id, 0));
CREATE INDEX idx_news_aliases_team ON news_aliases (team_id);
CREATE INDEX idx_news_aliases_conference ON news_aliases (conference_id);
