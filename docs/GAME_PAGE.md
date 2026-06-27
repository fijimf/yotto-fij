# Game Page Specification

## Motivation

The game detail page (`/games/{id}`) is one of the site's most important surfaces. It presents a rich, data-driven view of a single game that adapts based on game status: upcoming games show predictions, betting lines, and historical context; completed games add final scores, actual results, and post-game analysis.

---

## Page Layout

### Desktop (≥1024px)

```
┌─────────────────────────────────────────────────────────┐
│                    Game Header                          │
├─────────────────────────────────────────────────────────┤
│ Betting Info          │ Predictions                     │
├───────────────────────┴─────────────────────────────────┤
│ Head-to-Head Record   │ Last Five Meetings              │
├───────────────────────┴─────────────────────────────────┤
│ Team Comparison Table                                   │
├─────────────────────────────────────────────────────────┤
│ Game Analysis Chart                                     │
└─────────────────────────────────────────────────────────┘
```

### Tablet (768px–1023px)

Hybrid layout: Betting Info and Predictions stack vertically; Head-to-Head and Last Five Meetings stack vertically; other sections remain full-width.

### Mobile (<768px)

All sections stack vertically. Betting Info and Predictions collapse into accordion cards. The comparison table becomes horizontally scrollable. The chart uses a 4:3 aspect ratio at full width.

---

## UI Components

### Game Header

Displayed at the top of the page in all states.

**Completed game:**
```
[Home Logo]  {Home Team Name}   {Home Score}
                    FINAL
[Away Logo]  {Away Team Name}   {Away Score}

{Conference Name} · {Venue} · {Date}
```

**Upcoming game:**
```
[Home Logo]  {Home Team Name}
                    vs
[Away Logo]  {Away Team Name}

{Conference Name} · {Venue} · {Date}
```

**Rules:**
- **Date format:** Full month name — "January 21, 2023"
- **Conference name:** Show only if both teams are in the same conference (conference game). Leave blank for non-conference matchups.
- **Venue:** Always show from `Game.venue`. If `neutralSite = true`, append "(Neutral)".

---

### Betting Information

Displays closing betting market lines when available.

**Visibility:** Show only if at least one betting value is non-null. Hide entirely (no empty state) if all values are null.

**Layout:** Two-column grid on desktop, stacked on mobile.

**Sign convention:** A negative spread means the home team is favored (e.g., `spread = -1.5` → home team favored by 1.5 points).

> **Implementation note:** The `PredictionResult.bookSpread` Javadoc incorrectly states "positive = home favored." The database stores negative = home favored. This Javadoc must be corrected before implementation to avoid a display bug.

**Fields:**

| Label | Source field | Format | Example |
|-------|-------------|--------|---------|
| Spread | `BettingOdds.spread` | `{favored team abbr} {spread}` | `OKST -1.5` |
| O/U | `BettingOdds.overUnder` | One decimal place | `126.5` |
| Money | `BettingOdds.homeMoneyline` / `awayMoneyline` | `{homeAbbr} {homeML} / {awayAbbr} {awayML}` | `OKST -120 / ISU +100` |

**Spread display logic:**
- If `spread < 0`: home team is favored → display `{homeAbbr} {spread}` (e.g., `OKST -1.5`)
- If `spread > 0`: away team is favored → display `{awayAbbr} -{spread}` (e.g., `ISU -2.5`)
- If `spread = 0` or `null`: show "Pick'em" or hide the row

**Moneyline:** If either moneyline is null, show "N/A" for that team. Always show sign (+ or −).

---

### Predictions

Displays DeepFij's statistical model predictions. Shown for both upcoming and completed games. For completed games, show the pre-game prediction alongside the actual result so users can evaluate prediction accuracy.

**Visibility:** Hide entirely if all model results are null (e.g., game is POSTPONED/CANCELLED or no snapshots exist).

**Models and labels:**

| Model | Label | Output |
|-------|-------|--------|
| Massey | DeepFij Spread | Predicted point spread (home team perspective) |
| Massey Total | DeepFij Total | Predicted combined score |
| Bradley-Terry | DeepFij Win Probability | Win probability for each team + implied moneylines |
| Bradley-Terry Weighted | DeepFij Win Probability (Weighted) | Same as BT with recency weighting |
| ML (gradient-boosted) | DeepFij ML `{modelVersion}` | Spread, total, and win probabilities |

Show the ML model only when it is enabled and `featuresComplete = true`. Always show the model version string for the ML row.

