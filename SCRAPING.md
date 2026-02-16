# Scraping Data

## Motivation

Game results, team data, and conference data are sourced by scraping publicly available ESPN APIs. This document specifies the data sources, the mapping between ESPN's data model and our database schema, and the operational requirements for the scraping subsystem.

---

## Data Sources

All data comes from ESPN's public JSON APIs for men's college basketball (Division I).

| Data | ESPN API URL | Purpose |
|------|-------------|---------|
| **Teams** | `https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=500` | All current D-I teams |
| **Single Team** | `https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams/{espnTeamId}` | Lookup a team not in the current season (e.g., a team that left D-I) |
| **Conferences** | `https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences` | All D-I conferences |
| **Standings** | `https://site.api.espn.com/apis/v2/sports/basketball/mens-college-basketball/standings?season={year}` | Conference memberships and season statistics for a given season |
| **Scoreboard** | `https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=basketball&league=mens-college-basketball&limit=200&groups=50&dates={yyyyMMdd}` | All games on a given date (includes odds for pre-game events) |
| **Game Odds** | `https://sports.core.api.espn.com/v2/sports/basketball/leagues/mens-college-basketball/events/{espnGameId}/competitions/{espnGameId}/odds` | Betting odds for a specific game (persists after game completion; used to backfill completed games) |

---

## Teams

### API Structure

**URL:** `https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=500`

**Navigation path:** `root.sports[0].leagues[0].teams[i].team`

The API returns all teams for the current season. Teams that previously competed in D-I but no longer do will not appear here. When encountered during a historical season scrape (e.g., via standings), fetch them individually from the single-team endpoint and mark them as `active = false`.

Teams are uniquely identified by their ESPN team ID (a numeric string, e.g., `"333"` for Alabama).

### Field Mapping

| ESPN Field | Type | Example | Maps To | Notes |
|-----------|------|---------|---------|-------|
| `id` | String | `"333"` | `teams.espn_id` | **Primary external key** |
| `location` | String | `"Alabama"` | `teams.name` | School/location name |
| `name` | String | `"Crimson Tide"` | `teams.mascot` | Mascot name |
| `nickname` | String | `"Alabama"` | `teams.nickname` | Usually same as location |
| `abbreviation` | String | `"ALA"` | `teams.abbreviation` | Short display code |
| `slug` | String | `"alabama-crimson-tide"` | `teams.slug` | URL-safe identifier |
| `color` | String | `"9e1632"` | `teams.color` | Primary hex color, no `#` prefix |
| `alternateColor` | String | `"ffffff"` | `teams.alternate_color` | Secondary hex color |
| `isActive` | Boolean | `true` | `teams.active` | Whether team currently competes in D-I |
| `logos[0].href` | String | (CDN URL) | `teams.logo_url` | Default logo image |
| `displayName` | String | `"Alabama Crimson Tide"` | — | Skip (derived from location + name) |
| `uid` | String | `"s:40~l:41~t:333"` | — | Skip (redundant with id) |
| `links` | Array | (ESPN URLs) | — | Skip |

---

## Conferences

### API Structure

**URL:** `https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences`

**Navigation path:** `root.conferences[i]`

Returns ~31 entries. The first entry (`groupId: "50"`, name: "NCAA Division I") is the parent group, not a conference — **skip it**. The remaining 30 entries are actual conferences.

Conferences are uniquely identified by their ESPN group ID (a numeric string, e.g., `"2"` for the ACC).

### Field Mapping

| ESPN Field | Type | Example | Maps To | Notes |
|-----------|------|---------|---------|-------|
| `groupId` | String | `"2"` | `conferences.espn_id` | **Primary external key** |
| `name` | String | `"Atlantic Coast Conference"` | `conferences.name` | Full name |
| `shortName` | String | `"ACC"` | `conferences.abbreviation` | Abbreviation |
| `logo` | String | (CDN URL) | `conferences.logo_url` | Optional; not all conferences have logos |
| `parentGroupId` | String | `"50"` | — | Skip (always "50" for D-I) |
| `uid` | String | `"s:40~l:41~g:2"` | — | Skip |

All conferences from this endpoint are Division I, so `conferences.division` should be set to `"Division I"` for all entries.

---

## Conference Memberships & Season Statistics (Standings)

### API Structure

**URL:** `https://site.api.espn.com/apis/v2/sports/basketball/mens-college-basketball/standings?season={year}`

