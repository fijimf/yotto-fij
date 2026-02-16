# Scraping Implementation Plan

This document is the step-by-step implementation plan for the scraping subsystem described in [SCRAPING.md](SCRAPING.md). Each phase builds on the previous one. Phases should be completed in order; steps within a phase can sometimes be parallelized.

---

## Phase 1: Schema Migration & Cleanup

Remove the tournament subsystem and prepare the database for ESPN data.

### Step 1.1 — Remove Tournament from the codebase

Delete the following files:
- `entity/Tournament.java`
- `repository/TournamentRepository.java`
- `service/TournamentService.java`
- `controller/TournamentController.java`

Update files that reference Tournament:
- `entity/Game.java` — remove the `tournament` field, its getter/setter, and the constructor parameter
- `entity/Season.java` — remove the `tournaments` list and its getter/setter
- `service/GameService.java` — remove any tournament references
- `controller/GameController.java` — remove any tournament references

Update CLAUDE.md:
- Remove Tournament from the entity list
- Remove Tournament type enum values
- Update entity count (7 → 6)

### Step 1.2 — Remove city/state from Team

Update `entity/Team.java`:
- Remove `city` and `state` fields, their getters/setters, and the constructor parameters
- Update `toString()`

Update any service/controller code that references city or state.

### Step 1.3 — Remove attendance from Game

Update `entity/Game.java`:
- Remove `attendance` field, getter/setter, and constructor parameter
- Update `toString()`

### Step 1.4 — Flyway migration V2

Create `src/main/resources/db/migration/V2__scraping_schema.sql`:

```sql
-- Drop tournament references
ALTER TABLE games DROP COLUMN tournament_id;
DROP TABLE tournaments;

-- Drop unused columns
ALTER TABLE teams DROP COLUMN city;
ALTER TABLE teams DROP COLUMN state;
ALTER TABLE games DROP COLUMN attendance;

-- Add ESPN ID columns (NOT NULL requires empty table or default — use nullable initially,
-- then make NOT NULL after first scrape, or add with a temporary default)
ALTER TABLE teams ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE teams ADD COLUMN abbreviation VARCHAR(20);
ALTER TABLE teams ADD COLUMN slug VARCHAR(255);
ALTER TABLE teams ADD COLUMN color VARCHAR(10);
ALTER TABLE teams ADD COLUMN alternate_color VARCHAR(10);
ALTER TABLE teams ADD COLUMN active BOOLEAN DEFAULT true;
ALTER TABLE teams ADD COLUMN logo_url VARCHAR(500);

ALTER TABLE conferences ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE conferences ADD COLUMN logo_url VARCHAR(500);

ALTER TABLE games ADD COLUMN espn_id VARCHAR(20) UNIQUE;
ALTER TABLE games ADD COLUMN scrape_date DATE;

-- New tables
CREATE TABLE scrape_batches (
    id BIGSERIAL PRIMARY KEY,
    season_year INTEGER NOT NULL,
    scrape_type VARCHAR(50) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    records_created INTEGER DEFAULT 0,
    records_updated INTEGER DEFAULT 0,
    dates_succeeded INTEGER DEFAULT 0,
    dates_failed INTEGER DEFAULT 0,
    error_message TEXT
);

CREATE TABLE season_statistics (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    season_id BIGINT NOT NULL REFERENCES seasons(id),
    conference_id BIGINT NOT NULL REFERENCES conferences(id),
    wins INTEGER,
    losses INTEGER,
    conference_wins INTEGER,
    conference_losses INTEGER,
    home_wins INTEGER,
    home_losses INTEGER,
    road_wins INTEGER,
    road_losses INTEGER,
    points_for INTEGER,
    points_against INTEGER,
    streak INTEGER,
    conference_standing INTEGER,
    UNIQUE(team_id, season_id)
);

CREATE TABLE admin_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_must_change BOOLEAN DEFAULT false
);

-- Indexes for new tables
CREATE INDEX idx_scrape_batches_season ON scrape_batches(season_year);
CREATE INDEX idx_scrape_batches_status ON scrape_batches(status);
CREATE INDEX idx_season_statistics_team ON season_statistics(team_id);
CREATE INDEX idx_season_statistics_season ON season_statistics(season_id);
```