**Spread/total format:** `{favoredAbbr} -{value}` (same convention as betting lines). One decimal place.

**Win probability format:** `{homeAbbr} {pHome}% / {awayAbbr} {pAway}%` with implied moneylines below.

**Comparison with betting lines** (when both exist):2
|----------|-------|
| Size (desktop) | 800×800px, responsive to container width |
| Aspect ratio (mobile) | 4:3 below 600px width |
| Padding | 60px top, 80px right, 80px bottom, 80px left |
| Background | White or transparent (page theme) |
| Library | D3.js |

### Axes

Both axes share the same scale: **40–120 points**.

- **Y-axis (vertical):** Home team's score
- **X-axis (horizontal):** Away team's score
- **Grid:** Light gray lines every 10 points (`#e2e8f0`, 1px, 50% opacity)
- **Ticks:** Major every 10 points, minor every 5 points
- **Axis labels:** `[32px team logo] {Full Team Name} ({Abbreviation})`, 16px bold
  - Y-axis label: left side
  - X-axis label: bottom center

### The Key Design Insight

The dual-axis layout is the conceptual heart of the chart. Because home team score = away team's "points against" and away team score = home team's "points against," both teams' offensive and defensive distributions can be plotted in the same 2D space without duplication:

- A point at coordinate (x, y) simultaneously represents: "the away team scored x" and "the home team scored y"
- The home team's Points For distribution runs along the Y-axis; its Points Against distribution runs along the X-axis
- The away team's Points For distribution runs along the X-axis (opposite margin); its Points Against runs along the Y-axis (opposite margin)

The marginal distributions on opposing sides of each axis make this dual interpretation visual and immediate.

### Color Scheme

- **Team-specific elements:** Use each team's `color` field from the database
- **Neutral elements** (betting lines, implied score): `#64748b` (slate-500)
- **Color override toggle:** Swap to standard colors for teams with similar palettes
  - Home standard: `#3b82f6` (blue-500)
  - Away standard: `#ef4444` (red-500)

### Toggles and Interactivity

- Each chart element has a show/hide toggle in a legend panel (positioned to the right of the chart on desktop; stacks below on mobile)
- **Default:** All elements visible, including Season Game Markers
- **Hover tooltips:** Show for all elements with defined hover text
- **Click:** Navigate to game page where specified
- **Transitions:** 300ms D3 transitions for show/hide

---

### Chart Elements

All elements share the same coordinate space: X = away team score, Y = home team score.

---

#### 1. Result Marker

The actual final score displayed as a prominent foreground marker.

| Property | Value |
|----------|-------|
| Shape | Large star or diamond, 12px |
| Style | Black stroke (2px), neutral fill |
| Condition | Game status is FINAL and both scores exist |
| Hover | `{homeAbbr}: {homeScore}, {awayAbbr}: {awayScore}` |
| Click | None |
| Z-order | 100 (front) |

---

#### 2. Point Spread Line

A reference line through all score combinations consistent with the betting spread.

| Property | Value |
|----------|-------|
| Style | Dashed, 2px, `#64748b`, dash pattern `[5,5]` |
| Condition | `BettingOdds.spread` is non-null |
| Equation | `awayScore − homeScore = spread` |
| Label | Spread value at midpoint of visible segment, 12px, white background pill |
| Hover | None |
| Z-order | 10 (background) |

**Sign convention:** `spread = -1.5` means the home team is favored by 1.5 points. The line passes through points like (60, 61.5), (80, 81.5), etc.

---

#### 3. Over-Under Line

A reference line through all score combinations that sum to the over-under total.

| Property | Value |
|----------|-------|
| Style | Dashed, 2px, `#64748b`, dash pattern `[5,5,2,5]` (distinct from spread line) |
| Condition | `BettingOdds.overUnder` is non-null |
| Equation | `awayScore + homeScore = overUnder` (slope = −1) |
| Label | `O/U {value}` at midpoint, 12px, white background pill |
| Hover | None |
| Z-order | 10 (background) |

---

#### 4. Implied Score Marker

The intersection of the spread and over-under lines — the market's implied final score.

| Property | Value |
|----------|-------|
| Shape | Circle, 8px radius |
| Style | Neutral fill, 2px darker stroke |
| Condition | Both `spread` and `overUnder` are non-null |
| Calculation | `home = (total − spread) / 2`, `away = (total + spread) / 2` |
| Example | spread=−1.5, total=126.5 → home=64.0, away=62.5 |
| Hover | `Implied Score: {homeAbbr} {home:.1f}, {awayAbbr} {away:.1f}` |
| Z-order | 50 (mid) |