**Navigation path:** `root.children[i].standings.entries[j]`

The response is structured as a tree: Division I at the root, with `children[]` containing one object per conference. Each conference child has a `standings` object containing `entries[]` — one per team. Each entry has a `team` object and an array of ~84 stat objects organized in 6 groups (overall, home, road, conference, vs. AP Top 25, vs. USA ranked).

This endpoint serves two purposes:
1. **Conference memberships:** A team appearing under a conference child means that team is a member of that conference for that season.
2. **Season statistics:** The stat entries provide end-of-season (or current) aggregate statistics per team.

### Field Mapping — Conference Membership

| ESPN Path | Maps To | Notes |
|----------|---------|-------|
| `children[i].id` | `conference_memberships.conference_id` | Conference ESPN group ID; join to `conferences.espn_id` |
| `children[i].standings.entries[j].team.id` | `conference_memberships.team_id` | Team ESPN ID; join to `teams.espn_id` |
| Request parameter `season` | `conference_memberships.season_id` | Join to `seasons.year` |

### Field Mapping — Season Statistics

Stats are identified by `name` and `type` fields. The key stats to extract per team:

| Stat `type` | Stat `name` | Maps To | Notes |
|-------------|-------------|---------|-------|
| `wins` | `wins` | `season_statistics.wins` | Overall wins |
| `losses` | `losses` | `season_statistics.losses` | Overall losses |
| `home_wins` | `wins` | `season_statistics.home_wins` | Home wins |
| `home_losses` | `losses` | `season_statistics.home_losses` | Home losses |
| `road_wins` | `wins` | `season_statistics.road_wins` | Road wins |
| `road_losses` | `losses` | `season_statistics.road_losses` | Road losses |
| (vs. Conf. group) `wins` | `wins` | `season_statistics.conference_wins` | Conference wins |
| (vs. Conf. group) `losses` | `losses` | `season_statistics.conference_losses` | Conference losses |
| `pointsFor` | `pointsFor` | `season_statistics.points_for` | Total points scored |
| `pointsAgainst` | `pointsAgainst` | `season_statistics.points_against` | Total points allowed |
| `streak` | `streak` | `season_statistics.streak` | Current streak (positive = wins, negative = losses) |
| `playoffSeed` | `playoffSeed` | `season_statistics.conference_standing` | Standing within conference |

Rankings breakdowns (vs. AP Top 25, vs. USA ranked) are **not stored**.

