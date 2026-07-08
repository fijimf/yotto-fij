-- Full user account system (docs/USER_SYSTEM_SPEC.md).
-- Replaces the single-admin admin_users table with a unified users table,
-- plus one-time tokens, key/value preferences, remember-me tokens, and a
-- security audit trail.

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(30)  NOT NULL,
    -- Nullable ONLY for the bootstrap admin; required for self-registered users
    -- (enforced in the service layer).
    email                 VARCHAR(254),
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',
    -- true once email is verified (bootstrap admin: true)
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
    -- admin-set, indefinite until unlocked
    locked                BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    -- automatic temporary lockout; NULL = not temp-locked
    lockout_expires_at    TIMESTAMPTZ,
    password_must_change  BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified_at     TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness for both identifiers.
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower    ON users (lower(email)) WHERE email IS NOT NULL;

-- One table for all one-time tokens (email verification, password reset, email change).
CREATE TABLE user_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 hex of the raw token; the raw token is never stored
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    type        VARCHAR(30) NOT NULL,
    -- for EMAIL_CHANGE: the new email address
    payload     VARCHAR(254),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_tokens_user_type ON user_tokens (user_id, type);

-- Skinny key/value preference store.
CREATE TABLE user_preferences (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100)  NOT NULL,
    pref_value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_pref UNIQUE (user_id, pref_key)
);

-- Spring Security's standard persistent remember-me schema (JdbcTokenRepositoryImpl).
CREATE TABLE persistent_logins (
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) PRIMARY KEY,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);

-- Security audit trail.
CREATE TABLE user_audit_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    -- as entered; may not match a real user (e.g. failed logins)
    username    VARCHAR(254),
    event_type  VARCHAR(40) NOT NULL,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(255),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_user_created ON user_audit_events (user_id, created_at);
CREATE INDEX ix_audit_created ON user_audit_events (created_at);

-- Migrate the existing admin account(s). The {bcrypt} prefix makes the hash
-- readable by DelegatingPasswordEncoder, which replaces the raw BCryptPasswordEncoder.
INSERT INTO users (username, email, password_hash, role, enabled, locked,
                   password_must_change, created_at, updated_at)
SELECT username,
       NULL,
       '{bcrypt}' || password_hash,
       'ADMIN',
       TRUE,
       FALSE,
       COALESCE(password_must_change, FALSE),
       now(),
       now()
FROM admin_users;

DROP TABLE admin_users;