Note: The `espn_id` columns on teams, conferences, and games are added as nullable initially. Since the table may contain existing rows (from tests or manual entry), making them NOT NULL immediately would fail. They can be made NOT NULL after the first scrape populates them, or a subsequent migration can enforce it.

### Step 1.5 — Update existing entities with new columns

**`entity/Team.java`** — add fields:
- `espnId` (with `@Column(name = "espn_id", unique = true)`)
- `abbreviation`
- `slug`
- `color`
- `alternateColor`
- `active` (with `@Column(columnDefinition = "boolean default true")`)
- `logoUrl`

**`entity/Conference.java`** — add fields:
- `espnId`
- `logoUrl`

**`entity/Game.java`** — add fields:
- `espnId`
- `scrapeDate` (as `LocalDate`)

### Step 1.6 — Create new entities

**`entity/ScrapeBatch.java`** — new entity for `scrape_batches` table with all columns, enum for status (`RUNNING`, `COMPLETED`, `FAILED`, `PARTIAL`), enum for scrape type (`TEAMS`, `CONFERENCES`, `STANDINGS`, `GAMES`).

**`entity/SeasonStatistics.java`** — new entity for `season_statistics` table with ManyToOne references to Team, Season, and Conference.

**`entity/AdminUser.java`** — new entity for `admin_users` table.

### Step 1.7 — Create new repositories

- `repository/ScrapeBatchRepository.java` — add finder methods: `findBySeasonYearOrderByStartedAtDesc()`, `findByStatus()`
- `repository/SeasonStatisticsRepository.java` — add: `findByTeamAndSeason()`, `findBySeason()`
- `repository/AdminUserRepository.java` — add: `findByUsername()`

Add new repository methods to existing repos:
- `TeamRepository` — add: `findByEspnId(String espnId)`
- `ConferenceRepository` — add: `findByEspnId(String espnId)`
- `GameRepository` — add: `findByEspnId(String espnId)`, `findBySeasonAndStatusNot(Season season, GameStatus status)`
- `SeasonRepository` — add: `findByYear(Integer year)`
- `BettingOddsRepository` — add: `findByGame(Game game)`
- `ConferenceMembershipRepository` — add: `findByTeamAndSeason(Team team, Season season)`

### Step 1.8 — Verify

Run `./mvnw clean test` to confirm the migration applies, Hibernate validates, and existing tests pass.

---

## Phase 2: ESPN API Client

Build the HTTP client layer that talks to ESPN. This phase produces no database side effects — it only fetches and parses JSON.

### Step 2.1 — Add dependencies

Add to `pom.xml` (if not already present):
- `spring-boot-starter-webflux` (for `WebClient`, non-blocking HTTP) — OR use `RestTemplate`/`RestClient` if preferred to keep it simpler

Decision point: `RestClient` (new in Spring Boot 3.2, synchronous, simpler) is recommended since scraping is inherently sequential per-date. No need for reactive.

### Step 2.2 — Configuration properties

Add to `application.properties`:

```properties
# ESPN scraping
espn.scraping.base-delay-ms=200
espn.scraping.jitter-ms=100
espn.scraping.season-start-month=11
espn.scraping.season-start-day=1
espn.scraping.season-end-month=4
espn.scraping.season-end-day=30
```

Create `config/ScrapingProperties.java` — a `@ConfigurationProperties(prefix = "espn.scraping")` class to bind these.

### Step 2.3 — Rate-limited HTTP client

Create `scraping/EspnApiClient.java`:
- Wraps `RestClient` (or `RestTemplate`)
- Accepts a URL, returns the JSON response as a `JsonNode` (Jackson)
- Enforces rate limiting: after each request, sleeps for `baseDelay + random(0, jitter)` ms
- Logs each request URL and response status

