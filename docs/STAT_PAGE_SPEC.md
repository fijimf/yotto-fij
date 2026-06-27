# Per-Statistic Pages — Specification

Status: **APPROVED — ready to build** · Owner: web/stats · Last updated: 2026-06-27

> All open questions resolved; see §11 for the decision log. This section is the
> source of truth — the body below already reflects the answers.

## 1. Goal

A dedicated web page for every derived team statistic. Each page explains the
stat in plain language and visualizes it two ways, side by side:

1. **Game scatter** — one point per completed game: x = home team's value of the
   stat in that game, y = away team's value, colored by who won (home win =
   green, home loss = red). If the stat alone predicts the winner reasonably
   well, surface a single headline number for that.
2. **Team distribution** — a histogram of the season-to-date value for every
   team, with a kernel-density (KDE) curve behind the bars, and a one-line
   summary of population min / max / mean / std dev underneath.

Below the charts, a full table of every team **ranked** by the stat.

The set of stats is exactly the registry in `BoxScoreStatCalculator` (26 today;
the page set is generated from the registry, so adding a stat there
automatically yields a page).

## 2. Scope & non-goals

- In scope: the 26 box-score-derived stats in `BoxScoreStatCalculator.REGISTRY`.
- League-wide context only. No conference-scoped toggle in v1 (the data exists —
  `confZscore`, per-conference population rows — but it stays unused here).
- Out of scope (this pass): the wide cumulative metrics in
  `TeamSeasonStatSnapshot` (win%, RPI, margin, etc.), power ratings, and the
  existing season-stats leaderboard page (unchanged except we add links into the
  new pages from it).
- No new persisted tables or Flyway migrations. All data comes from existing
  snapshot/population tables plus on-the-fly per-game computation.

## 3. Routes & discovery

| Method | Path | Returns | Notes |
|---|---|---|---|
| GET | `/stats` | `pages/stats-index` | Glossary: all stats grouped by category, each linking to its page. |
| GET | `/stats/{statName}` | redirect → `/seasons/{latest}/stats/{statName}` | Latest-season convenience entry. |
| GET | `/seasons/{year}/stats/{statName}` | `pages/stat-detail` | The stat page for a given season. |
| GET | `/seasons/{year}/stats/{statName}/table` | `fragments/stat-rank-table` | HTMX fragment for the ranking table (date/season switch without full reload). |

- `statName` is the registry key (e.g. `efg_pct`, `off_efficiency`). Unknown
  names → 404 via `EntityNotFoundException` (consistent with existing handler).
- Latest season resolved with `seasonRepository.findTopByOrderByYearDesc()`,
  mirroring `StatsWebController`.
- Optional `?date=` query param (defaults to the latest snapshot date for the
  season) so a user can view the page as of a past date. **Both the distribution
  and the scatter respect this date**: the distribution/ranking use the snapshot
  on that date; the scatter uses every FINAL game in the season **up to and
  including that date** (whole season when no date given).

**Discovery (no top-level nav link).** The pages are reachable only via links
from other pages — there is **no** "Stats" entry added to `fragments/nav.html`:

- On the existing season-stats leaderboard, each stat column header / stat name
  links to `/seasons/{year}/stats/{statName}`.
