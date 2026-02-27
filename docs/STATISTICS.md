# SUBSYSTEM TO CALCULATE, STORE AND DISPLAY STATISTICS

## MOTIVATION

Calculate and store time series of computed statistics for each team within a season — a stepping stone to further analysis. Additionally, analyze population characteristics (distributions across all teams) at any given point in time.

For performance, statistics are persisted in the database after calculation. For completed seasons they remain unchanged; for the current season they are recalculated after every scrape and on demand.

---

## OVERVIEW OF NEW TABLES

This subsystem adds two new tables alongside the existing `season_statistics` table (which stays as the current-state summary):

### 1. `team_season_stat_snapshots`
Time series: one row per **team × season × date**. Each row represents cumulative stats as of that date.

| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| team_id | BIGINT FK | → teams |
| season_id | BIGINT FK | → seasons |
| snapshot_date | DATE | The game date this snapshot covers |
| games_played | INTEGER | Cumulative games played through this date |
| wins | INTEGER | Cumulative wins |
| losses | INTEGER | Cumulative losses |
| win_pct | DOUBLE PRECISION | wins / games_played |
| mean_pts_for | DOUBLE PRECISION | Running mean of points scored per game |
| stddev_pts_for | DOUBLE PRECISION | Running std dev of points scored per game |
| mean_pts_against | DOUBLE PRECISION | Running mean of points allowed per game |
| stddev_pts_against | DOUBLE PRECISION | Running std dev of points allowed per game |
| correlation_pts | DOUBLE PRECISION | Pearson correlation between pts scored and pts allowed across games so far. NULL if < 2 games. |
| UNIQUE | (team_id, season_id, snapshot_date) | |

Indexes: `(season_id, snapshot_date)`, `(team_id, season_id)`.

### 2. `season_population_stats`
Population distributions: one row per **season × date × stat_name**. Aggregates the snapshot values across all teams on that date.

| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| season_id | BIGINT FK | → seasons |
| stat_date | DATE | Matches snapshot dates from the time series table |
| stat_name | VARCHAR(64) | e.g. `win_pct`, `mean_pts_for`, `stddev_pts_for`, `mean_pts_against`, `stddev_pts_against`, `correlation_pts` |
| pop_mean | DOUBLE PRECISION | Mean across all teams |
| pop_stddev | DOUBLE PRECISION | Std dev across all teams |
| pop_min | DOUBLE PRECISION | Min across all teams |
| pop_max | DOUBLE PRECISION | Max across all teams |
| team_count | INTEGER | Number of teams with data on this date |
| UNIQUE | (season_id, stat_date, stat_name) | |

---

## STATISTICS CALCULATED

For each team, cumulative stats are computed through each game date:

| Stat | Description |
|---|---|
| `win_pct` | wins / games_played |
| `mean_pts_for` | Average points scored per game (cumulative) |
| `stddev_pts_for` | Sample std dev of points scored per game |
| `mean_pts_against` | Average points allowed per game (cumulative) |
| `stddev_pts_against` | Sample std dev of points allowed per game |
| `correlation_pts` | Pearson correlation between pts_scored and pts_allowed vectors across all games played so far |

Population stats are derived from the above by aggregating across all teams on each date.

---

## JAVA IMPLEMENTATION

### New Entities
- `TeamSeasonStatSnapshot` — maps to `team_season_stat_snapshots`
- `SeasonPopulationStat` — maps to `season_population_stats`

### New Repositories
- `TeamSeasonStatSnapshotRepository`
  - `findByTeamIdAndSeasonId(Long teamId, Long seasonId)` — full time series for one team
  - `findBySeasonIdAndSnapshotDate(Long seasonId, LocalDate date)` — all teams on a date
  - `deleteBySeasonId(Long seasonId)` — wipe before recalculation
- `SeasonPopulationStatRepository`
  - `findBySeasonIdAndStatDate(Long seasonId, LocalDate date)` — population stats on a date
  - `deleteBySeasonId(Long seasonId)` — wipe before recalculation

### New Service: `StatisticsTimeSeriesService`

Core method: `calculateAndStoreForSeason(int seasonYear)`

**Algorithm:**
1. Load all FINAL games for the season, sorted by `gameDate` ascending.
2. Group games by date.
3. For each team, maintain a running accumulator (list of `(ptsFor, ptsAgainst)` tuples and running counts).
4. Process dates in order. After each date's games are applied, write one `TeamSeasonStatSnapshot` per team that played on or before that date.
5. Compute Pearson correlation from the accumulated vectors (requires ≥ 2 games; store NULL otherwise).
6. After all team snapshots for a date are written, compute population stats across all teams for each of the six stat columns and write to `SeasonPopulationStat`.
7. Wipe existing rows for the season (by season_id) before writing, so recalculation is idempotent.

**Pearson correlation formula:**
```
r = (n * Σ(xy) - Σx * Σy) / sqrt((n*Σx² - (Σx)²) * (n*Σy² - (Σy)²))
```
where x = pts_for, y = pts_against, n = games played. Store NULL if denominator is zero or n < 2.

