# Daily Stats Pipeline — Efficiency Analysis & Refactoring Plan

*Analysis date: 2026-06-12. Scope: the per-cycle calculation and persistence of
derived statistics — `StatsCalculationService`, `StatisticsTimeSeriesService`,
`MasseyRatingService`, `BradleyTerryRatingService` — as orchestrated by
`ScrapeOrchestrator` / `ScrapeScheduler` (every 12h per auto-refresh season),
and the read paths that serve the resulting snapshots.*

## Current shape of the pipeline

Every scheduled cycle, for each auto-refresh season:

1. `StatsCalculationService.calculateAndUpdateForSeason` — current-day aggregates
   into `season_statistics` (~360 rows, upsert).
2. `StatisticsTimeSeriesService.calculateAndStoreForSeason` — **delete + full
   rewrite** of `team_season_stat_snapshots` (~50k rows/season) and
   `season_population_stats` (~24k rows/season).
3. `PowerRatingService.calculateAndStoreForSeason` — **delete + full rewrite**
   of `team_power_rating_snapshots` for 4 model types (MASSEY, MASSEY_TOTALS,
   BRADLEY_TERRY, BRADLEY_TERRY_W ≈ ~200k rows/season) plus
   `power_model_param_snapshots` (~900 rows).

Approximate totals per cycle, per season: **~275k rows deleted and re-inserted,
twice a day**, whether or not any game changed.

---

## 1. Things we are doing right

**The in-memory math is genuinely efficient and scales well.**
- All four calculators are single-pass over games grouped by date, with O(1)
  incremental accumulators: running sums for mean/stddev/correlation
  (`TeamAcc`), rank-2 outer-product updates to the Massey normal equations, and
  warm-started Newton-Raphson for Bradley-Terry (1–3 iterations per date
  instead of 5–15 from cold). This is the right architecture for cumulative
  daily snapshots and it will absorb more per-game stats cheaply.
- The dense `(T+1)×(T+1)` solve per date (~365 teams, ~150 dates) is small
  potatoes for commons-math; total CPU per season recompute is seconds, not
  minutes. Compute is **not** the bottleneck.

**Deletes are bulk, not per-entity.** The repositories use
`@Modifying @Query("DELETE ...")` — one DELETE statement per season/model, with
`clearAutomatically = true`. No row-at-a-time entity deletion.

**The delete + insert happens inside one transaction**, so readers see an
atomic swap (old snapshot set until commit, never a half-written season).

**Indexing matches the query patterns.** Composite unique constraints double as
idempotency guards; `(season_id, model_type, snapshot_date)` and
`(team_id, season_id, model_type)` cover the two read shapes (leaderboard on a
date, team trajectory); partial unique indexes handle the nullable
`conference_id` on population stats correctly.

**Read paths avoid N+1.** Snapshot queries `JOIN FETCH s.team`;
`findLatestBefore` is a native `ORDER BY ... LIMIT 1` against an indexed
prefix; latest-date resolution uses `MAX(snapshot_date)` rather than loading
rows. Game loading for the calculators join-fetches both teams.

**Good separation of concerns in storage.** Team-level snapshots, model-level
scalar params (HCA, intercepts), and population distributions are separate
tables. Notably, `season_population_stats` is already **long format** (keyed by
`stat_name`) — the right precedent for what's coming.

**Orchestration order and safety.** Games are scraped before stats are
computed; `ScrapeScheduler.tryRunNow` uses a compare-and-set lock so scheduled
and manual runs can't overlap; everything is idempotent.

---

## 2. Things that could be improved

### 2.1 The write path is the bottleneck: row-at-a-time INSERTs (highest impact)

All snapshot entities use `GenerationType.IDENTITY`, and there is **no**
`hibernate.jdbc.batch_size` configured. With IDENTITY, Hibernate *cannot* batch
inserts even if configured — it needs the generated key back after each row. So
`saveAll(allRatings)` on ~200k power snapshots executes ~200k individual INSERT
statements, each a driver round-trip. That, not the math, is where the cycle
time goes, and it scales linearly with every stat we add.

The snapshots are fire-and-forget on the write path — nothing reads the
generated IDs back. They don't need to be managed JPA entities at all when
writing. A `JdbcTemplate.batchUpdate` writer (batch size 500–1000) or Postgres
`COPY` reduces this to a few hundred round-trips.

