# Team Page — "Team Profile" Stats Panel Spec

Replaces the **Season Analytics** section on `pages/team-detail.html`
(lines 61–113 + its `<script>` block, lines 117–232).

## 1. Goal

We now calculate **22 box-score-derived stats per team per date** (efficiency,
four factors, shooting, rebounding, playmaking, defense), each with a national
**rank**, **z-score**, and **conference z-score**, persisted in
`team_stat_snapshots`. None of it is surfaced anywhere in the UI.

> The scoring-distribution stats (points-in-paint / fast-break / turnover-points
> shares) were removed: ESPN's summary endpoint does not populate those fields for
> NCAA men's basketball, so they had no data feeding them. The scraper still parses
> the raw fields in case that ever changes.

The current Season Analytics section shows only the legacy wide-format series
(win %, points for/against, a margin pill, a points-correlation pill) pulled from
`/api/statistics/team/{id}/season/{year}`. That view is thin and now redundant
with the richer league page.

**Replace it** with a *Team Profile* panel that shows, for the selected season's
latest snapshot date:

1. Every calculated stat, grouped by category, with the team's value, its
   **national rank** (`#42 of 363`), and a **percentile bar** colored good/bad.
2. A **trajectory chart**: pick any stat, see the team's value across the season
   with the national field band for context.

The legacy wide-format charts/endpoint stay available on the league
`/seasons/{year}/stats` page; they are simply removed from the team page.

## 2. Data sources (all already exist — no schema work)

| Need | Source |
|---|---|
| Team's 25 stats on a date | `TeamStatSnapshotRepository.findByTeamSeasonAndDate(teamId, seasonId, date)` |
| Latest snapshot date | `TeamStatSnapshotRepository.findLatestSnapshotDate(seasonId)` |
| One stat's season trajectory | `TeamStatSnapshotRepository.findByTeamSeasonAndStat(teamId, seasonId, statName)` |
| League distribution + **field size** per stat | `SeasonPopulationStatRepository.findLeagueWideBySeasonAndDate(seasonId, date)` → `statName`, `popMean/Stddev/Min/Max`, `teamCount` |
| Stat direction (`higherIsBetter`) | `BoxScoreStatCalculator.statMetas()` |

`rank` on each row is **direction-aware** (rank 1 = best, regardless of whether
high or low is good), so percentile derives directly from rank + field size and
needs no special-casing per stat.

## 3. Stat display catalog (new)

The calculator registry has only `name` + `higherIsBetter`. Presentation metadata
(label, category, number format) does not belong in the calc layer, so add a
small catalog: `controller/TeamStatDisplay.java` — an `enum` keyed by stat name.

```
enum TeamStatDisplay {
  // name                label                 category            format
  PACE("pace",          "Pace",               EFFICIENCY,         DECIMAL_1),
  OFF_EFF("off_efficiency","Offensive Rtg",    EFFICIENCY,         DECIMAL_1),
  DEF_EFF("def_efficiency","Defensive Rtg",    EFFICIENCY,         DECIMAL_1),
  EFG("efg_pct",        "eFG%",               FOUR_FACTORS_OFF,   PERCENT_1),
  TOV("tov_rate",       "Turnover Rate",      FOUR_FACTORS_OFF,   PERCENT_1),
  ORB("orb_pct",        "Off. Reb%",          FOUR_FACTORS_OFF,   PERCENT_1),
  FTR("ft_rate",        "FT Rate",            FOUR_FACTORS_OFF,   PERCENT_1),
  OPP_EFG("opp_efg_pct","Opp eFG%",           FOUR_FACTORS_DEF,   PERCENT_1),
  OPP_TOV("opp_tov_rate","Opp Turnover Rate", FOUR_FACTORS_DEF,   PERCENT_1),
  DRB("drb_pct",        "Def. Reb%",          FOUR_FACTORS_DEF,   PERCENT_1),
  OPP_FTR("opp_ft_rate","Opp FT Rate",        FOUR_FACTORS_DEF,   PERCENT_1),
  TS("ts_pct",          "True Shooting%",     SHOOTING,           PERCENT_1),
  FG("fg_pct",          "FG%",                SHOOTING,           PERCENT_1),
  FG3("fg3_pct",        "3PT%",               SHOOTING,           PERCENT_1),
  FT("ft_pct",          "FT%",                SHOOTING,           PERCENT_1),
  FG3R("fg3_rate",      "3PT Rate",           SHOOTING,           PERCENT_1),
  TRB("trb_pct",        "Total Reb%",         REBOUNDING,         PERCENT_1),
  AST_TO("ast_to_ratio","Assist/TO",          PLAYMAKING,         DECIMAL_2),
  AST_FG("assisted_fg_pct","Assisted FG%",    PLAYMAKING,         PERCENT_1),
  STL("stl_rate",       "Steal Rate",         DEFENSE,            PERCENT_1),
  BLK("blk_pct",        "Block%",             DEFENSE,            PERCENT_1),
  PF("pf_per_game",     "Fouls / Game",       DEFENSE,            DECIMAL_1);
}
```

Categories render in this order with these headers:

