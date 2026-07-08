# Spec: Full User Account System

**Status:** IMPLEMENTED 2026-07-07 (all three phases in one pass; V22 migration). Open questions resolved in §16 comments — Mailgun SMTP, open registration, silent forgot-password, immutable usernames, no HIBP, self-service deletion included, no CAPTCHA (honeypot + rate limits), first preference `email.daily-update`, admin email via `ADMIN_EMAIL` env (applied by `UserInitializer` at startup rather than hardcoded in the migration).
**Date:** 2026-07-07

## 1. Goal

Add a complete, secure end-user account system to the site:

- Self-service **register → email confirm → login → logout**, plus **password reset**, **password change**, **email change**, and **account lockout** (automatic and admin-driven).
- Exactly three effective roles: **admin**, **user**, **anonymous**. For now, `user` and `anonymous` are *nearly invisible* — a logged-in user gets an account page and a preference store, nothing else on the site changes.
- **Uniqueness invariant:** one username per email, one email per username. Both unique, both case-insensitive.
- A **skinny user preference table** (`user_id` / `key` / `value`) for future profile and preference data.
- The existing single-admin system is **unified into** the new system (one `users` table, one `UserDetailsService`), not run in parallel.

Non-goals (for now): OAuth/social login, 2FA/TOTP, API tokens for users, user-visible features beyond the account page itself, multi-instance session sharing.

---

## 2. Current state (summary of findings)

- Auth today is a single hard-coded `admin` account in `admin_users` (created by `V2`, columns: id, username, password_hash, password_must_change). `SecurityConfig` (in `config/`) protects `/admin/**` with form login at `/admin/login` + HTTP Basic (used by `retrain.sh`); everything else is public. CSRF is on except `/api/**`.
- Supporting beans in `security/`: `AdminUserDetailsService`, `AdminUserInitializer` (random password on first boot, logged at WARN), `LoginAttemptService` (in-memory, 5 attempts / 15-min lock), `LoginAttemptListener`, `PasswordChangeInterceptor` (forces first-login password change).
- **No email capability at all** — no `spring-boot-starter-mail`, no SMTP config.
- **No** `thymeleaf-extras-springsecurity6`; the nav has no auth state and no logout link.
- `GlobalExceptionHandler` is `@RestControllerAdvice` (JSON-only) — MVC page errors need separate handling.
- Highest Flyway migration is **V21**; `ddl-auto=validate`, so all schema ships as migrations. JaCoCo enforces 80% line coverage.

---

## 3. Data model (Flyway `V22__user_accounts.sql`)

### 3.1 `users`

Replaces `admin_users`.

```sql
CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(30)  NOT NULL,
    email                 VARCHAR(254),                 -- nullable ONLY for bootstrap admin; required for self-registered users (enforced in service layer)
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- 'ADMIN' | 'USER'
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,    -- true once email verified (admin bootstrap: true)
    locked                BOOLEAN      NOT NULL DEFAULT FALSE,    -- admin-set, indefinite until unlocked
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    lockout_expires_at    TIMESTAMPTZ,                            -- automatic temporary lockout; NULL = not temp-locked
    password_must_change  BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified_at     TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower    ON users (lower(email)) WHERE email IS NOT NULL;
```

Notes:
- **Case-insensitivity** via `lower()` functional unique indexes — no `citext` extension needed. Username case is preserved for display; email is normalized to lowercase at the service layer before save (belt and braces).
- `role` is a single column, not a join table. Three roles only; a user has exactly one. `anonymous` is never stored — it's Spring Security's implicit `ROLE_ANONYMOUS`.
- Two distinct lock concepts: `locked` (admin action, indefinite) and `lockout_expires_at` (automatic, from failed attempts, self-expiring). Both must be false/expired to log in.
- The migration also **migrates the existing `admin_users` row(s)** into `users` (`role='ADMIN'`, `enabled=true`, `email=NULL`, prefix the hash with `{bcrypt}` — see §6.2) and then **drops `admin_users`**. Code switches over in the same deploy, so no parallel-run window.

