# Stats Pipeline Refactoring — Implementation Plan (Phases 0–3)

Companion to [STATS_PIPELINE_ANALYSIS.md](STATS_PIPELINE_ANALYSIS.md). This document
specifies the concrete changes — files, signatures, migrations, and tests — for each
phase. Phases are implemented and verified in order; each leaves the build green.

Testing ground rules (apply to every phase):
- Integration tests extend `BaseIntegrationTest` (singleton Testcontainers Postgres,
  centralized `TRUNCATE` cleanup). New tables **must** be appended to
  `SharedPostgresContainer.TABLES_TO_TRUNCATE`.
- Every behavioral change gets a test; the key quality gate for incremental
  persistence is an *equivalence test*: incremental output == full-recompute output.
- Pure logic (registry math, scope decisions) gets plain unit tests where no DB is
  needed.

---

## Phase 0 — Measure + free wins

### 0.1 Per-phase timing logs
Add elapsed-millis to the completion log of each calc service and around each
persistence step.

- `StatsCalculationService`, `StatisticsTimeSeriesService`, `MasseyRatingService`,
  `BradleyTerryRatingService`: capture `long t0 = System.currentTimeMillis()` at
  method start; completion logs gain `in {} ms` (and the save step logs its own
  elapsed time where it is separable).

### 0.2 Change detection + short-circuit (gate)

**Migration `V18__stat_calc_watermarks.sql`:**
```sql
ALTER TABLE games ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT now();
CREATE INDEX idx_games_season_updated ON games (season_id, updated_at);

CREATE TABLE stat_calc_watermarks (
    id                    BIGSERIAL PRIMARY KEY,
    season_id             BIGINT NOT NULL UNIQUE REFERENCES seasons(id),
    last_calc_started_at  TIMESTAMP NOT NULL,
    final_game_count      INTEGER NOT NULL
);
```

**`Game` entity:** add `updatedAt` with JPA lifecycle audit hooks:
```java
@PrePersist @PreUpdate
void touchUpdatedAt() { this.updatedAt = LocalDateTime.now(); }
```
`@PreUpdate` only fires when Hibernate finds the entity dirty, so `updated_at`
moves **only on actual change** — exactly the semantics the watermark needs.
(`Game.scrapeDate` is a `LocalDate`; day granularity is too coarse for 12-hour
cycles and a same-day score correction would be missed.)

**New entity + repository:** `StatCalcWatermark`, `StatCalcWatermarkRepository`
(`findBySeasonId`, plus delete-by-season for tests).

**New service `StatCalcGateService`:**
```java
public RecalcScope check(Season season);           // called BEFORE calcs
public void recordRun(Season season, LocalDateTime calcStartedAt, int finalGameCount);

public record RecalcScope(Mode mode, LocalDate fromDate) {   // Mode: SKIP, FULL, INCREMENTAL
```
Decision logic:
- No watermark row → `FULL`.
- Current FINAL-game count != stored count → `FULL` (covers deletions).
- Else `min(game_date::date)` over games with `updated_at >= last_calc_started_at`,
  combined with `min(g.game_date::date)` over `team_game_stats` rows with
  `scrape_date >= last_calc_started_at` (box scores feed Phase 3 stats) →
  `INCREMENTAL(minDate)`; if neither query returns a row → `SKIP`.
- `calcStartedAt` is captured **before** the games are read, so concurrent writes
  committing during the calc are caught by the next cycle.

In Phase 0 the orchestrator uses only `SKIP` vs run-everything; `fromDate` is
threaded through in Phase 2.

### 0.3 Move `conferenceGame` derivation out of `StatsCalculationService`

**New service `ConferenceGameFlagService`:**
```java
@Transactional
public void updateForSeason(int seasonYear)
```
Loads memberships + all season games (`GameRepository.findBySeasonId`), sets
`game.setConferenceGame(bothTeamsSameConference)` for **every** game (not just
FINAL — schedule pages benefit). Hibernate dirty checking persists only real
changes, which also means `updated_at` bumps only when a flag genuinely flips
(and a flipped flag *should* trigger recalculation — it changes conference
records).