The `season_statistics` table is used for QA of scraped game data (e.g., verifying that a team's win count matches the number of FINAL games in which they won). The QA process itself is outside the scope of this implementation.

### Handling Unknown Teams

A team found in a historical season's standings may not exist in the current-season teams API response. When this happens, fetch the team individually from the single-team endpoint and insert it with `active = false`.

---

## Games (Scoreboard)

### API Structure

**URL:** `https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=basketball&league=mens-college-basketball&limit=200&groups=50&dates={yyyyMMdd}`

**Navigation path:** `root.sports[0].leagues[0].events[i]`

Returns all D-I games for a given date. The date parameter uses `yyyyMMdd` format (e.g., `20250215`).

Games are uniquely identified by their ESPN event ID (a numeric string, e.g., `"401708390"`).

### Field Mapping

| ESPN Field | Type | Example | Maps To | Notes |
|-----------|------|---------|---------|-------|
| `id` | String | `"401708390"` | `games.espn_id` | **Primary external key** |
| `date` | String | `"2025-02-15T21:00:00Z"` | `games.game_date` | ISO 8601 UTC; convert to LocalDateTime |
| `location` | String | `"Coleman Coliseum"` | `games.venue` | Venue name |
| `neutralSite` | Boolean | `false` | `games.neutral_site` | Already in schema |
| `season` | Number | `2025` | `games.season_id` | Join to `seasons.year` |
| `fullStatus.type.name` | String | `"STATUS_FINAL"` | `games.status` | See status mapping below |
| `competitors[homeAway="home"].id` | String | `"333"` | `games.home_team_id` | Join to `teams.espn_id` |
| `competitors[homeAway="away"].id` | String | `"2"` | `games.away_team_id` | Join to `teams.espn_id` |
| `competitors[homeAway="home"].score` | String | `"85"` | `games.home_score` | Parse to Integer; empty string if not started |
| `competitors[homeAway="away"].score` | String | `"94"` | `games.away_score` | Parse to Integer; empty string if not started |
| — | — | — | `games.scrape_date` | The `yyyyMMdd` date used in the URL (see note below) |

**Fields available but not currently mapped:**

| ESPN Field | Type | Example | Notes |
|-----------|------|---------|-------|
| `competitors[].record` | String | `"23-2"` | Overall record at time of game |
| `competitors[].rank` | Number | `1` | AP poll rank (absent if unranked) |
| `competitors[].group` | String | `"23"` | Conference groupId — used to compute `conference_game` on read |
| `broadcast` | String | `"ESPN"` | TV network |
| `period` | Number | `2` | Final period (useful for detecting OT) |

### Status Mapping

| ESPN `fullStatus.type.name` | ESPN `fullStatus.type.state` | Our `GameStatus` |
|-----------------------------|------------------------------|------------------|
| `STATUS_SCHEDULED` | `pre` | `SCHEDULED` |
| `STATUS_IN_PROGRESS` | `in` | `IN_PROGRESS` |
| `STATUS_FINAL` | `post` | `FINAL` |
| `STATUS_POSTPONED` | `post` | `POSTPONED` |
| `STATUS_CANCELED` | `post` | `CANCELLED` |

### Scrape Date vs. Game Date

The `scrape_date` column stores the date used in the URL request, which may differ from the actual `game_date` in the response. For example, a game that tips off at 11 PM ET on Feb 15 may have a UTC `game_date` of Feb 16. Storing the scrape date ensures we can re-fetch the same set of games if needed.

### Season Date Range

When scraping a full season, iterate over each date in the range:

- **Start:** November 1 of `{year - 1}`
- **End:** April 30 of `{year}`

For example, season 2025 covers `20241101` through `20250430` (181 dates).

### Conference Game Flag

The `games.conference_game` column is **not populated during scraping**. It is computed on read by checking whether both teams belong to the same conference for the game's season (via `conference_memberships`).

---

## Betting Odds

### Two-Source Strategy

Odds are collected from two different endpoints depending on game state:

| Game State | Source | When |
|-----------|--------|------|
| **Pre-game / in-progress** (current season) | Scoreboard endpoint (`events[i].odds`) | During the per-date game scrape |
| **Completed** (historical backfill) | Core odds endpoint (per-game) | Separate backfill pass after games are scraped |

For current-season scraping, odds are extracted from the scoreboard response alongside game data — no extra API calls needed. For historical seasons where games are already completed, odds must be backfilled using the core odds endpoint (one API call per game).

### Scoreboard Odds (Pre-game)

Present on scoreboard events where `fullStatus.type.state = "pre"`:

| ESPN Field | Type | Example | Maps To |
|-----------|------|---------|---------|
| `odds.spread` | Number | `-2.5` | `betting_odds.spread` |
| `odds.overUnder` | Number | `135.5` | `betting_odds.over_under` |
| `odds.home.moneyLine` | Number | `-142` | `betting_odds.home_moneyline` |
| `odds.away.moneyLine` | Number | `120` | `betting_odds.away_moneyline` |
| `odds.provider.name` | String | `"DraftKings"` | `betting_odds.source` |

Note: The scoreboard endpoint does not include opening lines. The `opening_spread` and `opening_over_under` columns will be null for odds captured this way.

### Core Odds Endpoint (Backfill)

**URL:** `https://sports.core.api.espn.com/v2/sports/basketball/leagues/mens-college-basketball/events/{espnGameId}/competitions/{espnGameId}/odds`

This endpoint **preserves betting data on completed games**, including both opening and closing lines.

| ESPN Field | Type | Example | Maps To | Notes |
|-----------|------|---------|---------|-------|
| `items[0].spread` | Number | `-1.5` | `betting_odds.spread` | Closing spread (home team perspective) |
| `items[0].overUnder` | Number | `174.5` | `betting_odds.over_under` | Closing total |
| `items[0].homeTeamOdds.moneyLine` | Number | `-140` | `betting_odds.home_moneyline` | Closing home moneyline |
| `items[0].awayTeamOdds.moneyLine` | Number | `120` | `betting_odds.away_moneyline` | Closing away moneyline |
| `items[0].pointSpread.home.open.line` | String | `"-1.5"` | `betting_odds.opening_spread` | Parse to BigDecimal |
| `items[0].total.over.open.line` | String | `"o134.5"` | `betting_odds.opening_over_under` | Strip "o"/"u" prefix, parse to BigDecimal |
| `items[0].provider.name` | String | `"ESPN BET"` | `betting_odds.source` | Odds provider |

**Notes:**
- The response contains an `items[]` array with one entry per odds provider. Use the first provider (highest priority).
- The `spread` and `overUnder` top-level fields represent the closing/current line.
- Opening lines are nested under `pointSpread.home.open.line` and `total.over.open.line`.
- Moneyline values can be the string `"OFF"` when unavailable — treat as null.
- The `lastUpdated` column on `betting_odds` should be set to the scrape timestamp.

---

## Required Schema Changes

The current schema needs modifications to support scraping. These should be implemented as Flyway migrations.

### Columns to Add

| Table | Column | Type | Constraint | Purpose |
|-------|--------|------|------------|---------|
| `teams` | `espn_id` | `VARCHAR(20)` | `UNIQUE NOT NULL` | ESPN team ID |
| `teams` | `abbreviation` | `VARCHAR(20)` | | Short code (e.g., "ALA") |
| `teams` | `slug` | `VARCHAR(255)` | | URL-safe identifier (e.g., "alabama-crimson-tide") |
| `teams` | `color` | `VARCHAR(10)` | | Primary hex color |
| `teams` | `alternate_color` | `VARCHAR(10)` | | Secondary hex color |
| `teams` | `active` | `BOOLEAN` | `DEFAULT true` | Currently competing in D-I |
| `teams` | `logo_url` | `VARCHAR(500)` | | Logo image URL |
| `conferences` | `espn_id` | `VARCHAR(20)` | `UNIQUE NOT NULL` | ESPN group ID |
| `conferences` | `logo_url` | `VARCHAR(500)` | | Conference logo URL |
| `games` | `espn_id` | `VARCHAR(20)` | `UNIQUE NOT NULL` | ESPN event ID |
| `games` | `scrape_date` | `DATE` | | The URL date used to fetch this game |

### Columns to Drop

| Table | Column | Reason |
|-------|--------|--------|
| `teams` | `city` | ESPN does not provide city data |
| `teams` | `state` | ESPN does not provide state data |
| `games` | `attendance` | ESPN does not provide attendance data |
| `games` | `tournament_id` | Tournaments table is being dropped |

### Tables to Drop

| Table | Reason |
|-------|--------|
| `tournaments` | Not populated by ESPN scraping; out of scope |

### New Tables

**`scrape_batches`** — tracks scraping history for the control panel:

| Column | Type | Constraint | Purpose |
|--------|------|------------|---------|
| `id` | `BIGSERIAL` | `PRIMARY KEY` | |
| `season_year` | `INTEGER` | `NOT NULL` | Which season was scraped |
| `scrape_type` | `VARCHAR(50)` | `NOT NULL` | `TEAMS`, `CONFERENCES`, `STANDINGS`, `GAMES` |
| `started_at` | `TIMESTAMP` | `NOT NULL` | When the batch started |
| `completed_at` | `TIMESTAMP` | | When it finished (null if running/failed) |
| `status` | `VARCHAR(20)` | `NOT NULL` | `RUNNING`, `COMPLETED`, `FAILED`, `PARTIAL` |
| `records_created` | `INTEGER` | `DEFAULT 0` | New records inserted |
| `records_updated` | `INTEGER` | `DEFAULT 0` | Existing records updated |
| `dates_succeeded` | `INTEGER` | `DEFAULT 0` | Dates successfully scraped (games only) |
| `dates_failed` | `INTEGER` | `DEFAULT 0` | Dates that failed (games only) |
| `error_message` | `TEXT` | | Error details if failed |

**`season_statistics`** — aggregate season stats per team, scraped from the standings endpoint:

| Column | Type | Constraint | Purpose |
|--------|------|------------|---------|
| `id` | `BIGSERIAL` | `PRIMARY KEY` | |
| `team_id` | `BIGINT` | `NOT NULL REFERENCES teams(id)` | |
| `season_id` | `BIGINT` | `NOT NULL REFERENCES seasons(id)` | |
| `conference_id` | `BIGINT` | `NOT NULL REFERENCES conferences(id)` | Conference the team was in |
| `wins` | `INTEGER` | | Overall wins |
| `losses` | `INTEGER` | | Overall losses |
| `conference_wins` | `INTEGER` | | Conference wins |
| `conference_losses` | `INTEGER` | | Conference losses |
| `home_wins` | `INTEGER` | | Home wins |
| `home_losses` | `INTEGER` | | Home losses |
| `road_wins` | `INTEGER` | | Road wins |
| `road_losses` | `INTEGER` | | Road losses |
| `points_for` | `INTEGER` | | Total points scored |
| `points_against` | `INTEGER` | | Total points allowed |
| `streak` | `INTEGER` | | Current streak (positive = wins, negative = losses) |
| `conference_standing` | `INTEGER` | | Standing within conference |
| | | `UNIQUE(team_id, season_id)` | One row per team per season |

**`admin_users`** — authentication for the scraping control panel:

| Column | Type | Constraint | Purpose |
|--------|------|------------|---------|
| `id` | `BIGSERIAL` | `PRIMARY KEY` | |
| `username` | `VARCHAR(50)` | `UNIQUE NOT NULL` | |
| `password_hash` | `VARCHAR(255)` | `NOT NULL` | BCrypt hash |
| `password_must_change` | `BOOLEAN` | `DEFAULT false` | Force password change on next login |

---

## Scraping Order & Flow

### Dependency Order

Data must be scraped in this order:

1. **Conferences** — no dependencies (1 API call)
2. **Teams** — no dependencies (1 API call, plus individual lookups for unknown teams)
3. **Seasons** — create season records as needed (no API call; derived from the year being scraped)
4. **Standings** — requires conferences, teams, and seasons to exist (1 API call per season)
   - Creates/updates conference memberships
   - Creates/updates season statistics
5. **Games** — requires teams and seasons to exist (1 API call per date, 181 dates per season)
   - For current-season pre-game events: also captures odds from the scoreboard response
6. **Betting Odds Backfill** — requires games to exist (1 API call per game, for completed games without odds)

### Idempotent Upserts

All scraping operations are idempotent. Records are matched by ESPN ID (`espn_id`) or by natural key:

- **Teams:** match on `espn_id`
- **Conferences:** match on `espn_id`
- **Games:** match on `espn_id`
- **Conference memberships:** match on `(team_id, season_id)` unique constraint
- **Season statistics:** match on `(team_id, season_id)` unique constraint
- **Betting odds:** match on `game_id` (one-to-one with game)

If a matching record exists, update it. If not, insert. Never create duplicates.

### Rate Limiting

ESPN's APIs are public but undocumented. All scraping requests must include:

- A **configurable base delay** between requests (default: 200ms)
- A **random jitter** added to the base delay to avoid regular request patterns

These values should be configurable in `application.properties`.

### Error Handling

A season game scrape involves 181 individual date requests. **A single date failure does not abort the batch.** The scraper should:

- Continue to the next date on failure
- Log the error with the failed date
- Track `dates_succeeded` and `dates_failed` counts on the `scrape_batches` record
- Mark the batch as `PARTIAL` if some dates failed, `COMPLETED` if all succeeded, `FAILED` only if a systemic error prevents any progress

---

## Administration

### Season Management

The control panel allows the administrator to:

- **Add a season** to be scraped (specifying the year)
- **Remove a season** from the scraping schedule
- **Force re-scrape** a historical season (upsert over existing records; data is never deleted)

### Season Types

| Type | Behavior |
|------|----------|
| **Historical** | Scraped once. All 181 dates fetched in a single batch. Not re-scraped unless forced by admin. |
| **Current** | Re-scraped on a recurring schedule (default: twice daily). Re-fetches today's date plus any recent dates that still have non-final games (e.g., postponed or in-progress games). |

The scraping schedule should be configurable through the control panel.

### Scrape History Dashboard

The control panel displays a table of past scrape batches showing:

- Season year
- Scrape type (teams, conferences, standings, games)
- Start time and duration
- Records created / updated
- Dates succeeded / failed (for game scrapes)
- Status (running, completed, partial, failed)
- Error details (expandable, if failed)

### Security

The scraping control panel is secured with Spring Security form-based login. The public-facing application (team pages, game listings, etc.) does not require authentication.

**Admin user setup:**

- A single admin user with username `admin`
- On application startup, if the admin user does not exist or has no password, generate a random password, store its BCrypt hash, and **log the generated password** to the application log
- Set `password_must_change = true` on the generated password
- The control panel provides a password change form so the admin can set their own password
- All scraping control panel routes (e.g., `/admin/**`) require authentication; all other routes are public