---

#### 5. Season Game Markers

One marker per completed game in each team's season (prior to the current game date), plotted in the team's scoring context.

| Property | Value |
|----------|-------|
| Shape | Circle, 4px radius |
| Style | Win: team color fill (50% opacity) + stroke (100%, 1.5px); Loss: hollow (stroke only, 1.5px) |
| Condition | Toggle-controlled; **visible by default** |
| Position (home team game) | X = opponentScore, Y = teamScore |
| Position (away team game) | X = teamScore, Y = opponentScore |
| Hover | `{date}: {teamAbbr} {teamScore}, {opponentAbbr} {opponentScore}` |
| Click | Navigate to `/games/{id}` |
| Z-order | 30 |

**Data:** A list of completed FINAL games for each team in the current season, strictly before the current game's date (exclude current game and any future games). Each entry: `{id, date, teamScore, opponentScore, opponentAbbreviation, isWin}`.

**Source:** Queried from the `Game` table and embedded in the page template as inline JSON by `GameDetailController`.

---

#### 6. Average Score Marker

The team's season average points scored vs. points allowed — the center of their scoring distribution.

| Property | Value |
|----------|-------|
| Shape | Square, 10px |
| Style | Team color, 70% fill opacity, 2px stroke at 100% |
| Position (home) | X = pointsAgainstAvg, Y = pointsForAvg |
| Position (away) | X = pointsForAvg, Y = pointsAgainstAvg |
| Hover | `Avg Score: {abbr} {pointsForAvg:.1f} – {pointsAgainstAvg:.1f}` |
| Z-order | 60 |

**Data source:** Computed from `SeasonStatistics.calcPointsFor / (calcWins + calcLosses)` and `calcPointsAgainst / (calcWins + calcLosses)`.

---

#### 7. Interquartile Box

A rectangle spanning Q1–Q3 of each team's scoring range, showing where the middle 50% of their games fall.

| Property | Value |
|----------|-------|
| Shape | Rectangle, transparent fill |
| Style | Team color, 40% opacity stroke, 2px |
| Position (home) | X: [pointsAgainstQ1, pointsAgainstQ3], Y: [pointsForQ1, pointsForQ3] |
| Position (away) | X: [pointsForQ1, pointsForQ3], Y: [pointsAgainstQ1, pointsAgainstQ3] |
| Hover | None |
| Z-order | 20 |

**Coverage:** The IQR box covers 50% of the team's game scores (Q1 to Q3 by definition).

**Data source:** Computed on-the-fly from the team's season games in the `Game` table (same set as Season Game Markers — FINAL games in current season before the game date). Q1 and Q3 are not stored; they are calculated per request.

---

#### 8. 95% Confidence Ellipse

A bivariate normal confidence ellipse representing the joint distribution of the team's points scored and allowed.

| Property | Value |
|----------|-------|
| Shape | Ellipse outline (no fill), ≥100 points |
| Style | Team color, 50% opacity stroke, 2.5px |
| Condition | All of `stddevPtsFor`, `stddevPtsAgainst`, `correlationPts` are non-null |
| Position (home) | X = pointsAgainst dimension, Y = pointsFor dimension |
| Position (away) | X = pointsFor dimension, Y = pointsAgainst dimension |
| Hover | None |
| Z-order | 25 |

If any required statistic is null, hide the ellipse entirely (do not degrade to axis-aligned).

Reference image: `docs/bivariate_ellipses.png`

**Mathematical specification:**

```
Inputs (from TeamSeasonStatSnapshot, most recent snapshot before game date):
  μ_for     = meanPtsFor
  μ_against = meanPtsAgainst
  σ_for     = stddevPtsFor
  σ_against = stddevPtsAgainst
  cov       = correlationPts × σ_for × σ_against

Covariance matrix:
  Σ = [ σ_for²    cov     ]
      [ cov    σ_against² ]

Ellipse boundary (95% confidence, χ² = 5.991 for 2 DOF):
  (x − μ)ᵀ Σ⁻¹ (x − μ) = 5.991

Construction:
  1. Compute eigenvalues λ₁, λ₂ and eigenvectors v₁, v₂ of Σ
  2. Semi-axes: a = √(5.991 × λ₁),  b = √(5.991 × λ₂)
  3. Rotation angle: θ = atan2(v₁[1], v₁[0])
  4. Parametric trace: t ∈ [0, 2π], ≥100 points
```

---