### Integration with Existing Services
- `ScrapeOrchestrator`: call `statisticsTimeSeriesService.calculateAndStoreForSeason(year)` after the existing `statsCalculationService.calculateAndUpdateForSeason(year)` call (both full and current scrape paths).
- `StatsCalculationService`: unchanged — continues to maintain the existing `season_statistics` calc columns.

---

## REST API

**Base path:** `/api/statistics`

| Method | Path | Description |
|---|---|---|
| GET | `/api/statistics/team/{teamId}/season/{year}` | Full time series for a team in a season. Returns list of snapshots ordered by date. |
| GET | `/api/statistics/season/{year}/snapshots?date=YYYY-MM-DD` | All team snapshots for a specific date in a season. |
| GET | `/api/statistics/season/{year}/population?date=YYYY-MM-DD` | Population distribution stats for a specific date. |
| GET | `/api/statistics/season/{year}/population/latest` | Population stats for the most recent date with data. |
| POST | `/api/statistics/recalculate/{year}` | Trigger on-demand recalculation for a season (runs synchronously or async). |

Response DTOs to be defined — at minimum include all snapshot columns plus `teamId`, `teamName`, `seasonYear`.

---

## UI

### Team Detail Page — Stats Tab
Add a "Stats" tab to the existing team detail page (`/teams/{slug}`).

Displays:
- **Line chart** (Chart.js): x-axis = date, y-axis dual lines for `win_pct` progression and optionally `mean_pts_for` / `mean_pts_against`.
- **Data table** below the chart listing all snapshots with all six stat columns.

Loads via HTMX from `GET /api/statistics/team/{teamId}/season/{year}`.

### Season League Overview Page — `/seasons/{year}/stats`
New page showing the league-wide picture for a season.

Displays:
- **Date picker** (or slider) to select the population stats date. Defaults to latest date with data.
- **Rankings table**: all teams sorted by `win_pct` descending, with all stat columns, plus z-score columns derived from population stats (optional enhancement).
- **Distribution summary panel**: population mean ± std dev for each stat on the selected date.

Loads snapshot data via HTMX from `GET /api/statistics/season/{year}/snapshots?date=`.
Loads population data via HTMX from `GET /api/statistics/season/{year}/population?date=`.

### Admin Panel
Add a "Recalculate Statistics" button per season row (alongside existing scrape buttons). POSTs to `/api/statistics/recalculate/{year}` with HTMX and shows feedback.

---

## DATABASE MIGRATION

New Flyway migration file: `V6__statistics_time_series.sql` (check existing migration numbering first).

```sql
CREATE TABLE team_season_stat_snapshots (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id),
    season_id BIGINT NOT NULL REFERENCES seasons(id),
    snapshot_date DATE NOT NULL,
    games_played INTEGER NOT NULL DEFAULT 0,
    wins INTEGER NOT NULL DEFAULT 0,
    losses INTEGER NOT NULL DEFAULT 0,
    win_pct DOUBLE PRECISION,
    mean_pts_for DOUBLE PRECISION,
    stddev_pts_for DOUBLE PRECISION,
    mean_pts_against DOUBLE PRECISION,
    stddev_pts_against DOUBLE PRECISION,
    correlation_pts DOUBLE PRECISION,
    UNIQUE (team_id, season_id, snapshot_date)
);

CREATE INDEX idx_snapshots_season_date ON team_season_stat_snapshots (season_id, snapshot_date);
CREATE INDEX idx_snapshots_team_season ON team_season_stat_snapshots (team_id, season_id);

CREATE TABLE season_population_stats (
    id BIGSERIAL PRIMARY KEY,
    season_id BIGINT NOT NULL REFERENCES seasons(id),
    stat_date DATE NOT NULL,
    stat_name VARCHAR(64) NOT NULL,
    pop_mean DOUBLE PRECISION,
    pop_stddev DOUBLE PRECISION,
    pop_min DOUBLE PRECISION,
    pop_max DOUBLE PRECISION,
    team_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (season_id, stat_date, stat_name)
);

CREATE INDEX idx_pop_stats_season_date ON season_population_stats (season_id, stat_date);
```

---

## OPEN QUESTIONS / FUTURE ENHANCEMENTS
_Yes to all of these_
- **Z-scores per team**: once population stats exist, we can store a team's z-score for each stat at each date. This enables normalized comparisons across eras and conferences. Could be added as columns to `team_season_stat_snapshots`.
- **Conference-level population stats**: run the same population aggregation but scoped to a conference, not the full league.
- **Additional stats**: margin of victory (mean/std dev of point differential), pace proxies, rolling window stats (e.g., last-10-game averages).
- **Async recalculation**: if full recalculation proves slow for a current season with many teams/dates, wrap in `@Async` similar to `AsyncScrapeService` and track with a `ScrapeBatch` record.
