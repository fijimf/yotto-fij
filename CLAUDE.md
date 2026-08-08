# Yotto Fij - College Basketball Statistics and Analysis

## Project Overview

Spring Boot 3.2.2 supporting both a REST API as well as a rich web UI powered by Thymeleaf and HTMX for college basketball statistics. Java 17, PostgreSQL 16, Flyway migrations. Data is populated by scraping ESPN's public JSON APIs.
Deployed on a server running Ubuntu 24.04.4 LTS.

**Artifact:** `deepfij` (0.0.1-SNAPSHOT)
**Base package:** `com.yotto.basketball`

Operator runbook for the ML model system (training, evaluation, promotion, what the admin UI should show): [ADMIN_MANUAL.md](ADMIN_MANUAL.md)

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

## UI
- Thymeleaf templates in `src/main/resources/templates`
- HTMX for AJAX requests and live scrape status polling
- CSS in `src/main/resources/static/css`
- JS in `src/main/resources/static/js`
- Admin panel at `/admin` (requires authentication)
See [UI.md](UI.md) for more details and guidelines.

### Entities
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
- **Snapshot entities** - TeamSeasonStatSnapshot (wide per-team daily stats), TeamPowerRatingSnapshot + PowerModelParamSnapshot (power models: MASSEY, MASSEY_TOTALS, BRADLEY_TERRY, BRADLEY_TERRY_W, plus ADJ_OFF/ADJ_DEF per-possession adjusted efficiencies from AdjustedEfficiencyRatingService — needs team_game_stats), SeasonPopulationStat (long-format population distributions, shared across services via stat-name-scoped deletes), TeamStatSnapshot (long-format derived stats, e.g. four factors — new stats are registry entries in BoxScoreStatCalculator, not migrations)
- **StatCalcWatermark** - per-season record of the last stats calc run; drives skip/incremental recalculation via Game.updatedAt change detection
- **PredictionEvaluation** - one row per FINAL game × model (MASSEY/MASSEY_TOTALS/BRADLEY_TERRY/BRADLEY_TERRY_W/ML:&lt;slug&gt;/BOOK benchmark): pre-game prediction + error vs. actual, built retroactively from snapshot time series (leakage-free) by PredictionEvaluationService — runs incrementally after scrapes and on demand from /admin; powers the public `/predictions/performance` page. Note: `betting_odds.spread` is handicap orientation (negative = home favored)
- **News entities** (V29, docs/NEWS_MODULE.md) - NewsSource (RSS feeds: authority weight, dedicated-CBB flag, health/auto-disable state), NewsArticle (link/title/snippet/thumbnail only — body text is NEVER persisted; simhash + duplicate_of self-reference for wire-story clustering; hidden flag), NewsArticleTeam/NewsArticleConference (tags with confidence + matched_via; manual rows survive retags), NewsAlias (gazetteer: AUTO seeded from teams/conferences + MANUAL/BLOCK curation; ambiguous aliases need corroboration). Pipeline: NewsScrapeService polls feeds every 30 min (`news.enabled`, default false) → canonicalize → sport filter (WBB/football excluded) → Aho-Corasick tagging → SimHash dedup (threshold 10) → thumbnail cache. Public: `/news`, front-page panel, team/conference sections, `/news/img/{id}`
- **MlModel** - registry of named ML bundles (`/models/<slug>/` = 3 ONNX files + features.json manifest whose ordered feature list drives vector assembly via `MlFeatureRegistry`): status ACTIVE (public) / CANDIDATE (shadow-evaluated only) / RETIRED + is_default. First model auto-promotes; later ones arrive as candidates. Feature sets: baseline (27), pace-v2 (41, adds box-score/RPI features), prior-v3 (69, adds preseason priors, full four factors, SOS/consistency, rolling-10, Massey-residual form), eff-v4 (77, adds ADJ_OFF/ADJ_DEF adjusted-efficiency ratings + matchup features) — mirrored by name in `scripts/train_models.py`'s registry; adding a feature = one supplier entry on each side (order is append-only; `MlFeatureRegistryTest` holds the golden list)