### 3.2 `user_tokens`

One table for all one-time tokens (email verification, password reset, email change).

```sql
CREATE TABLE user_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,           -- SHA-256 hex of the raw token; raw token is NEVER stored
    type        VARCHAR(30) NOT NULL,                  -- 'EMAIL_VERIFICATION' | 'PASSWORD_RESET' | 'EMAIL_CHANGE'
    payload     VARCHAR(254),                          -- for EMAIL_CHANGE: the new email address
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_tokens_user_type ON user_tokens (user_id, type);
```

- Raw token: **32 random bytes from `SecureRandom`, base64url-encoded** (43 chars), sent only in the email link. DB stores the SHA-256 hex. Lookup is by hash — constant-time by construction, and a DB leak exposes no usable tokens.
- Single-use enforcement is **atomic**: `UPDATE user_tokens SET used_at = now() WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()` — consume-and-check in one statement; 0 rows updated means invalid/expired/used.
- Issuing a new token of a given type **invalidates prior unused tokens of the same type** for that user (sets `used_at`), so only the most recent link works.
- Expiries: EMAIL_VERIFICATION **24 h**, PASSWORD_RESET **1 h**, EMAIL_CHANGE **1 h**.

### 3.3 `user_preferences`

The skinny key/value store requested.

```sql
CREATE TABLE user_preferences (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100)  NOT NULL,
    pref_value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_pref UNIQUE (user_id, pref_key)
);
```

- Keys are dot-namespaced by convention (`ui.theme`, `team.favorite`). Service enforces: key ≤ 100 chars matching `[a-z0-9._-]+`, value ≤ 2000 chars, **max 100 preferences per user** (abuse guard).
- Upsert semantics (`ON CONFLICT (user_id, pref_key) DO UPDATE`).
- No UI beyond plumbing for now; first consumer TBD (see Open Questions).

### 3.4 `persistent_logins` (remember-me)

Spring Security's standard schema for `PersistentTokenRepository` (series/token pattern — theft-detecting):

```sql
CREATE TABLE persistent_logins (
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) PRIMARY KEY,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);
```

### 3.5 `user_audit_events`

Lightweight security audit trail (also feeds admin UI and debugging).

```sql
CREATE TABLE user_audit_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    username    VARCHAR(254),          -- as entered (may not match a real user, e.g. failed logins)
    event_type  VARCHAR(40) NOT NULL,  -- see below
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(255),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_user_created ON user_audit_events (user_id, created_at);
CREATE INDEX ix_audit_created ON user_audit_events (created_at);
```

Event types: `REGISTERED`, `EMAIL_VERIFIED`, `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `LOGOUT`, `LOCKED_AUTO`, `LOCKED_ADMIN`, `UNLOCKED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `PASSWORD_CHANGED`, `EMAIL_CHANGE_REQUESTED`, `EMAIL_CHANGED`, `ROLE_CHANGED`, `ACCOUNT_DELETED`. Retained **90 days** (scheduled purge, §10). IP comes from the request (nginx already sets forwarded headers; `server.forward-headers-strategy=native` is configured).

---

## 4. Roles & authorization

- **Roles:** `ROLE_ADMIN`, `ROLE_USER`, implicit `ROLE_ANONYMOUS`. A `RoleHierarchy` bean declares `ADMIN > USER`, so admin passes every user check.
- **URL rules** (in `SecurityConfig`, order matters):