### 2.2 Full recompute + full rewrite every cycle, even when nothing changed

Out of season (like right now, in June), every 12h cycle still deletes and
rewrites ~275k identical rows per auto-refresh season. In season, a cycle that
only adds yesterday's games still rewrites November. Historical snapshot rows
are immutable once the games before that date are final.

Two-tier fix:
- **Short-circuit:** if no game rows changed since the last calc, skip the
  calculators entirely. Cheap to detect (e.g., `MAX(scrape_date)`/count of
  FINAL games vs. values recorded at last calc).
- **Watermark:** when games *did* change, find the earliest changed game date,
  replay the season **in memory** up to that date (cheap — it's the same
  single-pass math, just without persistence), and delete/insert only
  snapshots with `snapshot_date >= watermark`. The cumulative accumulators and
  BT warm-starts make this natural: math replays, I/O doesn't.

This also fixes the **MVCC churn**: deleting and reinserting ~550k rows/day per
season generates dead tuples and index bloat that autovacuum has to chase
forever on tables that are 99% static.

### 2.3 One giant transaction holding 250k entities in the persistence context

Each service accumulates every snapshot for the season into a list of managed
entities and saves in a single `@Transactional` method. That's hundreds of MB
of entity + persistence-context overhead at peak, dirty-checked at flush, and
it grows multiplicatively as stats are added. Moving to the JDBC writer (2.1)
eliminates the persistence context entirely; if staying on JPA, chunked
flush-and-clear is the fallback.

### 2.4 The season's games are loaded four times per cycle

`StatsCalculationService`, `StatisticsTimeSeriesService`, `MasseyRatingService`
and `BradleyTerryRatingService` each independently run
`findBySeasonIdAndStatus(FINAL)` (~6k games with two join-fetched teams) and
each rebuild the same date-grouping and team-index maps. Load once in the
orchestrator (or a shared context object) and pass it down.

### 2.5 Hidden side effect: `game.setConferenceGame()` inside stats calc

`StatsCalculationService` mutates `Game` entities (relying on dirty checking to
persist the flag) in the middle of a stats computation. It works, but it makes
the stats transaction write to the `games` table and couples "derive the flag"
to "compute season records." It belongs in the game upsert path
(GameScraper/StandingsScraper) or a small dedicated step.

### 2.6 Wide-table schema will not survive game-level stats (design decision)

`team_season_stat_snapshots` has one column per stat — and each *base* stat
actually costs ~5 columns (mean, stddev, league z-score, conference z-score,
rolling). Adding shooting/rebounding/possession stats (eFG%, TO%, ORB%, FT
rate, pace, off/def efficiency, …) the same way means **50+ new columns**, a
migration per stat, and hand-written getter/setter/z-score plumbing for each
(see the `GETTERS` map and `applyLeagueZscores` — already showing the strain).

`season_population_stats` already demonstrates the alternative: long format
keyed by `stat_name`. A long table for derived team-stat series —
`(team_id, season_id, snapshot_date, stat_name, value, zscore, conf_zscore, rank)`
— at ~365 teams × 150 dates × ~30 stats ≈ 1.6M rows/season is comfortable for
Postgres *if and only if* inserts are batched (2.1) and writes are incremental
(2.2). New stats then become registry entries, not migrations.

### 2.7 Pipeline ordering: box scores are scraped *after* stats are calculated

In `ScrapeOrchestrator.scrapeCurrentSeason`, the order is: games → stats calc →
time series → power ratings → odds backfill → **game stats backfill**. Today
that's harmless because no calculator consumes `TeamGameStats`. The moment
shooting/rebounding snapshots exist, they'd be computed from box scores that
are one cycle stale. The calc step must move after `gameStatsScraper.backfill`.

### 2.8 Minor: no per-phase timing

The services log row counts but not durations. Before optimizing further, add
elapsed-time logging per phase (and per save) so the wins in this plan are
measurable. Cheap and immediately useful.

---

## 3. Steps to eliminate, add, or reorder

**Eliminate**
- Per-row INSERT round-trips (IDENTITY + unbatched `saveAll`) → batched JDBC.
- Recomputation/rewrite of unchanged history → short-circuit + watermark.
- The 3 redundant season-game loads per cycle → load once, share.
- The `conferenceGame` side effect in `StatsCalculationService` → move to the
  scrape/upsert path.