### Step 2.4 — ESPN JSON DTOs

Create DTO records (or classes) in `scraping/dto/` to represent the ESPN JSON structures. These are **not** JPA entities — they're simple data holders for deserialization:

- `EspnTeamsResponse` — wraps the `sports[0].leagues[0].teams[]` navigation
- `EspnTeamData` — individual team fields (id, location, name, nickname, abbreviation, slug, color, alternateColor, isActive, logos)
- `EspnConferencesResponse` — wraps `conferences[]`
- `EspnConferenceData` — (groupId, name, shortName, logo)
- `EspnStandingsResponse` — wraps `children[]`
- `EspnStandingsEntry` — team + stats within a conference child
- `EspnScoreboardResponse` — wraps `sports[0].leagues[0].events[]`
- `EspnEventData` — game event with competitors, status, odds, location, etc.
- `EspnCompetitor` — id, homeAway, score, record, rank, group
- `EspnOddsResponse` — wraps `items[]` from the core odds endpoint
- `EspnOddsData` — spread, overUnder, moneylines, opening lines, provider

Use Jackson `@JsonIgnoreProperties(ignoreUnknown = true)` on all DTOs to handle fields we don't care about.

### Step 2.5 — Endpoint-specific fetch methods

Add methods to `EspnApiClient`:
- `fetchTeams()` → `List<EspnTeamData>`
- `fetchTeam(String espnId)` → `EspnTeamData`
- `fetchConferences()` → `List<EspnConferenceData>`
- `fetchStandings(int seasonYear)` → `EspnStandingsResponse`
- `fetchScoreboard(LocalDate date)` → `List<EspnEventData>`
- `fetchGameOdds(String espnGameId)` → `EspnOddsData` (nullable)

Each method handles URL construction, calls the rate-limited HTTP method, and deserializes into the appropriate DTO.

### Step 2.6 — Verify

Write a manual integration test (or a `@SpringBootTest` with a live call) that hits each ESPN endpoint and confirms parsing works. This can be `@Disabled` by default so it doesn't run in CI.

---

## Phase 3: Scraping Services

Build the service layer that coordinates fetching from ESPN and persisting to the database.

### Step 3.1 — ConferenceScraper

Create `scraping/ConferenceScraper.java` (`@Service`):
- Calls `espnApiClient.fetchConferences()`
- For each conference: find by `espnId`, create or update
- Returns a count of created/updated
- Creates a `ScrapeBatch` record tracking the operation

### Step 3.2 — TeamScraper

Create `scraping/TeamScraper.java` (`@Service`):
- Calls `espnApiClient.fetchTeams()`
- For each team: find by `espnId`, create or update
- Returns a count of created/updated
- Creates a `ScrapeBatch` record
- Exposes a `fetchAndSaveUnknownTeam(String espnId)` method for the standings scraper to call when it encounters a team not in the bulk response

### Step 3.3 — StandingsScraper

Create `scraping/StandingsScraper.java` (`@Service`):
- Calls `espnApiClient.fetchStandings(seasonYear)`
- Ensures the Season record exists (create if not)
- Iterates conference children:
  - Look up Conference by `espnId`
  - Iterate standings entries:
    - Look up Team by `espnId`; if not found, call `teamScraper.fetchAndSaveUnknownTeam()`
    - Upsert `ConferenceMembership` (match on team + season)
    - Upsert `SeasonStatistics` (match on team + season): parse the stat arrays to extract wins, losses, conference record, home/road splits, points for/against, streak, standing
- Creates a `ScrapeBatch` record

### Step 3.4 — GameScraper

Create `scraping/GameScraper.java` (`@Service`):