| Pattern | Access |
|---|---|
| `/login`, `/register/**`, `/verify/**`, `/forgot-password/**`, `/reset-password/**` | permitAll |
| `/account/**` | authenticated (`hasRole('USER')` via hierarchy) |
| `/admin/**` | `hasRole('ADMIN')` (tightened from today's bare `authenticated`) |
| `/api/**`, `/**` | permitAll (unchanged — public site stays public) |

- **"Nearly invisible":** logged-in users see only (a) a small account/sign-in item at the far right of the nav, (b) `/account`. No other page changes behavior by role. Admins additionally see the existing admin UI.
- Method-level enforcement: `@PreAuthorize("hasRole('ADMIN')")` on admin user-management service methods as defense-in-depth (enable `@EnableMethodSecurity`).

---

## 5. Flows

Every flow below states its enumeration stance, rate limits, and failure modes. General principle: **the site never confirms or denies that an email address has an account**, on any page, error message, or timing side-channel. Usernames, by contrast, are allowed to be revealed as taken (registration is unusable otherwise).

### 5.1 Registration — `GET/POST /register`

Form: username, email, password, confirm password.

Validation (service layer + Bean Validation on a DTO):
- Username: 3–30 chars, `[A-Za-z0-9._-]`, must start with a letter or digit; case-insensitively unique; **reserved-name blocklist** (`admin`, `administrator`, `root`, `system`, `api`, `support`, `moderator`, `anonymous`, `user`, `test`, `yotto`, `deepfij` — constant in code).
- Email: RFC-ish format check (Jakarta `@Email` + length ≤ 254), lowercased before storage.
- Password: **8–64 chars** (64 keeps us inside bcrypt's 72-byte limit with UTF-8 headroom); must not equal username or email local-part (case-insensitive). No composition rules (NIST 800-63B: length over complexity).

Behavior:
1. Username taken → inline field error ("username is taken").
2. Email already belongs to an account → **do not reveal on-page.** Proceed to the same "check your email" success page, but instead of a verification mail, send *"Someone tried to register with this address — you already have an account; reset your password if you've forgotten it."*
3. Otherwise: create user (`enabled=false`, `role=USER`, bcrypt hash), issue EMAIL_VERIFICATION token, send verification email, show "check your email" page.
4. **Race:** two concurrent registrations with the same username/email — the DB unique indexes are the source of truth; catch `DataIntegrityViolationException` and re-render as case 1/2. Never rely only on a pre-check `SELECT`.
5. **Mail send failure:** the account row still commits (send happens after commit — see §7); the check-email page always offers a "resend" link, so a lost email is recoverable. The failure is logged at ERROR and audited.

Rate limits (§9): per-IP 5/hour on POST; per-email 3/day.

### 5.2 Email verification — `GET /verify?token=…` then `POST /verify`

- The **GET renders a page with a "Confirm my account" button**; the **POST consumes the token**. Rationale: corporate mail scanners and link-prefetchers issue GETs and would burn single-use tokens; state changes belong on POST anyway (CSRF-protected).
- On success (atomic consume, §3.2): set `enabled=true`, `email_verified_at=now()`, audit `EMAIL_VERIFIED`, redirect to `/login?verified`.
- Invalid/expired/used token → page explaining the link is no longer valid, with a **resend form (enter your email)** that behaves enumeration-safely: always "if that address has an unverified account, we've sent a new link."
- Login attempt before verification → generic failure page variant: "Your account isn't confirmed yet — check your email," with resend link. (Spring throws `DisabledException`; a custom `AuthenticationFailureHandler` maps exception type → message, see 5.3.)

### 5.3 Login — `GET/POST /login`

- **Single shared login page for everyone** (users and admin). `/admin/login` becomes a permanent redirect to `/login` so bookmarks and `retrain.sh`-adjacent muscle memory survive. Login field accepts **username or email** (`UserDetailsService` tries username match, then email match — both lowercased).
- Spring Security form login: `loginProcessingUrl("/login")`, success = **saved-request redirect** (drop today's `defaultSuccessUrl(..., true)`); fallback `/` for users. Session fixation protection stays at the default (`changeSessionId`).
- **Failure messaging** via custom failure handler, carefully tiered to avoid enumeration:
  - Bad username *or* bad password → identical "Invalid username or password." (Spring's `DaoAuthenticationProvider` already does a dummy bcrypt hash for unknown users, equalizing timing.)
  - `DisabledException` (unverified) → "Account not confirmed" + resend link. *(This leaks that the username exists — acceptable: usernames are enumerable by design; the login identifier here was valid.)*
  - `LockedException` → "Account temporarily locked. Try again later or reset your password."
- **HTTP Basic stays enabled** — `retrain.sh` → `/admin/ml/reload` keeps working with the (migrated) admin credentials.
- **Remember-me:** persistent-token remember-me (`JdbcTokenRepositoryImpl` over `persistent_logins`), opt-in checkbox, **30-day validity**, cookie `remember-me` (HttpOnly, Secure, SameSite=Lax). All tokens for a user are purged on password change/reset, lock, and logout-everywhere. Series/token mismatch (theft indicator) → Spring invalidates the series; we audit it.
- On success: update `last_login_at`, reset `failed_login_attempts`, audit `LOGIN_SUCCESS`.

### 5.4 Lockout (automatic) & lock/unlock (admin)

- Failed-attempt counting moves **from memory to the `users` row** so it survives restarts: `AuthenticationFailureBadCredentialsEvent` → increment `failed_login_attempts`; at **10 failures** set `lockout_expires_at = now() + 15 min` and audit `LOCKED_AUTO`. Success or expiry resets the counter. (Existing in-memory `LoginAttemptService` is retired for per-user counting but its shape is reused for per-IP throttling, §9.)
- Counting failures for **nonexistent usernames** stays in-memory per-IP only (no row to write; prevents attacker-driven row churn).
- **Admin lock/unlock** (`/admin/users`): sets `locked` true/false, immediately expires the user's active sessions and remember-me tokens, audits `LOCKED_ADMIN`/`UNLOCKED`. Locking does not delete anything; unlock restores access unchanged.
- A temporary lockout never emails the user by default (avoids weaponized lockout-spam); the message on the login page suffices.

### 5.5 Logout — `POST /logout`

Standard Spring logout: invalidate session, clear `remember-me` cookie and its DB series, delete `JSESSIONID`, redirect `/`, audit `LOGOUT`. The nav shows the logout control only when authenticated (thymeleaf-extras-springsecurity6 `sec:authorize`). Logout is POST (CSRF-protected) — the nav item is a small form styled as a link.

### 5.6 Forgot / reset password

- `GET/POST /forgot-password`: form takes email. **Always** the same response page ("If that address has an account, we've emailed a reset link."). If a user exists: invalidate prior PASSWORD_RESET tokens, issue a new one (1 h), email the link, audit. If not: do nothing (optionally send "no account exists for this address" mail — see Open Questions). Rate limit: 3/hour per email, 10/hour per IP.
- `GET /reset-password?token=…`: validate token *without consuming*; if valid, show new-password form (token in hidden field); if not, "link expired" page linking back to `/forgot-password`.
- `POST /reset-password`: atomically consume token; set new password (same policy as registration); then:
  - reset `failed_login_attempts`, clear `lockout_expires_at` (reset unlocks a temp-locked account — the user proved email control), but **do NOT clear an admin `locked` flag**;
  - if the account was never verified, treat reset as verification (`enabled=true`, `email_verified_at=now()`) — the user just proved email ownership, which is exactly what verification proves;
  - invalidate **all sessions and remember-me tokens** for the user;
  - send a "your password was changed" notification email;
  - audit `PASSWORD_RESET_COMPLETED`; redirect to `/login?reset`.

### 5.7 Change password (logged in) — on `/account`

Requires **current password** re-entry. Same policy checks. On success: rehash, `password_must_change=false`, invalidate the user's *other* sessions (keep the current one) and all remember-me tokens, send notification email, audit `PASSWORD_CHANGED`. The existing `PasswordChangeInterceptor` is generalized: any authenticated user with `password_must_change=true` is forced here before doing anything else (skip list: login, logout, change-password, static assets, `/api/`).

### 5.8 Change email (logged in) — on `/account`

Two-sided flow to keep the uniqueness invariant and prevent hijack:
1. User enters new email + **current password**. If the new email is already taken → *do not reveal*; behave as success (and mail the *holder* of that address the "someone tried to use your email" notice).
2. Issue EMAIL_CHANGE token (1 h) with `payload = new email`; send confirmation link **to the new address**; send a heads-up notice **to the old address** ("if this wasn't you, reset your password").
3. `GET/POST /account/confirm-email?token=…` (GET page + POST consume, as in 5.2): on consume, re-check uniqueness (race window!) inside the same transaction — on conflict, fail with "that address is no longer available." Otherwise swap the email, update `email_verified_at`, audit `EMAIL_CHANGED`.
4. The account keeps working under the old email until confirmation; an unconfirmed change expires silently.

### 5.9 Account page — `GET /account`

Shows username (read-only — see Open Questions), email + verification state, member-since, last login; forms for change password (5.7), change email (5.8); and a "log out everywhere" button (invalidates all sessions + remember-me). Preferences have no visible UI yet.

### 5.10 Admin user management — `/admin/users`

New section (linked from the dashboard header button group, consistent with `/admin/quotes`):
- Paginated, searchable list (username/email), showing role, enabled, locked, failed attempts, last login, created.
- Actions per user: **lock/unlock**, **resend verification**, **trigger password reset email**, **promote/demote role** (USER↔ADMIN; an admin cannot demote or lock **themselves** — prevents locking everyone out), **delete** (hard delete; cascades wipe tokens/preferences; audit rows keep `username` text with `user_id` nulled). Destructive actions get a confirm dialog.
- Admin never sees or sets a user's password directly.

---

## 6. Spring Security integration details

### 6.1 Unified `UserDetailsService`

- `AdminUserDetailsService` → replaced by `AppUserDetailsService`: load by `lower(username)` then `lower(email)`; map to a custom `UserDetails` (wrapping the entity id — controllers need it) with:
  - `authorities` = single role from the row,
  - `enabled` = `users.enabled`,
  - `accountNonLocked` = `!locked && (lockout_expires_at is null or < now())`,
  - `credentialsNonExpired`/`accountNonExpired` = true.
- Let `DaoAuthenticationProvider` do the checks (it maps disabled/locked to the right exceptions and does the dummy-hash timing equalization).

### 6.2 Password encoding

Switch the bean to `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (default bcrypt, but hashes are `{bcrypt}`-prefixed, enabling future algorithm migration without a big-bang rehash). **V22 prefixes the migrated admin hash with `{bcrypt}`** so it keeps validating. New hashes use the default strength (10) — fine for this threat model; the delegating encoder makes upgrading cheap later.

### 6.3 Sessions

- Register `HttpSessionEventPublisher` + a `SessionRegistry` (in-memory — single app instance, matches current architecture) so "invalidate other sessions" (5.6/5.7, admin lock) actually works: iterate the registry, `expireNow()` matching principals, and delete remember-me rows.
- Existing cookie settings (HttpOnly, Secure, SameSite=Lax, 30-min timeout) stay. 30 min idle + remember-me is a good UX/security balance.
- **Known limit:** registry and rate-limit state are in-memory; a restart forgets live sessions' registry entries (sessions themselves die with the JVM anyway — no session persistence today) — acceptable, documented.

### 6.4 CSRF

Unchanged policy: enabled everywhere except `/api/**`. All new mutating endpoints are form POSTs under CSRF.

### 6.5 Web vs JSON error handling

`GlobalExceptionHandler` stays JSON for `/api/**`. New MVC controllers handle their own validation/flow errors via model attributes + re-render or flash + redirect (existing admin pattern). Add a small `@ControllerAdvice(basePackageClasses = …web controllers…)` that maps unexpected exceptions on HTML routes to a friendly error page instead of raw JSON. Custom 403 page for authenticated-but-forbidden (`accessDeniedPage("/error/403")`).

---

## 7. Email infrastructure

- Add `spring-boot-starter-mail`. Config via env (`.env` + `.env.example`):
  `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` (e.g. `noreply@<domain>`), `APP_BASE_URL` (canonical `https://…` — **all email links are built from this property**, never from request headers, to kill host-header-injection in reset links).
- `MailService` interface with two impls: SMTP (prod) and a **logging impl for `dev`/`test` profiles** (writes the would-be email incl. link to the log — makes local testing of flows trivial, no SMTP needed).
- **Sending is async and post-commit**: publish an application event in the transaction; a `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` listener sends. This guarantees (a) no email for a rolled-back registration, (b) an SMTP outage can't fail or slow a user-facing request. One retry after 30 s; on final failure, log ERROR + audit `detail` — every email-dependent flow has a user-reachable "resend" path, so nothing is unrecoverable.
- Templates: Thymeleaf text/HTML pairs under `templates/email/`: `verify`, `password-reset`, `password-changed`, `email-change-confirm`, `email-change-notice`, `already-registered`. Plain, minimal HTML (site name, one sentence, one button/link, expiry note, "ignore if this wasn't you").
- Deliverability (SPF/DKIM/DMARC, provider choice) is an ops task — see Open Questions.

---

## 8. Preferences service (plumbing only, this phase)

- `UserPreferenceService`: `Optional<String> get(userId, key)`, `Map<String,String> getAll(userId)`, `set(userId, key, value)` (upsert, validates §3.3 constraints), `delete(userId, key)`.
- Endpoints under `/account/preferences` (authenticated, CSRF form POSTs): list / set / delete — enough for HTMX-driven UI later. No `/api/**` exposure (the public API stays anonymous and read-only).
- Not a cache and not typed: callers own key naming and value parsing. Add a `PreferenceKeys` constants class the first time a real key ships.

---

## 9. Rate limiting & abuse resistance

Single-instance, in-memory (Caffeine or the existing `ConcurrentHashMap` pattern; no new infra):

| Action | Per IP | Per target |
|---|---|---|
| Login POST | 20 / 5 min | per-user DB counter (§5.4) |
| Register POST | 5 / hour | 3 / day per email |
| Forgot-password POST | 10 / hour | 3 / hour per email |
| Resend verification | 10 / hour | 3 / hour per email |
| Change-email POST | — | 3 / day per user |

- Over-limit → generic "Too many requests, try again later" page (HTTP 429), audited.
- All limits are constants in one `RateLimits` class; not config-tunable yet (YAGNI).
- No CAPTCHA in v1 (see Open Questions). Honeypot field on the registration form (hidden input; bots that fill it get a silent fake success) — cheap and invisible to humans.
- Micrometer counters for `auth.login.success/failure`, `auth.register`, `auth.lockout`, `auth.email.sent/failed` — visible in netdata alongside existing metrics; optional health alarm on failure spikes later.

---

## 10. Background jobs (extend existing `@Scheduled` infra)

Daily cleanup job (`UserMaintenanceJob`, one cron, ~03:00):
1. Delete `user_tokens` where `expires_at < now() - 7 days` (keep a short window for "link expired" UX diagnostics).
2. Delete **never-verified** accounts older than **7 days** (no `email_verified_at`, `enabled=false`, `created_at < now()-7d`) — frees the username/email for honest re-registration and keeps junk out. Audit `ACCOUNT_DELETED` with detail `unverified-purge`.
3. Delete `user_audit_events` older than 90 days.
4. Clear `lockout_expires_at`/`failed_login_attempts` on rows whose lockout has expired (cosmetic tidy; login logic already treats expired lockouts as unlocked).
5. Spring's remember-me repo doesn't self-purge: delete `persistent_logins` where `last_used < now() - 30 days`.

---

## 11. UI

- **Nav** (`fragments/nav.html`): far-right item — anonymous: "Sign in"; authenticated: username linking to `/account` + logout form. Requires `thymeleaf-extras-springsecurity6` dependency and `sec:` namespace. Styling stays subdued per "nearly invisible."
- **New templates** (decorating `layout/default.html`, matching existing form styling): `auth/login.html` (replaces `admin/login.html`), `auth/register.html`, `auth/check-email.html`, `auth/verify.html`, `auth/forgot-password.html`, `auth/reset-password.html`, `auth/link-expired.html`, `account/account.html`, `admin/users.html` (+ HTMX row fragments), `error/403.html`, `error/429.html`.
- Auth pages render in the light/public theme; `/admin/users` inherits the admin dark theme via existing `isAdminPage`.
- All forms: server-side validation errors re-rendered inline (existing flash/model pattern); no JS required for correctness; HTMX only where it already fits (admin user list actions).

---

## 12. Code layout (follows existing top-level conventions)

| Layer | New/changed files |
|---|---|
| `entity/` | `User` (replaces `AdminUser`), `UserToken`, `UserPreference`, `UserAuditEvent` |
| `repository/` | `UserRepository`, `UserTokenRepository`, `UserPreferenceRepository`, `UserAuditEventRepository` |
| `service/` | `UserAccountService` (register/verify/reset/change flows), `UserPreferenceService`, `MailService` (+ `SmtpMailService`, `LoggingMailService`), `UserAuditService`, `UserMaintenanceJob` |
| `security/` | `AppUserDetailsService`, `AppUserDetails`, `UserInitializer` (replaces `AdminUserInitializer`; same random-password bootstrap, now `role=ADMIN, enabled=true`), `LoginRateLimitService` (per-IP; replaces `LoginAttemptService`), auth event listeners, `AuthFailureHandler`, `PasswordChangeInterceptor` (generalized), `TokenService` (generate/hash/consume) |
| `controller/` | `AuthController` (login page/register/verify/forgot/reset), `AccountController`, `AdminUserController`; `AdminAuthController` slimmed (change-password moves to shared flow; `/admin/login` → redirect) |
| `config/` | `SecurityConfig` (rules, remember-me, session registry, role hierarchy, failure handler), `MailConfig` |
| `db/migration/` | `V22__user_accounts.sql` |

Deleted: `AdminUser`, `AdminUserRepository`, `AdminUserDetailsService`, `AdminUserInitializer`, `LoginAttemptService`/`Listener` (superseded), `admin/login.html`.

---

## 13. Failure modes & edge cases (checklist)

- **SMTP down:** requests still succeed (async post-commit send); resend paths recover; ERROR logs + metric for netdata.
- **Duplicate registration race:** DB unique indexes win; `DataIntegrityViolationException` handled as normal duplicate.
- **Token replay / double-click on confirm:** atomic consume → second attempt gets "link no longer valid."
- **Mail-scanner GET prefetch:** tokens consumed only on POST.
- **Reset link forwarded/leaked:** 1-h expiry, single use, invalidated by any newer request, and consuming it kills all sessions.
- **Host-header poisoning of emailed links:** links built from `APP_BASE_URL` config only.
- **Lockout spam (attacker locks a victim):** temp lockout is 15 min and password reset clears it; admin `locked` is separate and manual; per-IP limits slow the attacker.
- **Admin locks/demotes themselves:** blocked at service layer.
- **Last-admin protection:** cannot demote/delete/lock the only remaining enabled admin.
- **User deleted mid-session:** `UserDetailsService` reload fails on next auth-sensitive action; session registry expiry on delete makes it immediate.
- **Email case games** (`Foo@x.com` vs `foo@x.com`): normalized lowercase + `lower()` unique index.
- **Unicode/overlong passwords:** length validated in chars ≤ 64; bcrypt 72-byte limit unreachable.
- **Unverified account squatting a username/email:** 7-day purge frees it; the real owner of the email can also take over via password reset (proves inbox control; reset implies verify, §5.6).
- **Restart wipes in-memory state:** DB-backed lockout persists; rate-limit buckets reset (acceptable); active sessions are lost today anyway (no session persistence — unchanged).
- **Coverage gate:** all services/controllers get integration tests (§14) to keep the 80% JaCoCo gate green.

---

## 14. Testing plan

- Integration tests extend `BaseIntegrationTest` (shared Testcontainers PG); follow the FK-safe cleanup-order convention (new tables: tokens/prefs/audit/persistent_logins before users).
- `MailService` mocked (`@MockBean`) in flow tests; assert on captured messages (recipient, link token extraction to drive verify/reset in the same test).
- Security tests with `spring-security-test` (`@WithMockUser`, `SecurityMockMvc*`): URL matrix (anonymous/user/admin × public/account/admin routes), CSRF on all mutating forms, login/lockout/remember-me behavior.
- Dedicated tests: uniqueness races (constraint handling), token expiry/single-use/atomic-consume, enumeration responses are byte-identical for exists/not-exists email paths, session invalidation on password change, migration V22 result (admin row migrated, `{bcrypt}` prefix works).
- Manual smoke on deploy: register→verify→login→reset with the logging mail impl in dev; `retrain.sh` HTTP Basic against migrated admin.

## 15. Implementation phases

1. **Foundation (deployable alone):** V22 migration, `User` entity + unified `UserDetailsService`/`SecurityConfig`, `/login` page, initializer, DB-backed lockout, audit skeleton. *Admin experience identical; no registration exposed yet.*
2. **Email + registration:** mail infra, register/verify/resend, forgot/reset, notifications, rate limits, cleanup job.
3. **Account & admin UI:** `/account` (change password/email, logout-everywhere), nav auth state, `/admin/users`, remember-me, preferences plumbing.

Each phase ends green (`./mvnw test`) and independently deployable.

---

## 16. Open questions

1. **SMTP provider / sending domain.** What domain does the site serve on, and do you want a transactional provider (Postmark/SES/Mailgun free tiers all fit this volume) vs. Gmail app-password SMTP (fine for a side project, worse deliverability)? Spec assumes plain SMTP creds in `.env` either way; SPF/DKIM setup is on you/DNS.
**Comment** I set up an account on the free tier or Mailgun.  We can try that first.
2. **Registration open or gated?** Spec assumes open self-registration at launch. Alternative: an `INVITE_ONLY` toggle (registration requires an admin-generated invite code) if you want a quiet beta.
**Comment** Open
3. **"No account" forgot-password email:** when a reset is requested for an unknown address, silently do nothing (spec default) or send "no account exists here — maybe you registered with a different address"? The latter is friendlier, costs an email template.
**Comment** Fail silently.
4. **Username immutability.** Spec assumes usernames can't be changed after registration (simplest; avoids identity confusion). Confirm, or should `/account` allow renames (with cooldown)?
**Comment** Immutable
5. **Compromised-password screening** (HaveIBeenPwned k-anonymity API at registration/reset — Spring Security ships `HaveIBeenPwnedRestApiPasswordChecker`). Cheap to add, but introduces an external runtime dependency on an auth path. In or out?
**Comment** Not necessary.
6. **Self-service account deletion** on `/account` (with password re-entry)? Spec currently has admin-only delete. GDPR-ish hygiene suggests yes eventually.
**Comment** Self service deletion, unless its too much trouble.
7. **CAPTCHA:** honeypot + rate limits only (spec default), or add Cloudflare Turnstile on register/forgot now? Depends how much bot traffic the site already sees in goaccess.
**Comment** Not necessary.
8. **First real preference:** what should exercise the preference store first — UI theme? favorite team pinned in nav? (Drives whether phase 3 includes any preference UI.)
**Comment** set a preference for daily update email. default to false.
9. **Admin email:** the migrated admin has `email=NULL`, so admin cannot use password reset (bootstrap re-log fallback: WARN-logged password only on *first ever* boot). Should first login after this ships force the admin to set an email (extending the must-change interceptor)?
**Comment** set it to deppfij@gmail.com
10. 