| Enum | Header |
|---|---|
| `EFFICIENCY` | Efficiency |
| `FOUR_FACTORS_OFF` | Four Factors — Offense |
| `FOUR_FACTORS_DEF` | Four Factors — Defense |
| `SHOOTING` | Shooting |
| `REBOUNDING` | Rebounding |
| `PLAYMAKING` | Playmaking |
| `DEFENSE` | Defense |

Formats:
- `PERCENT_1` → `value * 100`, one decimal, `%` suffix (e.g. `53.2%`).
- `DECIMAL_1` → one decimal (e.g. `108.4`, `16.3`).
- `DECIMAL_2` → two decimals (e.g. `1.45`).

A stat present in `team_stat_snapshots` but **not** in the catalog is rendered
under a trailing "Other" group with `RAW` formatting (defensive default so a new
calc stat is never silently dropped). A catalogued stat with no snapshot row for
the team (e.g. a stat that produced no value) is simply omitted, not shown as `—`.

## 4. Controller changes (`TeamWebController`)

Inject `TeamStatSnapshotRepository` and `SeasonPopulationStatRepository`.

In `teamDetail(...)`, after resolving `currentSeason`, build the panel for the
current season and add it to the model. Add a sibling endpoint so the season tabs
can swap the panel for historical seasons (mirrors `teamSeasonSchedule`).

```
private TeamStatPanel buildStatPanel(Long teamId, Season season) {
    LocalDate date = teamStatSnapshotRepo.findLatestSnapshotDate(season.getId()).orElse(null);
    if (date == null) return null;                         // no calc data yet
    var rows = teamStatSnapshotRepo.findByTeamSeasonAndDate(teamId, season.getId(), date);
    if (rows.isEmpty()) return null;
    Map<String,SeasonPopulationStat> pop = popStatRepo
        .findLeagueWideBySeasonAndDate(season.getId(), date).stream()
        .collect(toMap(SeasonPopulationStat::getStatName, identity()));
    // group rows by category (catalog order), map each to StatRow
    ...
}
```

New view records (static, on the controller):

```
record TeamStatPanel(int year, LocalDate asOfDate, int gamesPlayed,
                     List<StatGroup> groups) {}

record StatGroup(String header, List<StatRow> rows) {}

record StatRow(
    String statName,        // raw key, drives the trajectory fetch
    String label,           // "True Shooting%"
    String formattedValue,  // "53.2%"
    Integer rank,           // 42
    Integer fieldSize,      // 363  (pop.teamCount)
    Integer percentile,     // 0–100, direction-aware, from rank+fieldSize
    Double zscore,          // league z (for tooltip / color intensity)
    Double confZscore,      // conference z (nullable; tooltip only)
    boolean higherIsBetter
) {
    String rankDisplay() { return rank == null ? "—" : "#" + rank + " of " + fieldSize; }
}
```

Percentile (direction-aware because `rank` already is):
`percentile = round(100.0 * (fieldSize - rank) / (fieldSize - 1))`, clamped
`[0,100]`; when `fieldSize <= 1`, percentile is `null` (bar hidden).

`gamesPlayed` for the panel header = `gamesPlayed` from any row (all equal for a
team on a date).

Model attributes: `statPanel` (the `TeamStatPanel`, may be `null`).

New fragment endpoint for season-tab swaps:

```
@GetMapping("/teams/{id}/season/{year}/stats-panel")
public String teamSeasonStatPanel(@PathVariable Long id, @PathVariable Integer year, Model model) {
    Season s = seasonRepository.findByYear(year).orElseThrow(...);
    model.addAttribute("statPanel", buildStatPanel(id, s));
    return "fragments/team-stat-panel :: panel";
}
```

## 5. New trajectory API (`StatisticsController`)

The percentile bars are server-rendered; only the trajectory chart needs JSON.

```
GET /api/statistics/team/{teamId}/season/{year}/stat/{statName}
 -> [ { snapshotDate, value, rank, zscore }, ... ]   // ordered by date
```

Backed by `findByTeamSeasonAndStat`. Returns `[]` for unknown season/stat.
DTO: `record StatPointDto(LocalDate snapshotDate, double value, Integer rank, Double zscore)`.

## 6. Template

Extract the panel into `fragments/team-stat-panel.html` (`th:fragment="panel"`)
so the controller can return it standalone for tab swaps. In `team-detail.html`,
replace the old `analytics-section` block with:

```html
<section class="team-stats" th:if="${statPanel != null}"
         th:data-team-id="${teamId}">
  <div class="team-stats__header">
    <h2 class="section-title">Team Profile</h2>
    <div class="analytics-season-tabs">
      <span th:each="season : ${seasons}" class="analytics-tab"
            th:classappend="${season.year == currentSeasonYear} ? 'analytics-tab--active'"
            th:data-year="${season.year}" th:text="${season.year}">2026</span>
    </div>
  </div>
  <p class="team-stats__asof"
     th:text="'Through ' + ${statPanel.gamesPlayed} + ' games · as of ' + ${statPanel.asOfDate}">…</p>

  <div id="team-stats-panel">
    <div th:replace="~{fragments/team-stat-panel :: panel}"></div>
  </div>

  <!-- Trajectory -->
  <div class="team-stats__trajectory">
    <div class="team-stats__trajectory-head">
      <h3 class="analytics-chart-card__title">Trajectory</h3>
      <select id="trajectory-stat" class="form-input form-input--sm"><!-- options from groups --></select>
    </div>
    <div class="analytics-chart-wrap"><canvas id="chart-stat-trajectory"></canvas></div>
  </div>

  <p class="analytics-link">
    <a th:href="@{'/seasons/' + ${currentSeasonYear} + '/stats'}" class="link">
      View full league rankings &rarr;</a>
  </p>
</section>
```

