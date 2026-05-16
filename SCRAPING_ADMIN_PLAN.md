# Scraping Admin Redesign Plan

Draft proposal — for review, not yet implemented.

## 1. Why we're changing it

Today, after adding a new season the admin is faced with **nine** per-row buttons
(Full Scrape, Re-scrape, Teams, Odds Backfill, Game Stats, Calc Stats, Time Series,
Power Ratings, Remove) and no indication of which to press, in what order, or
what state the season is in. There is no answer to questions like:

- "I just added 2027 — what now?"
- "Is anything running right now? How far along is it?"
- "Is the 2026 season fully scraped, or am I missing odds / box scores / a few
  dates?"
- "When did the 12-hour scheduled re-scrape last fire? When is the next one?"

The current dashboard only shows the most recent 20 `ScrapeBatch` rows, polled
every 5 seconds. That tells you *what happened*, but not *what is the season's
state* or *what is happening right now inside a long batch*.

## 2. Goals

1. **Set-it-and-forget-it onboarding.** Adding a season should be enough; the
   system should know what to scrape and do it in order, on its own.
2. **Live scrape visibility.** While a batch is running, surface the
   in-progress step (e.g. "GAMES — date 142 of 181, 3 failed") instead of
   just `RUNNING`.
3. **Per-season health.** For each season, show at a glance whether the data
   is complete: are all dates covered? are all FINAL games' box scores in? do
   we have odds for the games that had odds?
4. **Predictable automation.** The 12h cron should be visible (last fired,
   next fire) and adjustable; ad-hoc actions should be the exception, not the
   norm.

## 3. Proposed admin UI

Replace the current Seasons table on `/admin` with a stack of **Season Cards**.
One card per season, with three regions:

```
+----------------------------------------------------------------+
| 2026 NCAA Men's Basketball Season            [Re-scrape now ▼] |
|  Nov 1 2025 – Apr 30 2026                                      |
+----------------------------------------------------------------+
| Pipeline:                                                      |
|   ✓ Conferences   33 active           updated 2h ago           |
|   ✓ Teams         362 active          updated 2h ago           |
|   ✓ Standings     362/362 teams       updated 2h ago           |
|   ▣ Games         147/181 dates  ⟳ running, date 148 of 181    |
|   • Game stats    1,932/2,104 final games  (172 missing)       |
|   • Odds          1,840/1,932 with pre-game odds (92 missing)  |
|   ✓ Calc stats    362 teams                                    |
|   ✓ Time series   362 teams                                    |
|   ✓ Power ratings 362 teams           updated 2h ago           |
+----------------------------------------------------------------+
| Diagnostics                                                    |
|   • 12 games still IN_PROGRESS (probably stale)                |
|   • 4 dates with > 50% failed events                           |
|   • 7 teams have games but no SeasonStatistics                 |
|   • Next scheduled re-scrape: in 4h 12m                        |
|   [ View recent batches ]   [ Open Scraping QA ]               |
+----------------------------------------------------------------+
```

The single primary action becomes **"Re-scrape now"** (with a dropdown for
"Full re-scrape from scratch" / "Re-scrape only what's stale" / "Just X").
All the granular buttons stay, but tucked behind a "Advanced" disclosure.

When a season is **brand new** (no batches yet), the card collapses to a
single call-to-action:

```
+----------------------------------------------------------------+
| 2027 NCAA Men's Basketball Season           [Initialize ▶]     |
|  Nov 1 2026 – Apr 30 2027                                      |
|  No data yet. Initialize to run the full pipeline.             |
+----------------------------------------------------------------+
```

## 4. Set-it-and-forget-it

Two layers:

### 4a. Auto-initialize on add (opt-out checkbox, default ON) ✅ decided

When the admin POSTs `/admin/seasons` with `initialize=true` (default), kick
off `scrapeFullSeasonAsync` immediately after persisting the `Season`. An
"Initialize on save" checkbox sits next to the Year input, pre-checked. The
existing orchestrator already covers the full pipeline; we just remove the
need for the admin to remember to click "Full Scrape".