**`StatsCalculationService`:** delete the membership-pair derivation and the
`game.setConferenceGame(...)` side effect; consume
`Boolean.TRUE.equals(game.getConferenceGame())` instead. The flag service is now
the single source of truth; orchestration guarantees it runs first.

### 0.4 Reorder the orchestrator

New order in both `scrapeFullSeason` and `scrapeCurrentSeason`:
```
scrapers (conf/teams/standings as today) → games → odds backfill → game-stats backfill
→ conferenceGameFlagService.updateForSeason
→ gate.check → [SKIP: log and return] | [run calc block] → gate.recordRun
```
Standalone orchestrator entry points (`calculateStats`, `calculateTimeSeries`,
`calculatePowerRatings` — used by the admin panel via `AsyncScrapeService`)
remain **full, unconditional** recomputes; `calculateStats` additionally runs the
flag service first (it now depends on the flag).

### Phase 0 tests
- `GameUpdatedAtTest` (extends `BaseDataJpaTest`): insert sets `updatedAt`;
  re-saving without changes does not move it; changing the score moves it.
- `ConferenceGameFlagServiceTest` (integration): same-conference → true;
  cross-conference → false; no membership → false; corrects a stale flag;
  flags SCHEDULED games too.
- `StatCalcGateServiceTest` (integration): no watermark → FULL; recordRun then
  no changes → SKIP; score change after recordRun → INCREMENTAL with that game's
  date; new TeamGameStats row → INCREMENTAL; FINAL-count drop → FULL.
- `StatsCalculationServiceTest`: updated — conference-record tests now run the
  flag service before the calc (mirrors production contract).
- `ScrapeOrchestratorTest`: updated InOrder expectations (backfills before calc
  block, flag service before gate, gate SKIP suppresses calc services, recordRun
  called after a successful calc block).
- `SharedPostgresContainer.TABLES_TO_TRUNCATE` += `stat_calc_watermarks`.

---

## Phase 1 — Batched JDBC write path

### 1.1 `SnapshotJdbcWriter` (new, `service/`)
Spring component wrapping `JdbcTemplate`. One method per snapshot table; the
existing entity classes remain the in-memory carriers (they are never persisted
through JPA on the write path — IDs stay null):

```java
public void writeTeamSeasonStatSnapshots(List<TeamSeasonStatSnapshot> rows)
public void writeSeasonPopulationStats(List<SeasonPopulationStat> rows)
public void writeTeamPowerRatingSnapshots(List<TeamPowerRatingSnapshot> rows)
public void writePowerModelParamSnapshots(List<PowerModelParamSnapshot> rows)
```

Implementation: `jdbcTemplate.batchUpdate(sql, rows, 1000, setter)` (the chunking
overload). Explicit column lists matching the Flyway DDL. Runs inside the
caller's `@Transactional` (JpaTransactionManager exposes the bound connection to
`JdbcTemplate` on the same DataSource), so delete + batch-insert stays one
atomic swap.

### 1.2 Service changes
`StatisticsTimeSeriesService`, `MasseyRatingService`, `BradleyTerryRatingService`:
replace `repository.saveAll(list)` with the corresponding writer call. Bulk
deletes stay as-is. `StatsCalculationService` keeps JPA upserts (it updates ~360
existing managed rows — wrong tool for blind batch insert).

### 1.3 Driver-level batch rewrite
Append `?reWriteBatchedInserts=true` to the JDBC URL in
`application.properties` — the Postgres driver folds batches into multi-row
INSERTs (~2–5× on top of batching).

### Phase 1 tests
- `SnapshotJdbcWriterTest` (integration): for each of the four tables, write
  rows exercising every column (including null optionals), read back through the
  JPA repositories, assert field-for-field equality. This is the drift guard
  between entity mappings and the writer's SQL.