`fragments/team-stat-panel.html` (the swappable part):

```html
<div th:fragment="panel">
  <div class="stat-group" th:each="group : ${statPanel.groups}">
    <h3 class="stat-group__header" th:text="${group.header}">Shooting</h3>
    <div class="stat-grid">
      <div class="stat-grid__row" th:each="row : ${group.rows}" th:data-stat="${row.statName}">
        <span class="stat-grid__label" th:text="${row.label}">True Shooting%</span>
        <span class="stat-grid__value" th:text="${row.formattedValue}">53.2%</span>
        <span class="stat-grid__rank" th:text="${row.rankDisplay()}">#42 of 363</span>
        <div class="stat-bar" th:if="${row.percentile != null}"
             th:title="'z = ' + ${#numbers.formatDecimal(row.zscore,1,2)}">
          <div class="stat-bar__fill"
               th:classappend="${row.percentile >= 50} ? 'stat-bar__fill--good' : 'stat-bar__fill--bad'"
               th:style="'width:' + ${row.percentile} + '%'"></div>
        </div>
      </div>
    </div>
  </div>
</div>
```

## 7. CSS (`static/css`, alongside existing `.analytics-*`)

- `.stat-group__header` — small uppercase muted label, top border separator.
- `.stat-grid__row` — CSS grid: `label | value | rank | bar`
  (`grid-template-columns: 1fr auto 5.5rem 6rem`), collapse to label/value/bar on
  narrow screens.
- `.stat-bar` — full-width track, `height ~6px`, rounded; `.stat-bar__fill` width
  = percentile %. `--good` green (`#22c55e`), `--bad` red (`#ef4444`); reuse the
  palette already in the team-page `<script>`.
- Reuse `.analytics-tab`, `.analytics-chart-wrap`, `.analytics-link`,
  `.section-title`, `.form-input--sm` as-is.

## 8. JS (replaces the lines 118–232 block)

Single IIFE:
1. Populate `#trajectory-stat` options from the rendered `.stat-grid__row[data-stat]`
   (label + key), default to `ts_pct` if present else first.
2. `loadTrajectory(statName)` → fetch
   `/api/statistics/team/{teamId}/season/{year}/stat/{statName}`, draw a Chart.js
   line of `value` vs `snapshotDate`; tooltip shows formatted value + `#rank`.
   Destroy the prior chart instance before redraw (same pattern as current code).
3. Season-tab `.analytics-tab` click:
   - swap the panel via HTMX-style fetch of `/teams/{id}/season/{year}/stats-panel`
     into `#team-stats-panel` (or use `hx-get` on the tabs and drop the manual fetch),
   - update `currentYear`, repopulate the dropdown, reload the trajectory.

Keep the existing prediction-enrichment IIFE (lines 233–296) untouched — it is
schedule logic, unrelated to this section.

## 9. Edge cases

- **No calc data** (`statPanel == null`): render nothing for the section (the
  schedule still shows). Matches the league page's "run a Time Series calc" gap.
- **A catalogued stat with no snapshot row** for the team: omit that row; if a
  whole group empties, omit the group header too (filter empty groups in the
  controller).
- **`fieldSize <= 1`** (tiny early-season field): percentile/bar hidden, value +
  rank still shown.
- **Historical season with data but team didn't play**: `findByTeamSeasonAndDate`
  empty → `null` panel for that tab.

## 10. Tests

- `TeamWebControllerTest` (integration, Testcontainers): seed a season, teams,
  final games + box scores, run `TeamStatTimeSeriesService.calculateAndStoreForSeason`,
  GET `/teams/{id}` → assert model `statPanel` groups/rows, a known
  `formattedValue`, and `rankDisplay`.
- `teamSeasonStatPanel` fragment endpoint returns populated panel for a prior year.
- `StatisticsControllerTest`: trajectory endpoint returns ordered points for a
  seeded stat; `[]` for unknown stat / unknown season.
- `TeamStatDisplayTest` (pure unit): every name in
  `BoxScoreStatCalculator.statMetas()` has a catalog entry (guards against a new
  calc stat with no label), and each format renders a sample value as expected.

## 11. Out of scope

- Conference-relative ranking column (we have `confZscore` but no stored conf
  rank). Surfaced only as a tooltip for now.
- Editing/recalc controls (admin already owns that).
- Leaderboard drill-down from a stat row (future: link to
  `/seasons/{year}/stats` filtered by stat).
