# Yotto Fij - College Basketball Statistics and Analysis

## Project Overview

Spring Boot 3.2.2 supporting both a REST API as well as a rich web UI powered by Thymeleaf and HTMX for college basketball statistics. Java 17, PostgreSQL 16, Flyway migrations. Data is populated by scraping ESPN's public JSON APIs.
Deployed on a server running Ubuntu 24.04.4 LTS.

**Artifact:** `deepfij` (0.0.1-SNAPSHOT)
**Base package:** `com.yotto.basketball`

## Build & Run

```bash
# Build
./mvnw clean package

# Run locally (start Postgres first)
./scripts/start-postgres.sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Run tests (uses Testcontainers, needs Docker running)
./mvnw test

# Deploy to server
export DEPLOY_HOST=your-server
./scripts/deploy.sh
```

## Architecture

Standard layered Spring Boot: Controller -> Service -> Repository -> Entity

Additional layers:
- **Scraping** (`scraping/`) - ESPN API client + per-entity scrapers + orchestrator
- **Security** (`security/`) - Admin authentication with Spring Security
- **Config** (`config/`) - Configuration properties, security config, async config

## UI
- Thymeleaf templates in `src/main/resources/templates`
- HTMX for AJAX requests and live scrape status polling
- CSS in `src/main/resources/static/css`
- JS in `src/main/resources/static/js`
- Admin panel at `/admin` (requires authentication)
See [UI.md](UI.md) for more details and guidelines.

### Entities (9)
- **Conference** - name (unique), abbreviation, division, espnId (unique), logoUrl — always the CURRENT branding
- **ConferenceNameHistory** - superseded conference brandings (name/abbr/logo) + lastSeasonYear (inclusive), e.g. WAC through 2026 before the UAC rebrand; display code resolves per-season names via `ConferenceNamingService` (see docs/CONFERENCE_RENAME_PROPOSAL.md)
- **Season** - year (unique), startDate, endDate
- **Team** - name, mascot, abbreviation, slug, espnId (unique), color, alternateColor, active, logoUrl
- **ConferenceMembership** - links Team+Conference+Season (unique per team+season)
- **Game** - homeTeam, awayTeam, gameDate, scores, status (enum: SCHEDULED, IN_PROGRESS, FINAL, POSTPONED, CANCELLED), espnId (unique), venue, neutralSite, scrapeDate
- **BettingOdds** - spread, overUnder, moneylines, opening spread/OU (OneToOne with Game)
- **TeamGameStats** - per-game per-team box score: FG/3PT/FT made-attempted, rebounds (off/def/total), assists, steals, blocks, turnovers, fouls, plus optional pointsInPaint/fastBreak/turnoverPts (unique per game+team)
- **ScrapeBatch** - tracks scraping operations: type, status (RUNNING/COMPLETED/FAILED/PARTIAL), record counts, date tracking
- **SeasonStatistics** - aggregate stats per team per season: wins, losses, conference/home/road splits, points, streak, conferenceStanding
- **User** - username + email (both unique, case-insensitive via lower() indexes), passwordHash, role (ADMIN/USER), enabled (email verified), locked (admin lock) + failed_login_attempts/lockout_expires_at (auto lockout), passwordMustChange
- **UserToken** - one-time tokens (EMAIL_VERIFICATION/PASSWORD_RESET/EMAIL_CHANGE); stores SHA-256 of the token, consumed atomically
- **UserPreference** - skinny user/key/value store (unique per user+key); keys in `PreferenceKeys`
- **UserAuditEvent** - security audit trail (login/lockout/reset/etc.), 90-day retention
- **Snapshot entities** - TeamSeasonStatSnapshot (wide per-team daily stats), TeamPowerRatingSnapshot + PowerModelParamSnapshot (power models), SeasonPopulationStat (long-format population distributions, shared across services via stat-name-scoped deletes), TeamStatSnapshot (long-format derived stats, e.g. four factors — new stats are registry entries in BoxScoreStatCalculator, not migrations)
- **StatCalcWatermark** - per-season record of the last stats calc run; drives skip/incremental recalculation via Game.updatedAt change detection
- **PredictionEvaluation** - one row per FINAL game × model (MASSEY/MASSEY_TOTALS/BRADLEY_TERRY/BRADLEY_TERRY_W/ML/BOOK benchmark): pre-game prediction + error vs. actual, built retroactively from snapshot time series (leakage-free) by PredictionEvaluationService — runs incrementally after scrapes and on demand from /admin; powers the public `/predictions/performance` page. Note: `betting_odds.spread` is handicap orientation (negative = home favored)

