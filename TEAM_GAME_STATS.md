# Team Game Stats — Motivation & Implementation Plan

> Living document. Mirror status checkboxes below as work proceeds so a fresh
> session can resume mid-flight without losing context.

## Motivation

Today the model stores per-game `homeScore` / `awayScore` on `games`, plus
season-level aggregate stats on `season_statistics`. There is **no per-game,
per-team box score detail** — no shooting splits, no rebounds, no turnovers,
nothing that would let us decompose a result.

We want to capture team-level box score statistics for every game (no player
stats). With this we can:

- Power richer team and matchup pages (shooting splits, eFG%, turnover rate,
  pace).
- Build better predictive features for `PowerRatingService` and the ML
  pipeline (currently both rely only on scores + opponent quality).
- Support QA on standings: confirm that aggregated game stats reconcile with
  ESPN's season totals.

## Decision: modify existing project, do not greenfield

The existing scraping subsystem (`EspnApiClient`, `ScrapeBatch`, rate
limiting, partial-success tracking, async admin triggers, admin dashboard,
deployment + Testcontainers infra) is exactly what this feature needs.
Greenfield would rebuild all of it for no model benefit — team game stats are
purely additive and slot in next to `BettingOdds` using the same
"one-to-many-from-Game scraped in a backfill pass" pattern.

## ESPN data source

**Endpoint:** `https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/summary?event={espnGameId}`

**Navigation path:** `root.boxscore.teams[i]`

Each entry contains:
- `team.id` — ESPN team id
- `homeAway` — `"home"` / `"away"`
- `statistics[]` — `{name, displayValue, abbreviation, label}` objects

Mapping (team-level only — we **ignore** `players[]`):

| ESPN `name`                                                | Maps to                              | Notes                                       |
|------------------------------------------------------------|--------------------------------------|---------------------------------------------|
| `fieldGoalsMade-fieldGoalsAttempted`                       | `fg_made`, `fg_attempted`            | Hyphen-split string like `"28-61"`          |
| `threePointFieldGoalsMade-threePointFieldGoalsAttempted`   | `fg3_made`, `fg3_attempted`          | Hyphen-split                                |
| `freeThrowsMade-freeThrowsAttempted`                       | `ft_made`, `ft_attempted`            | Hyphen-split                                |
| `totalRebounds`                                            | `total_reb`                          |                                             |
| `offensiveRebounds`                                        | `offensive_reb`                      |                                             |
| `defensiveRebounds`                                        | `defensive_reb`                      |                                             |
| `assists`                                                  | `assists`                            |                                             |
| `steals`                                                   | `steals`                             |                                             |
| `blocks`                                                   | `blocks`                             |                                             |
| `turnovers`                                                | `turnovers`                          |                                             |
| `fouls`                                                    | `fouls`                              |                                             |
| `technicalFouls`                                           | `technical_fouls`                    |                                             |
| `flagrantFouls`                                            | `flagrant_fouls`                     |                                             |
| `largestLead`                                              | `largest_lead`                       |                                             |
| `pointsInPaint`                                            | `points_in_paint`                    |                                             |
| `fastBreakPoints`                                          | `fast_break_pts`                     |                                             |
| `turnoverPoints`                                           | `turnover_pts`                       |                                             |

We deliberately do **not** store the precomputed `fieldGoalPct` etc. — we can
recompute from made/attempted on read and avoid storing data that can drift
out of sync.

## Cost / rate-limiting impact

One API call per FINAL game. A full D-I season is ~5,500 games. At default
`base-delay-ms = 200` + jitter that's ~20–25 minutes per season. Strategy:
- Run as a separate backfill pass (one season at a time).
- During in-season operation, only fetch stats for games that newly entered
  FINAL — incremental cost is trivial.

## Schema (`V11__team_game_stats.sql`)

```sql
CREATE TABLE team_game_stats (
    id              BIGSERIAL PRIMARY KEY,
    game_id         BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    team_id         BIGINT NOT NULL REFERENCES teams(id),
    home_away       VARCHAR(4) NOT NULL,
    fg_made         INTEGER,
    fg_attempted    INTEGER,
    fg3_made        INTEGER,
    fg3_attempted   INTEGER,
    ft_made         INTEGER,
    ft_attempted    INTEGER,
    offensive_reb   INTEGER,
    defensive_reb   INTEGER,
    total_reb       INTEGER,
    assists         INTEGER,
    steals          INTEGER,
    blocks          INTEGER,
    turnovers       INTEGER,
    fouls           INTEGER,
    technical_fouls INTEGER,
    flagrant_fouls  INTEGER,
    largest_lead    INTEGER,
    points_in_paint INTEGER,
    fast_break_pts  INTEGER,
    turnover_pts    INTEGER,
    scrape_date     TIMESTAMP NOT NULL,
    UNIQUE (game_id, team_id)
);
CREATE INDEX idx_team_game_stats_team ON team_game_stats(team_id);
CREATE INDEX idx_team_game_stats_game ON team_game_stats(game_id);
```

Two rows per game (home + away). Unique on `(game_id, team_id)` keeps the
scraper idempotent — re-runs upsert instead of duplicating.

## Implementation steps

- [x] **1. Planning doc** — this file.
- [x] **2. Migration** — `V11__team_game_stats.sql`.
- [x] **3. Entity + repository** — `TeamGameStats`, `TeamGameStatsRepository`.
- [x] **4. API client + enum** — `EspnApiClient.fetchGameSummary`,
      `ScrapeBatch.ScrapeType.GAME_STATS`.
- [x] **5. Repository query** — `GameRepository.findFinalGamesWithoutStats`.
- [x] **6. GameStatsScraper** — `scrapeForGame(Game)`, `backfill(int)`,
      parser for `"made-attempted"` strings, `ScrapeBatch` tracking.
- [x] **7. Orchestrator + async + admin wiring** — backfill step in
      `ScrapeOrchestrator` (full + current season),
      `AsyncScrapeService.backfillGameStatsAsync`, `POST
      /admin/scrape/game-stats/{year}`, dashboard "Game Stats" button.
- [x] **8. Tests** — `GameStatsScraperTest` (5 tests, all passing).
- [x] **9. Docs** — `SCRAPING.md` and `CLAUDE.md` updated.
- [x] **10. Build + tests** — `./mvnw clean package` clean;
      `GameStatsScraperTest` + sibling scraper tests green. Unrelated
      pre-existing `ComprehensiveRankingsControllerTest` failures remain
      from in-flight rankings-page deletion on the working tree.

## Open questions / followups (not blocking this PR)

- Whether to backfill the `pointsInPaint` / `fastBreakPoints` columns when
  ESPN omits them on older games (leave null for now).
- Future migration: derived `_pct` views or materialized columns for
  efficient ranking queries. Skip for now — compute on read.
- Consider extending `current-season` rescrape to opportunistically scrape
  stats for any game that *just* transitioned to FINAL on this pass. Deferred
  to keep this change focused — backfill covers the gap.

## Restart cheat sheet

If a session restarts mid-implementation:
1. `git status` to see what files have been touched.
2. Check the checkboxes above to see what's done.
3. Re-read this file's "ESPN data source" and "Schema" sections — those are
   the only design decisions; everything else is mechanical.
4. Resume at the first unchecked step.