- The team detail page's stat panel links each stat row to its page.
- The `/stats` glossary is linked from the season-stats page header ("about
  these stats") and each stat-detail page carries a breadcrumb back to it.

## 4. Page layout (stat-detail)

```
┌─────────────────────────────────────────────────────────────────────┐
│  Effective Field Goal Percentage                        [2026 ▼]      │  ← full title, no abbrev; season picker
│  Shooting efficiency that counts 3-pointers as worth 1.5×...          │  ← description (omitted for obvious stats)
│  Higher is better · league avg 50.4% · as of Feb 14, 2026            │  ← quick context line
├──────────────────────────────────┬──────────────────────────────────┤
│  PER-GAME OUTCOMES               │  TEAM DISTRIBUTION                 │
│                                  │                                   │
│   away ▲                         │   ▁▃▅█▇▅▃▁  (histogram + KDE)      │
│   stat │   · ·● ●·               │                                   │
│        │ ●· ●●· ·●  (green=home  │                                   │
│        │·● ●·● ·● ·   win,       │                                   │
│        └───────────► home stat   │                                   │
│                                  │                                   │
│   Predicts home win: AUC 0.78    │   min 41.2  max 58.9              │
│   (better value wins 71% of      │   mean 50.4  std 3.1  (n=362)     │
│    games)                        │                                   │
│   based on 1,204 of 1,330 games  │                                   │
│   with box scores                │                                   │
├──────────────────────────────────┴──────────────────────────────────┤
│  TEAM RANKINGS                                          [date ▼]      │
│  #   Team            Value     z     Pctl    GP                       │
│  1   Houston         57.4%   +2.3    99      28                       │
│  2   ...                                                              │
└─────────────────────────────────────────────────────────────────────┘
```

- Two charts side by side on desktop; stacked on viewports < 768px (existing
  breakpoint).
- Title uses the **full name with no abbreviation** (catalog `title`).
- Description shown only when catalog `description` is non-null (obvious stats —
  e.g. `fg_pct`, `rpg`, `apg` — have `null` description and render no blurb).
- Scatter carries a coverage caveat line: *"based on N of M completed games with
  box scores"* (§6.2).

## 5. The missing piece: a standalone stat metadata catalog

The registry today carries only `name` + `higherIsBetter`. The pages need a
display title, optional description, value format, and category.

**Decision (D1, standalone):** these display fields live in a **separate
`StatCatalog`** keyed by stat name, *not* on the registry's `StatDef`/`StatMeta`.
Rationale: keeps the calc-pipeline contract (`DailyStatCalculator.StatMeta`)
untouched — adding display metadata there would ripple through every calculator
consumer. The cost (two lists that must stay in sync) is bought off by a
**completeness test** (§10) that fails the build if any `REGISTRY` name lacks a
catalog entry.

`StatCatalog` (new, `service/StatCatalog.java` or `web/StatCatalog.java`):

```java
record StatInfo(
    String name,            // registry key, e.g. "efg_pct"
    String title,           // "Effective Field Goal Percentage"
    String category,        // "Shooting", "Four Factors", ...
    String description,     // nullable; null = obvious, render no blurb
    Format format,          // PERCENT | RATE | RATING | PER_GAME | RATIO
    boolean higherIsBetter  // mirrored from registry; asserted equal in test
) {}
enum Format { PERCENT, RATE, RATING, PER_GAME, RATIO }

// keyed lookup; throws EntityNotFoundException on unknown name
static StatInfo require(String name);
static List<StatInfo> all();           // for the /stats glossary, grouped by category
```

`higherIsBetter` is duplicated into the catalog for convenience but the
completeness test asserts it matches `BoxScoreStatCalculator.statMetas()`, so the
two can't silently diverge.

`Format` drives client rendering:

| Format | Source value | Displayed as | Example |
|---|---|---|---|
| PERCENT | 0–1 ratio | `value×100`, 1 dp, `%` | `0.574` → `57.4%` |
| RATE | 0–1 ratio | `value×100`, 1 dp, `%` | `0.183` → `18.3%` |
| RATING | points/100 or poss/game | 1 dp | `112.3` |
| PER_GAME | count/game | 1 dp | `37.4` |
| RATIO | unitless ratio | 2 dp | `1.45` |

`PERCENT` vs `RATE` differ only in label semantics (a true shooting split vs a
per-possession rate); both render identically, but the distinction lets us word
descriptions/axes correctly. **Kept distinct** (decision A5).

### 5.1 Catalog values for all 26 stats (final)

`hib` = higher is better.

| name | title | category | format | hib | description (null = none) |
|---|---|---|---|---|---|
| `pace` | Pace | Efficiency | RATING | ✓ | Possessions per game; how fast a team plays. |
| `off_efficiency` | Offensive Efficiency | Efficiency | RATING | ✓ | Points scored per 100 possessions. |
| `def_efficiency` | Defensive Efficiency | Efficiency | RATING | ✗ | Points allowed per 100 possessions. |
| `efg_pct` | Effective Field Goal Percentage | Four Factors | PERCENT | ✓ | Field-goal shooting that credits a 3-pointer as 1.5 made shots. |
| `opp_efg_pct` | Opponent Effective Field Goal Percentage | Four Factors | PERCENT | ✗ | Opponent eFG%; a defense's shot-quality suppression. |
| `tov_rate` | Turnover Rate | Four Factors | RATE | ✗ | Share of possessions that end in a turnover. |
| `opp_tov_rate` | Opponent Turnover Rate | Four Factors | RATE | ✓ | Share of opponent possessions a defense forces into turnovers. |
| `orb_pct` | Offensive Rebound Percentage | Four Factors | PERCENT | ✓ | Share of available offensive rebounds a team grabs. |
| `drb_pct` | Defensive Rebound Percentage | Four Factors | PERCENT | ✓ | Share of available defensive rebounds a team grabs. |
| `ft_rate` | Free Throw Rate | Four Factors | RATE | ✓ | Free throws made per field-goal attempt; how often a team gets to the line. |
| `opp_ft_rate` | Opponent Free Throw Rate | Four Factors | RATE | ✗ | Opponent free throws made per FGA; fouling tendency of a defense. |
| `ts_pct` | True Shooting Percentage | Shooting | PERCENT | ✓ | Scoring efficiency across 2s, 3s, and free throws combined. |
| `fg_pct` | Field Goal Percentage | Shooting | PERCENT | ✓ | _null_ |
| `fg3_pct` | Three-Point Percentage | Shooting | PERCENT | ✓ | _null_ |
| `ft_pct` | Free Throw Percentage | Shooting | PERCENT | ✓ | _null_ |
| `fg3_rate` | Three-Point Attempt Rate | Shooting | RATE | ✓ | Share of field-goal attempts taken from three. |
| `trb_pct` | Total Rebound Percentage | Rebounding | PERCENT | ✓ | Share of all available rebounds a team grabs. |
| `rpg` | Rebounds Per Game | Rebounding | PER_GAME | ✓ | _null_ |
| `orpg` | Offensive Rebounds Per Game | Rebounding | PER_GAME | ✓ | _null_ |
| `drpg` | Defensive Rebounds Per Game | Rebounding | PER_GAME | ✓ | _null_ |
| `apg` | Assists Per Game | Playmaking | PER_GAME | ✓ | _null_ |
| `ast_to_ratio` | Assist-to-Turnover Ratio | Playmaking | RATIO | ✓ | Assists divided by turnovers. |
| `assisted_fg_pct` | Assisted Field Goal Percentage | Playmaking | PERCENT | ✓ | Share of made field goals that were assisted. |
| `stl_rate` | Steal Rate | Defense | RATE | ✓ | Steals per opponent possession. |
| `blk_pct` | Block Percentage | Defense | PERCENT | ✓ | Share of opponent 2-point attempts blocked. |
| `pf_per_game` | Personal Fouls Per Game | Defense | PER_GAME | ✗ | _null_ |

Copy is **final** — build to it as written (decision A2).

## 6. Data & computation

### 6.1 Team distribution (right chart) — reuse existing snapshots

The histogram is over **one value per team** = each team's cumulative value of
the stat as of the chosen date. This is already computed and stored.

- Date: `?date=` or `teamStatSnapshotRepository.findLatestSnapshotDate(seasonId)`.
- Values + ranking rows:
  `teamStatSnapshotRepository.findBySeasonStatAndDate(seasonId, statName, date)`
  → returns each team's `value`, `rank`, `zscore`, `confZscore`, `gamesPlayed`,
  ordered by rank.
- Population min/max/mean/std/count: pulled from the matching
  `SeasonPopulationStat` **league-wide** row via
  `seasonPopulationStatRepository.findLeagueWideBySeasonAndDate(seasonId, date)`
  filtered to `statName` (`popMin`, `popMax`, `popMean`, `popStddev`,
  `teamCount`). These are authoritative — do **not** recompute from the team
  list, so the summary line matches the rest of the app.
- **Histogram bins + KDE:** computed **server-side** from the team value list
  and shipped as ready-to-draw arrays (bin edges + counts; KDE x/y samples).
  Rationale: keeps the client dumb and identical to how `game-chart.js` receives
  pre-derived geometry. Bin count via Freedman–Diaconis with a sane clamp
  (e.g. 8–30 bins); KDE via Gaussian kernel, bandwidth by Silverman's rule.

### 6.2 Per-game scatter (left chart) — compute per game, reuse the formula

There is **no stored per-game stat value** — only cumulative snapshots and raw
`TeamGameStats`. But `BoxScoreStatCalculator.TeamAcc.addGame(...)` already
computes exactly one game's contribution, and the registry extractors run over a
`TeamAcc`. So a single-game value is *a `TeamAcc` with exactly one game added*,
and using it guarantees the scatter axes match the stat's real definition.

**Decision (D2):** expose per-game computation from `BoxScoreStatCalculator`
rather than duplicating formulas. Add a public method, e.g.:

```java
/** All stat values for one finished game, from each team's perspective.
 *  Returns null entries for stats whose denominator is 0 that game. */
public static Map<String, GamePoint> perGame(Game g,
                                              TeamGameStats home,
                                              TeamGameStats away);
// GamePoint = (Double homeValue, Double awayValue)  // home = own from home POV
```

Internally: build a one-game `TeamAcc` for home (own=home box, opp=away box) and
one for away, run every `StatDef.extractor`. This reuses `isUsable`, the
possession estimator, and all 26 formulas with zero duplication. (`perGame`
lives on the registry/calculator; this is the *only* registry change — the
display catalog stays standalone per D1.)

**Assembling the scatter dataset (server-side):**
1. Games: `gameRepository.findBySeasonIdAndStatus(seasonId, FINAL)`, filtered to
   `gameDate <= date`. Call this count **M** (the denominator of the caveat).
2. Box scores: load `teamGameStatsRepository.findBySeasonId(seasonId)` once and
   group by `gameId` in memory (one query, not 2×N — same one-pass spirit as the
   stats pipeline).
3. Skip games where either box score is missing/`!isUsable` (mirrors the
   calculator — never plot half a game).
4. Each surviving game → one point: `{ x: homeValue, y: awayValue,
   homeWin: homeScore > awayScore }`. Drop points where either value is null
   (zero denominator that game). Call the surviving count **N**.

**Coverage caveat (A8):** always render *"based on N of M completed games with
box scores"* under the scatter so a sparse early-season plot isn't misread.

Point colour: `homeWin` → success green (`--color-success`), else danger red
(`--color-danger`).

Rendering is **SVG always** (A3) — see §8. A full season is a few thousand
points, which SVG handles fine and keeps per-point hover trivial.

### 6.3 Predictive headline number

"How good is this value alone at predicting the winner." Computed server-side
over the scatter games, **direction-aware** via `higherIsBetter`.

Define, per game, the home advantage in the stat:
`d = (homeValue − awayValue)` if `higherIsBetter` else `(awayValue − homeValue)`.
Outcome label `y = homeWin`.

The **headline metric is AUC** — area under the ROC of `d` ranking home wins;
equivalently the Mann–Whitney statistic = P(d higher in a home win than in a
home loss). Robust to scale, 0.5 = coin flip, 1.0 = perfect. Computed by
rank-sum, O(n log n). Displayed as *"Predicts home win: AUC 0.78."*

Underneath, as a plain-language gloss, the **naive-rule accuracy** — "the team
with the better single-game value wins": `mean( sign(d) == (homeWin?+1:−1) )`,
ties excluded — rendered as *"(better value wins 71% of games)."*

**Gate (A4):** show the predictive block **only when AUC ≥ 0.55**; otherwise
render a muted *"weak standalone predictor"* note. (The accuracy gloss rides
along with the AUC block — it is not independently gated.)

> Note: this measures the *single-game* stat's correlation with *that game's*
> result, the most honest reading of the request ("how good this value alone is
> in predicting winner"). It is descriptive, not a trained model.

### 6.4 Ranking table (below charts)

Straight from `findBySeasonStatAndDate` (already rank-ordered):

| Column | Source |
|---|---|
| Rank | `rank` |
| Team (logo + name, link to `/teams/{slug}/season/{year}`) | join via `teamsById` / team repo |
| Value (formatted per `Format`) | `value` |
| z-score | `zscore` (league-wide) |
| Percentile | `100 × (teamCount − rank) / (teamCount − 1)` (direction already baked into `rank`) |
| GP | `gamesPlayed` |

HTMX fragment `/seasons/{year}/stats/{statName}/table` re-renders this on
date/season change, matching the existing season-stats `/table` pattern.

## 7. Backend design

New, thin, no migrations:

- **`StatPageController`** (web, returns Thymeleaf):
  `/stats`, `/stats/{statName}` (redirect), `/seasons/{year}/stats/{statName}`,
  `…/table`.
- **`StatCatalog`** (new): the standalone display catalog from §5 — `require`,
  `all`, the `Format` enum.
- **`StatPageService`**: orchestrates the three datasets and builds the DTO.
  - `StatPageDto build(int year, String statName, LocalDate date)`.
  - Validates `statName` via `StatCatalog.require` → 404 if unknown.
- **DTO** (`controller/dto/StatPageDto`), serialized to JSON inline like
  `ChartDataDto` (`ObjectMapper.writeValueAsString` → `JSON.parse` in template):

```
StatPageDto {
  meta:    { name, title, category, description, format, higherIsBetter,
             unitLabel, axisLabel }
  season:  { year, date, latestDate, availableSeasons[] }
  population: { min, max, mean, stddev, count }
  histogram:  { binEdges[], binCounts[], kde:{x[], y[]} }
  scatter:    { points:[{x,y,homeWin}], axisMin, axisMax,
                gamesTotal, gamesPlotted,           // M and N for the caveat
                predictive:{ auc, naiveAccuracy, show:boolean } }   // show = auc >= 0.55
  rankings:   [{ rank, teamSlug, teamName, color, logoUrl,
                 value, zscore, percentile, gamesPlayed }]
}
```

Server does **all** numeric work (bins, KDE, AUC, accuracy, formatting inputs);
the client only draws. This matches the established game-chart split and keeps
the page fast and testable (the math gets unit tests, not the DOM).

### 7.1 Reuse / refactor notes

- `BoxScoreStatCalculator`: add **only** the public `perGame(...)` (D2).
  `TeamAcc`, `isUsable`, extractors stay private and shared. The pipeline
  contract (`DailyStatCalculator.StatMeta`) is **not** touched — display
  metadata lives in the standalone `StatCatalog` (D1).
- A small `StatMath` helper (histogram binning, Gaussian KDE, AUC, naive
  accuracy) — pure functions, unit-tested independently.

## 8. Frontend design

- **Library:** D3.js v7 (already used by `game-chart.js`) for *both* charts.
  Chart.js can't draw a KDE overlay cleanly; D3 gives full control and we
  already load it on chart pages. Load D3 only on this page (per-page
  `layout:fragment="scripts"`), as game-detail does — not globally.
- **New JS:** `static/js/stat-page.js`, reads `window.STAT_PAGE_DATA`
  (inline JSON), renders:
  - `renderScatter(el, data)` — **SVG**, square plot, shared x/y domain
    `[axisMin, axisMax]`, faint y=x diagonal, points colored by `homeWin`, hover
    tooltip (teams + score + both values). No canvas path — SVG always (A3).
  - `renderHistogram(el, data)` — bars from `binEdges/binCounts` + KDE path from
    `kde.x/kde.y` on a secondary density scale; mean line marker.
- **CSS:** add a `stat-detail` block to `main.css` reusing existing tokens
  (`--color-success`, `--color-danger`, `.card`, the 768px breakpoint). Side-by-
  side via CSS grid `grid-template-columns: 1fr 1fr` collapsing to one column on
  mobile. Follow the `.game-detail-chart-*` conventions for container/legend.
- **Value formatting** shared with the table via a small JS `formatValue(value,
  format)` mirroring the server `Format` enum.

## 9. Edge cases

- **No box scores yet** (early season / stats not scraped): scatter empty →
  render an empty-state ("No completed games with box scores yet"); hide the
  predictive block. The coverage caveat still shows `0 of M`.
- **Stat null for a team that date** (zero denominator cumulatively): excluded
  from histogram/ranking, consistent with snapshot rows never being written.
- **Single team / n=1**: percentile formula divides by `(count − 1)` → guard
  (show "—"). KDE needs n ≥ 2 and AUC needs at least one win and one loss; below
  that, hide those elements.
- **Unknown `statName`** → 404.
- **Season with no snapshots** (never calculated) → friendly "stats not yet
  computed for {year}" state; no admin trigger exposed.
- **`opp_*` and lower-is-better stats**: titles say "Opponent…"; the predictive
  `d` and percentile already flip via `higherIsBetter`. Verify green/red mapping
  is about *home win*, not about *who had the better stat* (the request is
  explicit: green = home won).

## 10. Testing

- Unit: `StatMath` (binning edges, KDE sample shape, AUC against a hand-checked
  tiny set, naive accuracy with ties).
- Unit: `BoxScoreStatCalculator.perGame` — a known box score yields the same
  value as a one-game cumulative snapshot (guards the reuse claim).
- Unit: **catalog completeness** — every `BoxScoreStatCalculator.statMetas()`
  name has a `StatCatalog` entry with a title + format, and the catalog's
  `higherIsBetter` matches the registry for each (guards the standalone-catalog
  drift risk from D1).
- Integration (`BaseIntegrationTest`): seed a season + a few FINAL games with
  `TeamGameStats` + snapshots, hit `/seasons/{year}/stats/efg_pct`, assert the
  model/JSON has the right point count (N/M), population numbers, and ranking
  order.

## 11. Decision log (resolved)

- **A1 (nav):** **No top-level nav link.** Pages are reached via links from other
  pages (season-stats leaderboard, team stat panel) and the `/stats` glossary.
- **A2 (copy):** **No review needed** — §5.1 titles/descriptions are final.
- **A3 (scatter scale):** **SVG always**, and the scatter **respects `?date=`**
  (games up to the chosen date).
- **A4 (predictive metric):** **AUC**, gated at **≥ 0.55**; naive-rule accuracy
  shown as a gloss beneath it.
- **A5 (format granularity):** **Keep** the PERCENT / RATE split.
- **A6 (catalog location):** **Standalone `StatCatalog`** keyed by name; do *not*
  extend the registry's `StatMeta`. Guarded by a completeness test.
- **A7 (conference view):** **League-wide only** in v1; no conference toggle.
- **A8 (coverage caveat):** **Show** "based on N of M completed games with box
  scores" under the scatter.