- Existing `StatisticsTimeSeriesServiceTest`, `MasseyRatingServiceTest`,
  `BradleyTerryRatingServiceTest` pass unchanged (same observable behavior).

---

## Phase 2 — Watermark-incremental persistence

### 2.1 Repository deletes scoped by date
```java
// TeamSeasonStatSnapshotRepository
void deleteBySeasonIdFromDate(Long seasonId, LocalDate fromDate);
// SeasonPopulationStatRepository
void deleteBySeasonIdFromDate(Long seasonId, LocalDate fromDate);
// TeamPowerRatingSnapshotRepository / PowerModelParamSnapshotRepository
void deleteBySeasonIdAndModelTypeFromDate(Long seasonId, String modelType, LocalDate fromDate);
```
All `@Modifying` JPQL bulk deletes (`snapshot_date >= :fromDate`).

### 2.2 Service signatures
Each per-date service gains an overload; the old signature delegates with
`fromDate = null` (full):
```java
public void calculateAndStoreForSeason(int seasonYear, LocalDate fromDate)
```
Semantics (identical pattern in all three):
- **Replay the whole season in memory** (accumulators are cheap — that was never
  the bottleneck).
- Only **emit/persist** snapshots for dates `>= fromDate`; delete only those
  dates' rows.
- Massey: skip the per-date `solve()` for dates before the watermark (the solve
  is only needed to emit). Bradley-Terry: skip `newtonRaphson` before the
  watermark — the first post-watermark solve starts cold and simply takes a few
  more iterations; the converged optimum is identical.
- `StatsCalculationService` stays full-recompute (cheap aggregate upsert).

### 2.3 Orchestrator
The gate's `RecalcScope` now drives the calc block:
- `SKIP` → nothing.
- `FULL` → `fromDate = null`.
- `INCREMENTAL(d)` → `fromDate = d`.

### Phase 2 tests
- Per service, the **equivalence test**: seed games across ≥3 dates; full run;
  mutate a game on the middle date (score change); incremental run from that
  date; capture all rows; then wipe + full recompute; assert row sets are equal
  (dates, ratings/stats, ranks). This proves incremental == full.
- Boundary tests: watermark before all dates == full; watermark after all dates
  rewrites nothing but deletes nothing earlier; pre-watermark rows untouched
  (assert by `calculatedAt`/row identity).
- Gate integration test extended: orchestrator passes INCREMENTAL date through
  (Mockito orchestrator test verifying the argument).

---

## Phase 3 — Shared pass, stat registry, long-format box-score stats

### 3.1 Load games once: `SeasonGameData`
```java
public record SeasonGameData(Season season,
                             List<Game> finalGames,            // sorted by date
                             Map<LocalDate, List<Game>> gamesByDate,
                             Map<Long, Team> teamsById) { }
```
New `SeasonGameDataLoader.load(int seasonYear) : Optional<SeasonGameData>` does
the one `findBySeasonIdAndStatus(FINAL)` (join-fetched teams), null-score
filtering, sorting, grouping. The four calc services gain overloads taking
`SeasonGameData` (+ `fromDate`); the `int seasonYear` overloads load internally
(admin/standalone paths unchanged). The orchestrator loads once and shares.