### API Endpoints
All REST controllers are at `/api/{resource}` with standard CRUD. Notable custom endpoints:
- `GET /api/teams/search?name=` - case-insensitive search
- `GET /api/conference-memberships/team/{id}/current` - latest membership
- `GET /api/games/date-range?start=&end=` - date range query
- `PUT /api/games/{id}/score?homeScore=&awayScore=` - update score (sets FINAL)

### Admin Endpoints
- `GET /admin` - dashboard with season management and scrape controls
- `POST /admin/seasons` - add a season
- `DELETE /admin/seasons/{year}` - remove a season
- `POST /admin/scrape/full/{year}` - trigger full season scrape (async)
- `POST /admin/scrape/current/{year}` - trigger current season re-scrape (async)
- `POST /admin/scrape/odds/{year}` - trigger odds backfill (async)
- `GET /admin/scrape-history` - HTMX fragment for live scrape status
- `POST /admin/ml/reload` - hot-reload ONNX models; `POST /admin/ml/evaluate[/rebuild]` - (re)build prediction evaluations (async, all seasons)
- `POST /admin/ml/train` - start a training run on the trainer service; `GET /admin/ml/training-status` - HTMX-polled run history (completion auto-reloads models + re-runs evaluation). Runs recorded in ml_training_runs (V26)
- `GET /admin/users` - user management (search, lock/unlock, role, resend verification, trigger reset, delete)

### Error Handling
`GlobalExceptionHandler` returns consistent JSON with timestamp, status, error, message:
- EntityNotFoundException -> 404
- IllegalArgumentException -> 400
- Validation errors -> 400 with field-level details
- Generic exceptions -> 500

## ESPN Scraping System

Data is sourced from ESPN's public JSON APIs. See [SCRAPING.md](SCRAPING.md) for full details.

### Scrapers (in `scraping/`)
- **EspnApiClient** - RestClient wrapper with rate limiting (configurable delay + jitter)
- **ConferenceScraper** - fetches/upserts conferences, skips NCAA D-I parent (groupId "50")
- **TeamScraper** - bulk team scrape + `fetchAndSaveUnknownTeam` for historical/inactive teams
- **StandingsScraper** - standings entries -> ConferenceMembership + SeasonStatistics
- **GameScraper** - `scrapeFullSeason` (Nov 1 - Apr 30, per-date) + `scrapeCurrentSeason` (re-scrape non-final dates). Extracts pre-game odds from scoreboard.
- **OddsBackfillScraper** - backfills odds for final games missing them using ESPN core API
- **GameStatsScraper** - per-game team-level box score backfill via ESPN summary endpoint; one API call per FINAL game; idempotent upsert keyed on (game_id, team_id)
- **ScrapeOrchestrator** - coordinates scrapers in dependency order (conferences -> teams -> standings -> games -> odds -> game stats), then runs the stats calc block: conference-game flag refresh -> StatCalcGateService change detection (skip when nothing changed, watermark-incremental otherwise) -> SeasonGameDataLoader loads games once -> all calculators. Snapshot writes go through SnapshotJdbcWriter (batched JDBC). See docs/STATS_PIPELINE_ANALYSIS.md and docs/STATS_PIPELINE_IMPLEMENTATION_PLAN.md.
- **AsyncScrapeService** - @Async wrappers for long-running scrapes
- **ScrapeScheduler** - @Scheduled cron (default every 12h) for automatic current-season re-scraping

### Scraping Conventions
- All scrapers are idempotent (upsert by ESPN ID)
- Per-date error handling: failures on individual dates don't abort the full season scrape (batch marked PARTIAL)
- Rate limiting between API calls to be respectful to ESPN
- ScrapeBatch records track every scrape operation with counts and timing

## Security

Full user account system — see [docs/USER_SYSTEM_SPEC.md](docs/USER_SYSTEM_SPEC.md) for the complete design.

- Roles: **ADMIN > USER** (RoleHierarchy) + implicit anonymous. `/admin/**` needs ADMIN, `/account/**` needs authentication, everything else (incl. `/api/**`) is public
- Shared form login at `/login` (accepts username **or** email); HTTP Basic stays enabled for scripts (`retrain.sh`); persistent remember-me (30 days, `persistent_logins` table)
- Self-service: register → email verification → login, forgot/reset password, change password/email, self-delete, preferences — all on `/account`; admin user management at `/admin/users`
- Email-enumeration resistance: the app never confirms whether an email has an account (identical responses; the real owner gets an email instead). Usernames ARE revealed as taken
- One-time tokens: DB stores SHA-256 only; consumed atomically (single-use); GET shows a confirm page, POST consumes (mail-scanner safety); links built from `APP_BASE_URL` config, never request headers
- Lockout: 10 failed logins → 15-min temp lock (DB-backed) + separate admin `locked` flag; in-memory per-IP rate limits on login/register/forgot (see `RateLimitService`)
- Transactional email via Mailgun SMTP (`app.mail.enabled=true`); dev/test default logs emails instead of sending. Sends are async + post-commit (`MailEventListener`)
- Admin user auto-created on first startup with random password (logged at WARN); `ADMIN_EMAIL` env backfills its email; `PasswordChangeInterceptor` (site-wide) forces password change when flagged
- Passwords 8-64 chars, must not equal username/email; `DelegatingPasswordEncoder` ({bcrypt}-prefixed hashes)
- CSRF enabled everywhere except `/api/**`; nightly `UserMaintenanceJob` purges expired tokens, 7-day-old unverified accounts, 90-day-old audit rows, stale remember-me tokens