### 4b. Per-season scheduling, not just a global cron ✅ decided (multi-season allowed)

Today `ScrapeScheduler` re-scrapes whichever year `determineCurrentSeasonYear()`
returns. Add a per-`Season` `autoRefresh` flag (default `true` for the season
matching the current real-world year, `false` otherwise) so:

- Old seasons stop being touched once they're complete.
- A future season can be pre-loaded and then auto-refresh kicks in when its
  start date arrives.
- The admin can pause auto-refresh on a season without code changes (e.g.
  during ESPN outages).

The scheduler iterates over `seasonRepository.findByAutoRefreshTrue()` instead
of computing one year. **Multiple seasons can have `autoRefresh=true`
simultaneously** — e.g. late October when last year's tournament data is
still being touched up and this year's tipoff is imminent. The scheduler
runs them sequentially within one scheduled tick (still guarded by the
existing `AtomicBoolean running` lock) to avoid stacking ESPN load.

## 5. Live scrape status

Currently `ScrapeBatch` records `recordsCreated`, `recordsUpdated`,
`datesSucceeded`, `datesFailed`, and gets saved to the DB every 10 dates
inside `scrapeDateRange()`. The UI shows these but not what the batch is
*currently working on*.

### 5a. Add `currentStep` + `progressTotal` to `ScrapeBatch`

Two new nullable columns:

| Column           | Type | Meaning                             |
|------------------|------|-------------------------------------|
| `current_step`   | TEXT | e.g. `"GAMES 2026-02-14"`, `"ODDS"` |
| `progress_total` | INT  | e.g. `181` (dates in range)         |

`datesSucceeded + datesFailed` already gives "progress so far"; combined with
`progress_total` we can render a percentage and ETA. The scraper updates
`currentStep` at the top of each per-date loop iteration and saves more often
(every iteration is fine — these rows are small).

### 5b. Treat the full-season pipeline as one tracked unit ✅ decided (Option A)

Right now the orchestrator creates **separate** `ScrapeBatch` rows for
conferences, teams, standings, games, odds backfill, and game stats. The
admin sees them as six independent entries.

Add a `pipeline_run_id` (UUID) column on `ScrapeBatch`, plus a
`pipeline_step_order` integer. `ScrapeOrchestrator.scrapeFullSeason` /
`scrapeCurrentSeason` mints one UUID at entry and threads it through to
each child scraper, which stamps it on its `ScrapeBatch` before saving.
The dashboard groups batches by `pipeline_run_id` and shows them as one
expandable row: the parent row shows aggregate progress + the
currently-executing step, the children are revealed on click.

`pipeline_run_id IS NULL` is the marker for ad-hoc/Advanced-button runs —
those continue to render as standalone rows for backward compatibility with
existing history.

### 5c. HTMX polling already covers refresh ✅ decided (poll all cards)

The dashboard already has `hx-trigger="every 5s"` on `#scrape-history`. Each
Season Card gets the same wrapper around its Pipeline + Diagnostics regions,
polling every 5s unconditionally. With 5 cards that's ~1 req/sec on a
single-admin surface — negligible, and not worth the complexity of
selectively polling only active cards. The card-fragment endpoint
(`GET /admin/season-card/{year}`) returns the same HTML whether or not
anything is running, so the server cost is one cheap `SeasonHealthService`
call per poll.

## 6. Per-season diagnostics

A new read-only service, `SeasonHealthService`, that returns a
`SeasonHealth` record built from cheap repository counts. All but one of
the counts come from existing tables — the exception is the non-D1
opponent count (see 6a below), which needs a small new observation table.

```java
public record SeasonHealth(
    int seasonYear,
    int conferenceCount,
    int activeTeamCount,
    int totalDates,
    int scrapedDates,             // distinct game.scrape_date for the season
    int totalGames,
    int finalGames,
    int inProgressGames,          // probably-stale flag
    int gamesWithStats,           // join to TeamGameStats
    int gamesWithOdds,            // join to BettingOdds
    int teamsWithStandings,       // join to SeasonStatistics
    int nonD1OpponentGames,       // see 6a — games we drop because one team is unknown
    LocalDateTime lastFullRun,
    LocalDateTime lastIncrementalRun,
    LocalDateTime nextScheduledRun
) {}
```