#### 9. Score Rug

A rug of short tick marks in the chart margins — one tick per completed game, projected onto the relevant axis. This is the empirical, axis-anchored expression of the dual-axis design: each team's scoring spread appears as ticks along the axis it shares with the opponent. Overlapping ticks accumulate opacity, so denser scoring regions read darker.

Replaces the earlier normal-density "marginal distributions," which were redundant with the 95% ellipse (their 1D projection), implied false precision from a fitted Gaussian on ~15–30 games, and were prone to extending past the viewbox when μ ± 3σ left the 40–120 range. Ticks use the actual game scores instead, are always flush to the axis, and never overflow.

| Property | Value |
|----------|-------|
| Shape | Short line, ~11px long, 2.25px, round cap, 2px gap from the axis |
| Style | Team color, 45% stroke opacity (overlap builds density) |
| Label | Small (9px) team-color caption per rug, e.g. "LOU scored" / "USF allowed", parallel to its axis |
| Data source | Each team's season game list (same set as Season Game Markers / IQR box) |
| Domain | Only scores within 40–120 are drawn (others skipped, never clipped/overflowed) |
| Hover | None |
| Z-order | 5 (background, in margin space) |

**Rendering positions:**

| Ticks | Side | Value per game |
|---|---|---|
| Home team — Points For | Left margin (Y-axis), extends left | home team's score |
| Away team — Points Against | Right margin (Y-axis), extends right | away team's opponent's score |
| Home team — Points Against | Bottom margin (X-axis), extends down | home team's opponent's score |
| Away team — Points For | Top margin (X-axis), extends up | away team's score |

Both teams' "Points For" ticks sit on opposite margins of the X-axis; both "Points Against" ticks on opposite margins of the Y-axis. This is intentional: the X-axis simultaneously represents the away team's offense and the home team's defense, and the contrast between the top and bottom rugs makes that duality legible.

Each rug carries a small caption in its team's color, reading "{abbr} scored" on the two Points-For rugs and "{abbr} allowed" on the two Points-Against rugs, set parallel to its axis. The captions sit in the clear band just beyond the axis tick numbers. The two away-side captions (top, right margins) are centered on their axis; the two home-side captions (left, bottom margins) are nudged off-center along the axis so they clear the centered rotated team-name titles that already occupy those edges.

---

## Data Requirements Summary

All required data and its implementation approach:

| Data | Used By | Implementation |
|------|---------|----------------|
| Head-to-head record | Head-to-Head section | New `GameRepository` query: count FINAL games between team A and team B (all-time, excluding current game) |
| Last 5 meetings | Last Five Meetings | Same query, ordered by date desc, limit 5 |
| Neutral site record | Comparison table | `GameRepository` query: FINAL neutral-site games for each team in current season |
| Last 5 games record | Comparison table | `GameRepository` query: last 5 FINAL games before game date per team |
| Points per game averages | Comparison table, Chart | Derived: `calcPointsFor / (calcWins + calcLosses)` from `SeasonStatistics` |
| Rating values (Massey, BT, BT-W) | Comparison table | `TeamPowerRatingSnapshot`, most recent snapshot before game date |
| Rating ranks | Comparison table | Sort all team snapshots for the same model type and date; derive ordinal rank |
| RPI value | Comparison table | `TeamSeasonStatSnapshot.rpi`, most recent snapshot before game date |
| RPI rank | Comparison table | Sort all team RPI snapshots for the same date; derive ordinal rank |
| Season game list per team | Season Game Markers, IQR Box | `GameRepository` query: FINAL games for each team in current season, before game date; embedded in template as JSON |
| IQR statistics (Q1, Q3) | IQR Box | Computed from season game list above (no stored field) |
| Std dev (points for/against) | Confidence Ellipse, Marginals | `TeamSeasonStatSnapshot.stddevPtsFor`, `stddevPtsAgainst` |
| Correlation | Confidence Ellipse | `TeamSeasonStatSnapshot.correlationPts`; covariance derived as `corr × σ_for × σ_against` |
| Distribution means | Confidence Ellipse, Marginals | `TeamSeasonStatSnapshot.meanPtsFor`, `meanPtsAgainst` |

---

## Loading and Error States

- **Loading:** Show a spinner with "Loading game analysis…" while chart initializes
- **Error:** Show an error message with a retry button if data is unavailable
- **Partial data:** If a section's data is missing (e.g., no betting lines), hide that section silently; if a chart element's statistics are incomplete, hide that element and show a warning icon in its legend toggle