### Admin Endpoints
- `GET /admin` - dashboard with season management and scrape controls
- `POST /admin/seasons` - add a season
- `DELETE /admin/seasons/{year}` - remove a season
- `POST /admin/scrape/full/{year}` - trigger full season scrape (async)
- `POST /admin/scrape/current/{year}` - trigger current season re-scrape (async)
- `POST /admin/scrape/odds/{year}` - trigger odds backfill (async)
- `GET /admin/scrape-history` - HTMX fragment for live scrape status
- `POST /admin/ml/reload` - rescan/reload all model bundles; `POST /admin/ml/evaluate[/rebuild]` - (re)build prediction evaluations (async, all seasons)
- `POST /admin/ml/train` (params modelSlug, featureSet; optional spreadTarget margin|residual_massey, winprobMode classifier|derived, tune (Optuna trials), seasonDecay) - train a named model on the trainer service; `GET /admin/ml/training-status` - HTMX-polled run history (completion auto-reloads bundles + re-runs evaluation). Runs recorded in ml_training_runs
- `POST /admin/ml/models/{slug}/promote|activate|retire|reinstate` - model lifecycle (ml_models registry, V27)
- `POST /admin/phase` - force/clear a season-phase override for previewing phase-aware UI (in-memory, resets on restart); current phase shown on the dashboard
- `GET /admin/users` - user management (search, lock/unlock, role, resend verification, trigger reset, delete)
- `/admin/news/*` - news module admin: `sources` (CRUD + feed dry-run test + Poll Now), `tagging` (untagged queue with tag-and-create-alias, near-miss review, alias browser + reseed), `articles` (browse/hide/break-cluster/refetch), `POST retag` (async, add-only over titles/snippets)

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

## Deployment & Monitoring

Deploy/monitoring runbook lives in the `server-ops` skill (`.claude/skills/server-ops/SKILL.md`): Docker Compose topology, `deploy.sh`, Netdata configs and invariants. Safety invariant that always applies: management port **8081** (Actuator/Micrometer) is internal-only — never publish or proxy it.

## Testing

- Integration tests extend `BaseIntegrationTest` which provides a shared Testcontainers PostgreSQL instance
- Flyway runs automatically in tests
- Requires Docker to be running
- Scraper tests use `@MockBean` for `EspnApiClient` to avoid real API calls
- `@DirtiesContext(AFTER_EACH_TEST_METHOD)` on scraper tests for context isolation
- `@BeforeEach` cleanup for database isolation between tests
- Surefire configured with `-Dnet.bytebuddy.experimental=true` for Java 23 compatibility

## Key Conventions

- The front page is composed by `HomePageService` per phase: an ordered list of `HomePanel(fragment, model)` rendered from `templates/fragments/home/`; panel builders return empty (never an empty shell) on missing data. Game panels rank by `HomeInterestScore` (top-6, marquee-filtered to power conferences + RPI top 50, falling back to all games when the filter would empty a panel). `/about` holds the count tiles + attribution. Dev preview: boot with `--app.home.force-phase=` / `--app.home.force-date=` or use the /admin override
- Followed teams: `FavoriteTeamService` (CSV in `favorite.team-ids` pref, cap 10) drives the phase-aware "Your Teams" front-page strip and the ☆ Follow buttons (`POST /teams/{id}/follow|unfollow`, HTMX fragment swap, auth required; manage list on /account). Thymeleaf gotcha: a ternary inside `@{...}` concatenation renders a garbage URL — hoist it into `th:with`
- POSTSEASON front page: bracket anchor (Final Four/championship center via `fragments/bracket` sub-fragments), seeded tournament results with NIT/CBI/Crown collapsed to scores-only, next-round slate, Selection Sunday conf-champ panel with auto-bid badges; EPILOGUE: `SeasonWrapService` superlatives (cached per season for process lifetime — `clearCache()` to recompute) + pinned title game
- Daily digest email: `DailyDigestJob` renders the same front-page composition per user (`HomePageService.buildDigest` — no queries of its own) and sends to users opted into `email.daily-update`. Off by default (`app.digest.enabled`), cron 12:30 UTC, `daily_digest_runs` (V31) claims each day atomically so restarts can't double-send; empty digests (archive trivia only) are skipped
- `SeasonPhaseService` is the single source of truth for "where are we in the basketball calendar" (OFFSEASON/PRESEASON/IN_SEASON/POSTSEASON/EPILOGUE + confTourneyWeek/selectionSunday flairs, cached ~10 min, exposed to every view as `seasonPhase` via `SeasonPhaseModelAdvice`); only NCAA_TOURNAMENT games drive postseason detection — NIT/CBI/Crown are ignored. Don't re-derive season/date logic in controllers. Landing-page redesign spec: docs/LANDING_PAGE_SPEC.md + docs/LANDING_PAGE_IMPLEMENTATION_PLAN.md
- Inject the Eastern-zoned `Clock` bean (`ClockConfig`) instead of calling `LocalDate.now()` in new code so tests can pin time
- Entities use `@NotNull`/`@NotBlank` for validation; service layer enforces business rules
- ESPN IDs stored as `espnId` (String) with unique constraints for idempotent upserts
- Lazy loading on all `@ManyToOne` and `@OneToOne` relationships
- Services throw `EntityNotFoundException` (-> 404) or `IllegalArgumentException` (-> 400)
- `config/` and `.env` are gitignored — contain server-specific config and secrets