Diagnostics shown in the card are derived purely from this record — no joins
in the template. The existing **Scraping QA** page (calc-vs-scraped
mismatches) stays as the deep-dive link.

### 6a. Tracking non-D1 opponent games ✅ decided (new observation table)

When ESPN's scoreboard returns a game with a team whose ESPN ID we don't
recognize, `GameScraper.upsertGame` currently logs a warning and drops the
game on the floor (`GameScraper.java:171-174`). In practice these are
almost always non-Division-I opponents (D-II/D-III schools that occasionally
play a D-I team in November). We don't want them in our statistical tables,
but we **do** want to count them so D-I season totals can be tied out
against ESPN's own season-stats page.

**Where to keep the count.** Three options considered:

| Option | What | Why not / why so |
|--------|------|------------------|
| (a) Counter on `ScrapeBatch` (e.g. `skippedUnknownTeam INT`) | Cheapest — one column | Per-run only; re-scrapes overwrite the count; you can't see *which* games were skipped |
| (b) **New `non_d1_game_observations` table** | One row per (espn_game_id, season) with home/away ESPN IDs and the unknown team IDs | Authoritative count + drill-down. Lets the admin spot a borderline team that should actually be onboarded |
| (c) Generic `season_diagnostics` cache table with pre-computed counts | Could hold lots of derived numbers | Silent staleness; two sources of truth for things current tables already know |

**Going with (b).** The migration is tiny and the upsert call sits in
exactly the place that currently throws data away. Schema:

```sql
CREATE TABLE non_d1_game_observations (
    id BIGSERIAL PRIMARY KEY,
    espn_game_id TEXT NOT NULL UNIQUE,
    season_year INT NOT NULL,
    scrape_date DATE NOT NULL,
    game_date_utc TIMESTAMP,
    home_espn_id TEXT NOT NULL,
    away_espn_id TEXT NOT NULL,
    unknown_team_espn_ids TEXT NOT NULL,  -- comma-separated; usually one
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_non_d1_obs_season ON non_d1_game_observations (season_year);
```

`SeasonHealth.nonD1OpponentGames` is `SELECT COUNT(*) FROM
non_d1_game_observations WHERE season_year = ?`. The card surfaces it as
e.g.:

```
  • 47 games against non-D1 opponents (excluded from stats)  [view]
```

…where `[view]` opens a small drill-down showing the unknown ESPN team IDs
grouped by frequency — so the admin can decide whether any of them are
actually misclassified D-I teams worth onboarding via
`TeamScraper.fetchAndSaveUnknownTeam`.

### Things the diagnostics surface that we can't see today

- Games stuck in `IN_PROGRESS` more than 24 hours after `gameDate` (ESPN
  occasionally drops the FINAL transition).
- FINAL games missing `TeamGameStats` — the `GameStatsScraper` is idempotent
  but only runs as part of the pipeline, so any failures silently linger.
- FINAL games missing `BettingOdds` that were never picked up by the
  scoreboard *or* the odds backfill.
- Teams that appear in games but have no `SeasonStatistics` row (= standings
  scrape missed them).
- The "dates scraped vs. dates in season window" ratio — the only
  user-visible signal today is whether a batch's status is PARTIAL.

## 7. Schedule visibility

Add a small admin section above the season cards:

```
Automation
  Every 12h re-scrape:    enabled  (cron: 0 0 */12 * * *)
  Last fired:             2026-05-16 04:00  (PIPELINE, COMPLETED, 38m)
  Next fire:              2026-05-16 16:00
  Seasons in scope:       2026
  [ Pause ]  [ Run now ]  [ Edit schedule ]
```

"Last fired" is derived from `ScrapeBatch` (most recent batch whose source =
`SCHEDULED`); "next fire" is computed from the Spring `CronExpression` (it
can answer `.next(LocalDateTime.now())`). Add a `source` enum column on
`ScrapeBatch`: `MANUAL`, `SCHEDULED`, `AUTO_INITIALIZE`.