**Add**
- Change detection before calculation (skip the whole calc block when no games
  changed — this alone removes ~100% of out-of-season write load).
- A per-phase timing log / simple metrics.
- A `DailyStatCalculator`-style interface so all calculators share one pass
  over `gamesByDate` (one game load, one loop, N calculators) — this is the
  extension point where shooting/rebounding calculators plug in.
- A stat registry + long-format snapshot storage for the new game-level
  derived stats (keep existing core W/L columns wide; don't migrate what
  works).

**Reorder**
- `gameStatsScraper.backfill` must run **before** the stat calculators once
  box-score-derived stats exist.

**Keep as-is**
- The incremental accumulator math, warm starts, and per-date solves.
- Bulk deletes, atomic-swap transaction semantics, the index design.
- Storing rank/params denormalized (cheap, makes reads trivial).
- The read-path query shapes (`JOIN FETCH`, `LIMIT 1` latest-before, etc.).

---

## 4. Plan of attack

Phases are independent and shippable in order; each is verifiable on its own.

### Phase 0 — Measure + free wins (small, no schema changes)
1. Add elapsed-time logging around each calc phase and each save.
2. Short-circuit: record `(final game count, max scrape_date)` per season at
   the end of each calc run (e.g., on `ScrapeBatch` or a tiny
   `stat_calc_watermarks` table); skip all three calculators when unchanged.
3. Move `conferenceGame` flag derivation out of `StatsCalculationService`.
4. Reorder orchestrator: `gameStatsScraper.backfill` before the calc block.

### Phase 1 — Fix the write path (the big one)
1. Introduce a small `SnapshotJdbcWriter` (JdbcTemplate `batchUpdate`, chunks
   of ~500–1000) for `team_season_stat_snapshots`,
   `team_power_rating_snapshots`, `power_model_param_snapshots`,
   `season_population_stats`. Services build plain DTOs/records instead of
   managed entities; the JPA entities remain for the read side.
2. Keep delete-then-insert semantics inside one transaction (atomic swap
   preserved).
3. Verify with Phase 0 timings — expect the save step to drop from minutes to
   seconds.

### Phase 2 — Incremental persistence (watermark)
1. Determine the earliest changed game date per cycle (simplest robust source:
   min `game_date` among games whose `scrape_date` is newer than the last calc
   watermark; or an `updated_at` column maintained on actual change if
   `scrape_date` proves too coarse).
2. Replay the season in memory up to the watermark (no writes), then
   delete + batch-insert only `snapshot_date >= watermark` rows for all four
   snapshot tables.
3. In-season steady state becomes: recompute everything (cheap), write ~1–3
   days of rows (~5–10k) instead of ~275k.

### Phase 3 — Extensibility for game-level stats
1. Refactor the four calculators behind a shared per-date pass: orchestrator
   loads games once, iterates `gamesByDate`, and feeds each registered
   calculator (`onGame(game)` / `snapshotsFor(date)`).
2. Add the stat registry + long-format `team_stat_snapshots` table (V18
   migration) with indexes mirroring the power-snapshot pattern:
   `(season_id, stat_name, snapshot_date)` and `(team_id, season_id, stat_name)`.
3. Generalize z-score/population machinery to operate on registry entries
   (the existing `GETTERS` map collapses into the registry).
4. Implement the first box-score calculators (four factors, pace, efficiency)
   as registry entries consuming `TeamGameStats`, loaded once per cycle
   alongside games.

### Phase 4 — Only if needed later
- Postgres `COPY` for bulk loads if batch INSERT proves insufficient.
- Partition snapshot tables by `season_id` once several seasons × 30+ stats
  accumulate (drop-partition beats DELETE for season re-runs).

### Risks / notes
- Phase 1 bypasses Hibernate on writes: keep entity ↔ SQL column lists in one
  place per table to avoid drift, and lean on the existing integration tests
  (Testcontainers) to verify the swap is faithful.
- Watermark correctness (Phase 2) depends on detecting *all* game mutations,
  including odds/box-score backfills if those ever feed snapshots — the
  conservative fallback (recompute-all-on-any-change) is always safe and still
  benefits from Phases 0–1.
- Long-format storage (Phase 3) trades per-stat columns for ~30× rows; it is
  only viable after Phase 1 (batching) and strongly helped by Phase 2.
