# RPI — Rating Percentage Index

## Definition

RPI is a composite ranking statistic used in NCAA basketball (1981–2018). It combines a
team's own winning percentage with two layers of strength-of-schedule to produce a single
comparable number.

```
RPI = 0.25 × WP  +  0.50 × OWP  +  0.25 × OOWP
```

| Component | Weight | Description |
|---|---|---|
| **WP** | 25% | Team's own winning percentage (location-adjusted) |
| **OWP** | 50% | Average winning percentage of the team's opponents (excluding games vs. the team) |
| **OOWP** | 25% | Average OWP of the team's opponents |

---

## Component Definitions

### WP — Winning Percentage (location-adjusted)

The NCAA applies a multiplier to each game based on location and result, to account for
home-court advantage (home teams win roughly 2/3 of games in D-I):

| Situation | Multiplier |
|---|---|
| Home win | 0.6 |
| Road win | 1.4 |
| Neutral win | 1.0 |
| Home loss | 1.4 |
| Road loss | 0.6 |
| Neutral loss | 1.0 |

```
WP = Σ(multiplier for each win) / Σ(multiplier for every game)
```

The location adjustment applies **only to WP** — OWP and OOWP use unweighted win counts.

### OWP — Opponents' Winning Percentage

For each opponent O that team T has faced:
1. Take all of O's games, **excluding** any games O played against T.
2. Compute O's raw (unadjusted) winning percentage from those games.

Then average those values across all distinct opponents of T.

```
OWP(T) = mean over all opponents O of [ wins(O, excluding vs T) / games(O, excluding vs T) ]
```

Note: this is **not** the combined W–L record of all opponents; each opponent contributes
equally regardless of how many times T played them (though in practice most opponents
appear only once).

### OOWP — Opponents' Opponents' Winning Percentage

For each opponent O that team T has faced, compute OWP(O) using the standard definition
above (i.e., O's opponents' WP excluding O's games against each of those opponents).
Then average across all of T's opponents.

```
OOWP(T) = mean over all opponents O of [ OWP(O) ]
```

---

## Time-Series Calculation

The goal is to compute RPI for every team for every game date in the season — one
snapshot per (team, season, date) — so the progression can be tracked and charted.

### Algorithm (per date D)

1. **Load games.** Collect all FINAL games with `gameDate.toLocalDate() ≤ D` for the
   season. Only these games exist "as of" date D.

2. **Build the game graph.** For each team, build a list of played-game records:
   `(opponentId, ptsFor, ptsAgainst, isHome, isNeutral, isWin)`.

3. **Compute WP for all teams.** Using the location-adjusted formula above.

4. **Compute OWP for each team T.**
   - For each distinct opponent O of T:
     - Filter O's games to exclude those against T.
     - Compute O's raw WP from the filtered list.
   - Average the results.

5. **Compute OOWP for each team T.**
   - For each distinct opponent O of T, compute OWP(O) (standard, excluding O's games
     against each of O's own opponents in turn).
   - Average the results.

6. **Compute RPI.** `0.25 × WP + 0.50 × OWP + 0.25 × OOWP`

Steps 4 and 5 share most of the work; OWP(O) in step 5 is the same function used in
step 4, so it can be cached once per date.

### Complexity

For ~360 teams each playing ~30 games cumulative at mid-season:
- Step 3: O(N) — trivial
- Step 4: O(N × G) where G = avg games per team ≈ 10,800 ops/date
- Step 5: O(N × G²) ≈ 324,000 ops/date
- Across ~90 dates: ~30M ops total — well within a single background thread

---

## Storage

Add four new columns to `team_season_stat_snapshots` (new Flyway migration):

| Column | Type | Description |
|---|---|---|
| `rpi` | DOUBLE PRECISION | Final RPI value (0–1) |
| `rpi_wp` | DOUBLE PRECISION | WP component (location-adjusted) |
| `rpi_owp` | DOUBLE PRECISION | OWP component |
| `rpi_oowp` | DOUBLE PRECISION | OOWP component |

Storing the three sub-components alongside RPI allows debugging, auditing, and
future display of strength-of-schedule on its own.

---

## Implementation Plan

### 1. Flyway Migration — `V8__rpi_columns.sql`
Add the four columns to `team_season_stat_snapshots`.

### 2. Entity — `TeamSeasonStatSnapshot.java`
Add `rpi`, `rpiWp`, `rpiOwp`, `rpiOowp` fields (Double) with getters/setters.

### 3. Calculation — `StatisticsTimeSeriesService.java`
Add a private `computeRpi(date, gamesByTeam)` method called from within the existing
per-date loop (after win/loss accumulators are updated). It should:
- Accept the map of `teamId → List<GameRecord>` already being maintained
- Return a `Map<Long teamId, RpiComponents>` (a private record: wp, owp, oowp, rpi)
- Cache intermediate OWP values keyed by team so OOWP reuses already-computed values
- Set the four fields on each `TeamSeasonStatSnapshot` before it is added to the batch

No new service or class needed — this slots cleanly into the existing per-date loop.

### 4. REST API — `StatisticsController.java`
Add `rpi`, `rpiWp`, `rpiOwp`, `rpiOowp` fields to `SnapshotDto`.

### 5. League Overview Page — `season-stats-table.html`
Add an **RPI** column to the rankings table. The table is already sorted by `winPct`;
provide a note or consider sorting by RPI instead (or making it toggleable).

### 6. Admin Integration
No new admin button required — RPI is calculated as part of the existing
`Time Series` button (and automatically after each scrape). The calculation
is deterministic and fast enough to run inline in `StatisticsTimeSeriesService`.

---

## Edge Cases

| Case | Handling |
|---|---|
| Team has played 0 games on date D | Skip — no snapshot row written for this date |
| Opponent has played 0 games excluding vs T | Exclude that opponent from OWP average |
| Only one opponent | OWP is that opponent's WP; OOWP is that opponent's OWP |
| Neutral site game | `isNeutral = true` from `game.neutralSite`; multiplier 1.0 |
| Same opponent faced multiple times | Count as one entry in the OWP average (by distinct opponentId) per NCAA convention |

---

## References

- [Rating percentage index — Wikipedia](https://en.wikipedia.org/wiki/Rating_percentage_index)
- [RPI Help — kenpom.com](https://kenpom.com/blog/rpi-help/)
- [How to Game the RPI — Squared Statistics](https://squared2020.com/2017/03/10/how-to-game-the-rating-percentage-index-rpi-in-basketball/)