## 8. Data model changes (summary)

A single Flyway migration `V<next>__scrape_pipeline_tracking.sql`:

```sql
ALTER TABLE scrape_batches
    ADD COLUMN pipeline_run_id UUID,
    ADD COLUMN pipeline_step_order INTEGER,
    ADD COLUMN current_step TEXT,
    ADD COLUMN progress_total INTEGER,
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

CREATE INDEX idx_scrape_batches_pipeline_run_id
    ON scrape_batches (pipeline_run_id);

ALTER TABLE seasons
    ADD COLUMN auto_refresh BOOLEAN NOT NULL DEFAULT FALSE;

-- Default the current real-world season to auto-refresh=true via app code,
-- not in SQL, so we don't hard-code a year in a migration.

CREATE TABLE non_d1_game_observations (
    id BIGSERIAL PRIMARY KEY,
    espn_game_id TEXT NOT NULL UNIQUE,
    season_year INT NOT NULL,
    scrape_date DATE NOT NULL,
    game_date_utc TIMESTAMP,
    home_espn_id TEXT NOT NULL,
    away_espn_id TEXT NOT NULL,
    unknown_team_espn_ids TEXT NOT NULL,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_non_d1_obs_season ON non_d1_game_observations (season_year);
```

## 9. Implementation phases

Small, independently shippable steps. Each ends with a working dashboard.

**Phase 1 — Diagnostics + non-D1 observations.**
Single Flyway migration: just the `non_d1_game_observations` table. Add
`SeasonHealthService` + Season Cards rendering the health panel. Wire the
upsert into `GameScraper.upsertGame` where unknown teams are dropped today.
Keep the existing 9 buttons untouched.
*Value: admin can finally answer "what's missing for season X?" and tie out
to ESPN's season-stats page.*

**Phase 2 — Pipeline grouping + live progress.**
Migration for `pipeline_run_id`, `pipeline_step_order`, `current_step`,
`progress_total`, `source`. Update `ScrapeOrchestrator` to mint and thread
one UUID per pipeline run. Update scrapers to set `currentStep` per
iteration. UI groups batches by run and renders a progress bar.
*Value: "is anything running, and how far along is it?" is answered.*

**Phase 3 — Set-it-and-forget-it.**
`auto_refresh` column on `seasons`. Scheduler iterates flagged seasons
sequentially (still guarded by the existing single-run lock). Add-season
flow defaults to `initialize=true` and `auto_refresh=true`. Collapse the
9 buttons into a single "Re-scrape now ▼" with an "Advanced" disclosure
preserving them for power use.
*Value: adding a season is genuinely one click.*

**Phase 4 — Schedule visibility.**
Render the Automation section with cron + last/next fire + per-season scope.
Optional: a "Run scheduled re-scrape now" button that simply calls
`ScrapeScheduler.scheduledScrape()` so we can test the same code path
manually.

## 10. Resolved decisions

1. **Auto-initialize on add → ON by default.** Adding a season triggers
   `scrapeFullSeasonAsync` immediately. An "Initialize on save" checkbox
   sits next to the Year input, pre-checked, for the rare case the admin
   wants to defer.
2. **`auto_refresh` → may be true on multiple seasons at once.** The
   scheduler iterates them sequentially within one tick, still gated by
   the existing `AtomicBoolean running` lock so we never stack ESPN load.
3. **Pipeline run grouping → Option A (UUID column on `scrape_batches`).**
   No new parent table; UI groups by `pipeline_run_id`.
4. **Non-D1 opponent games → new `non_d1_game_observations` table.**
   Captured in `GameScraper.upsertGame` where we currently throw the data
   away. Counted in `SeasonHealth.nonD1OpponentGames`; drill-down lets the
   admin spot misclassified teams worth onboarding. Schema in §6a / §8.
5. **Polling → all cards poll every 5s.** Simple, ~1 req/sec on a
   single-admin surface, not worth gating.