**Full season scrape** (`scrapeFullSeason(int year)`):
- Compute date range: Nov 1 of `year-1` through Apr 30 of `year`
- Create a `ScrapeBatch` record with status `RUNNING`
- For each date in range:
  - Try: call `espnApiClient.fetchScoreboard(date)`
  - For each event: upsert game (match on `espnId`), map all fields per SCRAPING.md
    - Look up home/away teams by ESPN ID
    - Look up season by year
    - If scoreboard event has odds (pre-game): upsert `BettingOdds`
  - Track created/updated counts, increment `dates_succeeded`
  - On failure: log error, increment `dates_failed`, continue
- Finalize batch: set status to `COMPLETED`, `PARTIAL`, or `FAILED`

**Current season re-scrape** (`scrapeCurrentSeason(int year)`):
- Find today's date
- Query for games in this season with status != FINAL → collect their `scrapeDate` values
- Build a set of dates to re-fetch: today + those dates
- Fetch each date and upsert (same logic as full season, but smaller date set)

### Step 3.5 — OddsBackfillScraper

Create `scraping/OddsBackfillScraper.java` (`@Service`):
- Query: all games in a given season where `bettingOdds` is null and status = `FINAL`
- For each game:
  - Call `espnApiClient.fetchGameOdds(game.getEspnId())`
  - If odds returned: upsert `BettingOdds` with closing lines, opening lines, provider
  - Rate limited (handled by API client)
- Track counts in a `ScrapeBatch` record

### Step 3.6 — ScrapeOrchestrator

Create `scraping/ScrapeOrchestrator.java` (`@Service`):

Orchestrates a full scrape for a season:
1. `conferenceScraper.scrape()`
2. `teamScraper.scrape()`
3. `standingsScraper.scrape(seasonYear)`
4. `gameScraper.scrapeFullSeason(seasonYear)`
5. `oddsBackfillScraper.backfill(seasonYear)`

Also provides:
- `scrapeCurrentSeason(int year)` — calls standings + game re-scrape + odds backfill (used by scheduler)
- `forceRescrape(int year)` — same as full scrape (upserts over existing data)

### Step 3.7 — Verify

