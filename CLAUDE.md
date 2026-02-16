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

### Entities (8)
- **Conference** - name (unique), abbreviation, division, espnId (unique), logoUrl
- **Season** - year (unique), startDate, endDate
- **Team** - name, mascot, abbreviation, slug, espnId (unique), color, alternateColor, active, logoUrl
- **ConferenceMembership** - links Team+Conference+Season (unique per team+season)
- **Game** - homeTeam, awayTeam, gameDate, scores, status (enum: SCHEDULED, IN_PROGRESS, FINAL, POSTPONED, CANCELLED), espnId (unique), venue, neutralSite, scrapeDate
- **BettingOdds** - spread, overUnder, moneylines, opening spread/OU (OneToOne with Game)
- **ScrapeBatch** - tracks scraping operations: type, status (RUNNING/COMPLETED/FAILED/PARTIAL), record counts, date tracking
- **SeasonStatistics** - aggregate stats per team per season: wins, losses, conference/home/road splits, points, streak, conferenceStanding
- **AdminUser** - username, passwordHash, passwordMustChange

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
- **ScrapeOrchestrator** - coordinates scrapers in dependency order (conferences -> teams -> standings -> games -> odds)
- **AsyncScrapeService** - @Async wrappers for long-running scrapes
- **ScrapeScheduler** - @Scheduled cron (default every 12h) for automatic current-season re-scraping

### Scraping Conventions
- All scrapers are idempotent (upsert by ESPN ID)
- Per-date error handling: failures on individual dates don't abort the full season scrape (batch marked PARTIAL)
- Rate limiting between API calls to be respectful to ESPN
- ScrapeBatch records track every scrape operation with counts and timing

## Security

- Spring Security with form-based login for `/admin/**` routes only
- Public pages and `/api/**` endpoints do not require authentication
- CSRF disabled for `/api/**` (REST API), enabled for admin pages
- Admin user auto-created on first startup with random password (logged at WARN level)
- `PasswordChangeInterceptor` forces password change on first login
- Passwords must be >= 8 characters

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

Docker Compose with 3 services:
- **db**: PostgreSQL 16-alpine
- **app**: Spring Boot (built with `--platform linux/amd64` for x86 server)
- **nginx**: Reverse proxy on ports 80/443 with Let's Encrypt SSL

Files to copy to server: `.env`, `config/mysite`, `docker-compose.yml`. The `deploy.sh` script handles the full build-transfer-restart cycle.

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