## Database

- PostgreSQL 16, managed by Flyway
- Migrations: `src/main/resources/db/migration/` (V1 initial schema, V2 scraping schema)
- DDL mode: `validate` (Flyway owns the schema, Hibernate only validates)
- Credentials are in `.env` (gitignored), never hardcoded

## Configuration

- **Secrets**: All in `.env` file (see `.env.example`). Properties files use `${ENV_VAR:default}` syntax.
- **Profiles**: `dev` (debug logging), `test` (Testcontainers)
- **Server port**: 8080
- **Scraping config**: `espn.scraping.*` properties (base-delay-ms, jitter-ms, season dates, schedule cron)

## Deployment

Docker Compose services:
- **db**: PostgreSQL 16-alpine
- **app**: Spring Boot (built with `--platform linux/amd64` for x86 server)
- **trainer**: always-on ML trainer service (FastAPI, `scripts/trainer_service.py`) on the internal network only — the app POSTs `http://trainer:8000/train`; training runs `train_models.py` as a subprocess, writing ONNX models to the shared `model_data` volume
- **nginx**: Reverse proxy on ports 80/443 with Let's Encrypt SSL
- **goaccess**: real-time analytics from the nginx access log (port 8888, TLS + basic auth)
- **netdata**: monitoring (see below)
- **logrotate**: alpine sidecar running `scripts/rotate-logs.sh` — copy-truncate rotation of `goaccess.log`/`access_timed.log` at 50MB, 3 gzipped generations (no signals needed; nginx/goaccess/netdata tolerate truncation)

Files to copy to server: `.env`, `config/mysite`, `docker-compose.yml`, `netdata/`. The `deploy.sh` script handles the full build-transfer-restart cycle.

## Monitoring (Netdata)

Spec: `docs/monitoring-spec-netdata.md`. Netdata runs as a compose service; configs live in `netdata/` (go.d collector configs, `health.d/` alarms, ntfy notification override). Key invariants:
- Actuator/Micrometer metrics are on management port **8081** — internal-only, scraped by netdata's prometheus collector; never publish or proxy it. Public `/actuator/` is 404'd in nginx.
- The `netdata` Postgres role (pg_monitor, no table access) is created by Flyway **V21**; its password comes from `NETDATA_DB_PASSWORD` via a Flyway placeholder. Changing the password later requires a manual `ALTER ROLE`.
- `netdata/go.d/postgres.conf` is **rendered from postgres.conf.template by deploy.sh** on the server (embeds the DB password; gitignored).
- nginx writes a second timed access log (`access.log` with `rt=`/`urt=` fields) for netdata's web_log collector; `goaccess.log` must stay strict COMBINED for goaccess.
- Dashboard: `127.0.0.1:19999` on the server (ssh tunnel), or remotely via nginx on port **9999** (TLS + basic auth via existing htpasswd; port must be open in the Hetzner firewall).
- Alerts push to ntfy.sh (`NETDATA_NTFY_TOPIC_URL` in `.env`); subscribe to the topic in the ntfy phone app.

## Testing

- Integration tests extend `BaseIntegrationTest` which provides a shared Testcontainers PostgreSQL instance
- Flyway runs automatically in tests
- Requires Docker to be running
- Scraper tests use `@MockBean` for `EspnApiClient` to avoid real API calls
- `@DirtiesContext(AFTER_EACH_TEST_METHOD)` on scraper tests for context isolation
- `@BeforeEach` cleanup for database isolation between tests
- Surefire configured with `-Dnet.bytebuddy.experimental=true` for Java 23 compatibility

## Key Conventions

- Entities use `@NotNull`/`@NotBlank` for validation; service layer enforces business rules
- ESPN IDs stored as `espnId` (String) with unique constraints for idempotent upserts
- Lazy loading on all `@ManyToOne` and `@OneToOne` relationships
- Services throw `EntityNotFoundException` (-> 404) or `IllegalArgumentException` (-> 400)
- `config/` and `.env` are gitignored — contain server-specific config and secrets