Write integration tests using Testcontainers:
- Mock or stub the ESPN API client (don't hit real ESPN in CI)
- Provide sample JSON fixtures for each endpoint
- Test the full scrape flow: conferences → teams → standings → games → odds
- Verify idempotency: run the same scrape twice, assert no duplicates
- Verify partial failure: simulate one date failing, assert batch is PARTIAL

---

## Phase 4: Security & Authentication

### Step 4.1 — Add Spring Security dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

### Step 4.2 — Security configuration

Create `config/SecurityConfig.java` (`@Configuration`, `@EnableWebSecurity`):

- `/admin/**` — requires authentication (form login)
- Everything else (`/`, `/api/**`, `/teams/**`, `/games/**`, etc.) — permit all
- Login page at `/admin/login`
- Logout redirects to `/`
- CSRF enabled for admin forms (disable for API endpoints if needed)

### Step 4.3 — AdminUser UserDetailsService

Create `security/AdminUserDetailsService.java` (`@Service`, implements `UserDetailsService`):
- Loads admin user from `AdminUserRepository`
- Returns a Spring Security `UserDetails` with the stored BCrypt hash

### Step 4.4 — Admin user initialization

Create `security/AdminUserInitializer.java` (`@Component`, implements `ApplicationRunner`):
- On startup: check if `admin` user exists in `admin_users` table
- If not: generate a random password (e.g., 16 chars alphanumeric), BCrypt-hash it, insert the row with `password_must_change = true`
- Log the generated password at WARN level: `"Admin password generated: {password}. Change it at /admin/change-password"`

### Step 4.5 — Password change

Create `controller/AdminAuthController.java` (`@Controller`):
- `GET /admin/login` — renders login form
- `GET /admin/change-password` — renders password change form
- `POST /admin/change-password` — validates old password, updates hash, sets `password_must_change = false`
- If `password_must_change` is true, redirect to change-password after login (use a Spring Security success handler or an interceptor)

### Step 4.6 — Thymeleaf templates for auth

Create templates:
- `templates/admin/login.html` — login form (username + password)
- `templates/admin/change-password.html` — old password + new password + confirm

Style with the existing dark theme from UI.md.

### Step 4.7 — Verify

- App starts, generates admin password, logs it
- Can log in at `/admin/login`
- Forced to change password on first login
- Public pages remain accessible without auth
- API endpoints remain accessible without auth

---

## Phase 5: Admin Control Panel UI

### Step 5.1 — Admin dashboard controller

Create `controller/AdminController.java` (`@Controller`, `@RequestMapping("/admin")`):

- `GET /admin` — dashboard page showing season list and recent scrape batches
- `POST /admin/seasons` — add a season (year)
- `DELETE /admin/seasons/{year}` — remove a season
- `POST /admin/scrape/full/{year}` — trigger full season scrape (async)
- `POST /admin/scrape/current/{year}` — trigger current season re-scrape (async)
- `POST /admin/scrape/odds/{year}` — trigger odds backfill (async)
- `GET /admin/scrape-history` — HTMX partial returning scrape batch table rows

### Step 5.2 — Async scrape execution

Scraping operations are long-running (a full season scrape hits 181+ URLs). They must run asynchronously:

- Use `@Async` on orchestrator methods, or submit to a `TaskExecutor`
- The admin UI triggers the scrape, immediately returns, and the dashboard shows the batch as `RUNNING`
- The dashboard polls via HTMX (`hx-trigger="every 5s"`) to refresh the batch status table

Create `config/AsyncConfig.java` — configure a `TaskExecutor` for scraping tasks (single-threaded to avoid concurrent scrapes).

### Step 5.3 — Dashboard template

Create `templates/admin/dashboard.html`:

**Season Management Section:**
- Table of seasons with columns: Year, Status (historical/current), Last Scraped, Actions
- "Add Season" form (year input + submit)
- Per-season actions: "Scrape Full", "Re-scrape Current", "Backfill Odds", "Remove"
- Actions are HTMX POST/DELETE requests

**Scrape History Section:**
- Table of recent scrape batches with columns: Season, Type, Started, Duration, Created, Updated, Dates OK/Failed, Status, Error
- Auto-refreshes via HTMX polling
- Error details expandable on click

### Step 5.4 — Schedule management

Add a section to the dashboard for configuring the automatic scrape schedule:
- Current schedule display (e.g., "Every 12 hours" or cron expression)
- Form to update the schedule
- Store the schedule in `application.properties` or in a `scraping_config` database table

### Step 5.5 — Verify

- Admin dashboard loads at `/admin`
- Can add/remove seasons
- Can trigger scrapes from the UI
- Scrape batches appear in the history table
- History auto-refreshes while a scrape is running

---

## Phase 6: Scheduled Scraping

### Step 6.1 — Enable scheduling

Add `@EnableScheduling` to the application class or a config class.

### Step 6.2 — Scheduled task

Create `scraping/ScrapeScheduler.java` (`@Component`):

- `@Scheduled` method that runs on a configurable cron/fixed-rate (default: every 12 hours)
- Determines the "current" season based on today's date
- Calls `scrapeOrchestrator.scrapeCurrentSeason(currentYear)`
- Guarded by a lock/flag to prevent concurrent executions

### Step 6.3 — Verify

- App starts scheduler
- Current season games are re-scraped on schedule
- Manual trigger from admin panel also works
- No concurrent scrape collisions

---

## Phase 7: Update CLAUDE.md & Cleanup

### Step 7.1 — Update CLAUDE.md

Reflect the new state of the project:
- Update entity list (remove Tournament, add ScrapeBatch, SeasonStatistics, AdminUser)
- Add scraping section with overview of the subsystem
- Document new properties (`espn.scraping.*`)
- Document admin panel routes
- Note the Spring Security setup (admin-only routes)

### Step 7.2 — Update existing tests

- Remove Tournament-related test code if any
- Update `BaseIntegrationTest` if it references Tournament
- Ensure all existing tests pass with the new schema

### Step 7.3 — Add scraping tests

Integration tests with mocked ESPN responses:
- Conference scraping: happy path + idempotent re-scrape
- Team scraping: happy path + unknown team lookup
- Standings scraping: conference membership creation + season statistics
- Game scraping: full season + partial failure handling
- Odds backfill: completed games get odds
- Current season re-scrape: only non-final dates re-fetched

### Step 7.4 — Security tests

- Unauthenticated access to `/admin/**` redirects to login
- Unauthenticated access to `/api/**` and `/` succeeds
- Login with correct credentials works
- Password change flow works

---

## File Summary

New files to create:

```
src/main/java/com/yotto/basketball/
├── config/
│   ├── ScrapingProperties.java          # Phase 2.2
│   ├── SecurityConfig.java              # Phase 4.2
│   └── AsyncConfig.java                 # Phase 5.2
├── entity/
│   ├── ScrapeBatch.java                 # Phase 1.6
│   ├── SeasonStatistics.java            # Phase 1.6
│   └── AdminUser.java                   # Phase 1.6
├── repository/
│   ├── ScrapeBatchRepository.java       # Phase 1.7
│   ├── SeasonStatisticsRepository.java  # Phase 1.7
│   └── AdminUserRepository.java         # Phase 1.7
├── scraping/
│   ├── EspnApiClient.java              # Phase 2.3
│   ├── ConferenceScraper.java           # Phase 3.1
│   ├── TeamScraper.java                 # Phase 3.2
│   ├── StandingsScraper.java            # Phase 3.3
│   ├── GameScraper.java                 # Phase 3.4
│   ├── OddsBackfillScraper.java         # Phase 3.5
│   ├── ScrapeOrchestrator.java          # Phase 3.6
│   ├── ScrapeScheduler.java             # Phase 6.2
│   └── dto/
│       ├── EspnTeamsResponse.java       # Phase 2.4
│       ├── EspnTeamData.java            # Phase 2.4
│       ├── EspnConferencesResponse.java # Phase 2.4
│       ├── EspnConferenceData.java      # Phase 2.4
│       ├── EspnStandingsResponse.java   # Phase 2.4
│       ├── EspnStandingsEntry.java      # Phase 2.4
│       ├── EspnScoreboardResponse.java  # Phase 2.4
│       ├── EspnEventData.java           # Phase 2.4
│       ├── EspnCompetitor.java          # Phase 2.4
│       ├── EspnOddsResponse.java        # Phase 2.4
│       └── EspnOddsData.java            # Phase 2.4
├── security/
│   ├── AdminUserDetailsService.java     # Phase 4.3
│   └── AdminUserInitializer.java        # Phase 4.4
└── controller/
    ├── AdminController.java             # Phase 5.1
    └── AdminAuthController.java         # Phase 4.5

src/main/resources/
├── db/migration/
│   └── V2__scraping_schema.sql          # Phase 1.4
└── templates/admin/
    ├── dashboard.html                   # Phase 5.3
    ├── login.html                       # Phase 4.6
    └── change-password.html             # Phase 4.6
```

Files to delete:
```
src/main/java/com/yotto/basketball/
├── entity/Tournament.java
├── repository/TournamentRepository.java
├── service/TournamentService.java
└── controller/TournamentController.java
```

Files to modify:
```
pom.xml                                  # Phase 2.1, 4.1
src/main/resources/application.properties # Phase 2.2
entity/Team.java                          # Phase 1.2, 1.5
entity/Conference.java                    # Phase 1.5
entity/Game.java                          # Phase 1.1, 1.3, 1.5
entity/Season.java                        # Phase 1.1
repository/TeamRepository.java            # Phase 1.7
repository/ConferenceRepository.java      # Phase 1.7
repository/GameRepository.java            # Phase 1.7
repository/SeasonRepository.java          # Phase 1.7
repository/BettingOddsRepository.java     # Phase 1.7
repository/ConferenceMembershipRepository.java # Phase 1.7
CLAUDE.md                                 # Phase 7.1
```