### 3.2 Migration `V19__team_stat_snapshots.sql` (long format)
```sql
CREATE TABLE team_stat_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES teams(id),
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    snapshot_date DATE NOT NULL,
    stat_name     VARCHAR(64) NOT NULL,
    value         DOUBLE PRECISION NOT NULL,
    games_played  INTEGER NOT NULL DEFAULT 0,
    rank          INTEGER,
    zscore        DOUBLE PRECISION,
    conf_zscore   DOUBLE PRECISION,
    UNIQUE (team_id, season_id, snapshot_date, stat_name)
);
CREATE INDEX idx_team_stat_snaps_season_stat_date ON team_stat_snapshots (season_id, stat_name, snapshot_date);
CREATE INDEX idx_team_stat_snaps_team_season_stat ON team_stat_snapshots (team_id, season_id, stat_name);
```
Entity `TeamStatSnapshot`, repository (leaderboard-by-date, team-trajectory,
latest-date, delete-by-season, delete-from-date), and a
`writeTeamStatSnapshots` method on `SnapshotJdbcWriter`. Population rows for the
new stats reuse the existing long-format `season_population_stats` table.

### 3.3 Calculator pipeline
```java
public interface DailyStatCalculator {
    void begin(SeasonGameData data);
    void onGame(Game game, TeamGameStats homeStats, TeamGameStats awayStats); // stats nullable
    List<TeamStatValue> snapshot(LocalDate date);   // (teamId, statName, value, gamesPlayed)
}
```
New `TeamStatTimeSeriesService` orchestrates: loads `TeamGameStats` for the
season once (new `TeamGameStatsRepository.findBySeasonId` join through game),
iterates `gamesByDate` feeding every registered calculator, and for each date
`>= fromDate`: collects values, computes league + per-conference population
stats and z-scores **generically per stat name** (shared `PopulationCalculator`
utility extracted from `StatisticsTimeSeriesService`), assigns ranks, persists
via the JDBC writer with watermark-scoped deletes.

### 3.4 First registry: box-score stats (`BoxScoreStatCalculator`)
Per-team cumulative accumulator of own + opponent box-score sums. Possessions
estimated as `FGA − ORB + TO + 0.475×FTA`. Registry entries (computed through
date, NULL-safe — a stat is only emitted when its inputs are present):

| stat_name | definition |
|---|---|
| `pace` | possessions per game |
| `off_efficiency` / `def_efficiency` | 100 × pts / poss (own / opponent) |
| `efg_pct` / `opp_efg_pct` | (FGM + 0.5×3PM) / FGA |
| `tov_rate` / `opp_tov_rate` | TO / possessions |
| `orb_pct` | ORB / (ORB + opp DRB) |
| `drb_pct` | DRB / (DRB + opp ORB) |
| `ft_rate` / `opp_ft_rate` | FTM / FGA |
| `fg_pct`, `fg3_pct`, `ft_pct` | shooting splits |
| `fg3_rate` | 3PA / FGA |

Each entry: `StatDefinition(name, extractor, higherIsBetter)` — direction drives
rank ordering. Adding a stat is one registry line, no migration.

### 3.5 Orchestrator + gate
- `TeamStatTimeSeriesService` joins the calc block (after the flag service; the
  game-stats backfill already precedes the block from Phase 0).
- Gate already includes `team_game_stats.scrape_date` in change detection
  (Phase 0), so new box scores trigger incremental recompute.
- `SharedPostgresContainer.TABLES_TO_TRUNCATE` += `team_stat_snapshots`.

### Phase 3 tests
- `BoxScoreStatCalculatorTest` (plain unit test, no DB): hand-computed values
  for a 2-team, 2-game fixture — possessions, eFG%, TOV rate, ORB%/DRB%
  complementarity, cumulative progression across dates, missing box score →
  stats omitted, not zeroed.
- `TeamStatTimeSeriesServiceTest` (integration): end-to-end persist + read-back;
  z-scores across 3+ teams; ranks respect `higherIsBetter`; per-conference
  z-scores; population rows written to `season_population_stats`; idempotency;
  **watermark equivalence test** (same pattern as Phase 2).
- `ScrapeOrchestratorTest`: pipeline service invoked inside the gated block.

---

## Sequencing & verification

1. After each phase: `./mvnw test` green (Docker/Testcontainers).
2. Phase commits kept separate for reviewability.
3. Out of scope (Phase 4 of the analysis): `COPY`-based loads, partitioning.
