# Menu & GUI Reorganization — Detailed Specification

Status: **APPROVED 2026-08-21** — all §10 recommendations adopted (OQ-8 set aside as a
stale note; §7.5's spec'd changes stand as the matchup work list).
Implementation plan: [MENU_AND_GUI_IMPLEMENTATION_PLAN.md](MENU_AND_GUI_IMPLEMENTATION_PLAN.md)
Source: [IMPROVED_MENU_AND_GUI_TOUCHUP.md](IMPROVED_MENU_AND_GUI_TOUCHUP.md)
Companion docs: [UI.md](../UI.md), [STAT_PAGE_SPEC.md](STAT_PAGE_SPEC.md), [POWER_MODELS.md](../POWER_MODELS.md), [ADMIN_MANUAL.md](../ADMIN_MANUAL.md)

---

## 1. Purpose & scope

The site grew organically: the top nav is a flat list of ten links, several major
pages are unreachable from the nav (`/stats` glossary, `/predictions/performance`,
`/about`), some pages bundle unrelated content (the rankings page hides a
correlation matrix in a tab; each stat page carries a predictor scatter, a
distribution, and a leaderboard), and "predictions" concepts are split across
three top-level entries (Rankings, Predictions, Matchup, plus a phase-gated
Bracket).

This spec reorganizes the public site into six content sections — **Teams,
Conferences, Games, Statistics, Power Rankings, Models** — plus Home, News and
the user area, and defines every new or modified page. It also specifies the
look-and-feel consolidation needed so new and old screens read as one product.

Out of scope: admin UI, REST API shape (only additions, no breaking changes),
the home page composition (explicitly "OK for now"), and auth flows.

---

## 2. Target information architecture

### 2.1 Navigation tree

```
Home
Teams
Conferences
Games ▾
    Scores & Schedule        /games
    NCAA Bracket             /bracket            (year tabs ON the page, not in the menu)
Statistics ▾
    League Overview          /seasons/{y}/stats  (today's "Season Stats" page)
    Predictor                /stats/predictor
    Correlation              /stats/correlation
    ── categories ──
    Results                  /stats/results
    Scoring                  /stats/scoring
    Efficiency               /stats/efficiency
    Four Factors             /stats/four-factors
    Shooting                 /stats/shooting
    Rebounding               /stats/rebounding
    Playmaking               /stats/playmaking
    Defense                  /stats/defense
    Glossary                 /stats
Power Rankings ▾
    Overview (composite)     /rankings
    RPI                      /rankings/rpi
    Massey                   /rankings/massey
    Bradley-Terry            /rankings/bradley-terry
    Bradley-Terry (Wtd)      /rankings/bradley-terry-weighted
    Adjusted Efficiency      /rankings/adjusted-efficiency        [proposed addition — OQ-6]
Models ▾
    Compare Models           /models/compare     (today's /predictions/performance)
    Matchup Predictor        /models/matchup     (today's /predictions/matchup)
    ── one entry per public model, dynamic ──
    {Model display name}     /models/{slug}      (hub page with tabs: About | Schedule | Bracket | Matchup)
News
────────── right side (unchanged behavior) ──────────
Admin                        (ADMIN only)
{username} ▾  Profile / Sign out        — or —        Sign in
```

Design rule: **the nav has exactly one level of dropdown.** Anything deeper in
the source sketch (bracket years, per-model sub-pages) is handled by
tab strips / year switchers on the destination page. Nested flyout menus are
hostile on mobile and to keyboard users, and the year list and model list are
both unbounded over time.

### 2.2 What moves where

| Today | Becomes |
|---|---|
| `Season Stats` nav item → `/seasons/stats` | `Statistics ▸ League Overview` (same page, re-titled) |
| `Rankings` nav item → `/rankings` (3 tabs) | `Power Rankings ▸ Overview`; Scatter Matrix tab **removed**, superseded by `/stats/correlation`; Model View tab removed (superseded by per-model pages) |
| `Bracket` nav item (phase-gated) | `Games ▸ NCAA Bracket`, always visible (historical brackets are evergreen) — see OQ-5 |
| `Predictions` nav item → `/predictions` | Folded into per-model **Schedule** pages; `/predictions` 301s to the default model's schedule — see OQ-3 |
| `Matchup` nav item → `/predictions/matchup` | `Models ▸ Matchup Predictor` at `/models/matchup` |
| `/predictions/performance` (orphan, no nav link) | `Models ▸ Compare Models` at `/models/compare` |
| `/stats` glossary (orphan, button-only) | `Statistics ▸ Glossary` |
| Stat-detail scatter ("Game Outcomes" chart) | Its own page: `/stats/predictor/{statName}` |
| `/about` (footer only) | stays footer-only (fine) + footer gains Glossary and Compare Models links |

### 2.3 Redirects (permanent, so old bookmarks and inbound links keep working)

| Old | New |
|---|---|
| `/predictions/performance` | `/models/compare` |
| `/predictions/matchup` (+ `/result` HTMX endpoint) | `/models/matchup` (+ `/models/matchup/result`) |
| `/predictions` | `/models/{defaultSlug}/schedule` (or `/models/compare` if no servable model) |
| `/rankings/comprehensive` | `/rankings` (already exists, keep) |

All other existing URLs (`/games`, `/bracket`, `/seasons/{y}/bracket`,
`/seasons/{y}/stats`, `/stats`, `/stats/{statName}`, `/seasons/{y}/stats/{statName}`,
`/rankings`) keep their meaning.

### 2.4 URL conventions

Keep the existing convention: **unscoped URL redirects to the latest season;
`/seasons/{year}/…` is the canonical season-scoped form.** Every new page above
therefore has a `/seasons/{year}/…` twin:

- `/seasons/{y}/stats/predictor`, `/seasons/{y}/stats/predictor/{statName}`
- `/seasons/{y}/stats/correlation`
- `/seasons/{y}/stats/{categorySlug}`
- `/seasons/{y}/rankings/{modelSlug}`
- `/models/{slug}/bracket/{year}` (year is in-path since bracket is inherently per-year)

Namespace note: `/stats/{x}` must disambiguate category slugs from stat names.
Category slugs (`results`, `scoring`, …) and reserved words (`predictor`,
`correlation`) are matched first; anything else falls through to the existing
stat-name redirect. No current stat name collides (stat names are
snake_case like `efg_pct`); a unit test should pin that the reserved-slug set
and the `StatCatalog` names stay disjoint.

---

## 3. Navigation component spec

The current `fragments/nav.html` is a flat `<ul>` with one auth dropdown. It needs
a real dropdown system.

**Desktop (≥ 768px)**
- Top-level items with children render as a button with a ▾ caret (same pattern
  as the existing account dropdown — reuse/extend `.nav__dropdown`).
- Open on click (not hover-only); close on outside click, `Esc`, or opening a
  sibling. Hover-open is an optional enhancement, click must always work.
- Active section: the top-level item highlights (`.nav__link--active`) when any
  of its child pages is current. Extend the `currentPage` model attribute to a
  pair (`currentSection`, `currentPage`) supplied by each controller.
- Dropdown panels: single column; the Statistics panel may use a labelled
  divider ("Explore" / "Categories") given its length. If it still feels long,
  a two-column panel is acceptable — but no second-level flyouts.

**Mobile (< 768px)**
- Hamburger opens the existing slide-down; items with children become
  accordions (tap section name → expand children indented beneath). One section
  open at a time.

**Accessibility**
- Toggle buttons: `aria-haspopup="true"`, `aria-expanded`, and the panel gets
  `role="menu"` semantics consistent with the account dropdown. All items
  keyboard-reachable (Tab into panel; Esc closes and returns focus).

**Dynamic content**
- The Models dropdown lists public models from the `ml_models` registry (see
  §7.1 for which statuses are public). This requires a small `@ControllerAdvice`
  (like `SeasonPhaseModelAdvice`) exposing `navModels` — display name + slug,
  default model first, then alphabetical. Cache alongside the registry's
  existing caching; must not add a per-request DB query (registry is already
  in-memory).
- The News item and everything else stays static.

---

## 4. Games section

### 4.1 Scores & Schedule — `/games` (unchanged)

No changes beyond nav placement.

### 4.2 NCAA Bracket — `/bracket`, `/seasons/{year}/bracket` (modified)

The bracket page itself is good. Changes:

- **Year switcher on the page**: a tab strip (matching the team page's season
  tabs) listing every season that has ≥ 1 `NCAA_TOURNAMENT` game, newest first.
  The source sketch put years in the menu; per §2.1 they live on the page.
- **Nav visibility**: the menu entry is always present (see OQ-5). `/bracket`
  redirects to the most recent season with tournament games (current behavior
  redirects home when the phase hides the link — that fallback goes away).
- The existing `showBracketLink()` phase gate is retired for nav purposes but
  the phase logic stays for the home-page bracket panel.
- Cross-link: each bracket page links to the per-model bracket views
  ("How did the models do? → Model bracket performance", listing public models).

---

## 5. Statistics section

The unifying idea: today's stat-detail page does three jobs (predictiveness
scatter, distribution, leaderboard). This spec splits "how predictive is this
stat?" (Predictor) from "who leads this stat?" (category pages + stat detail),
and promotes correlation exploration to its own configurable page.

### 5.1 League Overview — `/seasons/{year}/stats` (light touch)

Today's "Season Stats" page (margin histogram, scored-vs-allowed scatter, big
sortable table). Changes:

- Re-title "League Overview".
- Header buttons updated: "Stats glossary" stays; "Bracket" button dropped (now
  in nav); add links to Predictor and Correlation.
- Otherwise unchanged (explicitly "seems OK").

### 5.2 Stat category pages — `/stats/{categorySlug}` (NEW)

One page per category. Categories and membership:

| Slug | Title | Stats |
|---|---|---|
| `results` | Results | WP (win %), OWP (opponents' win %), OOWP (opponents' opponents' win %) — the RPI components |
| `scoring` | Scoring | PPG, Opp PPG, Scoring margin, Scoring volatility (σ of margin) |
| `efficiency` | Efficiency | Pace, Offensive efficiency, Defensive efficiency |
| `four-factors` | Four Factors | eFG%, Opp eFG%, TO rate, Opp TO rate, ORB%, DRB%, FT rate, Opp FT rate |
| `shooting` | Shooting | TS%, FG%, 3P%, FT%, 3-point rate |
| `rebounding` | Rebounding | TRB%, RPG, ORPG, DRPG |
| `playmaking` | Playmaking | APG, AST/TO, Assisted FG% |
| `defense` | Defense | Steal rate, Block %, Fouls per game |

The last six columns map 1:1 onto the existing `BoxScoreStatCalculator`
registry / `StatCatalog` categories. **Results and Scoring are new as
first-class stats** — today they exist only as wide columns on
`TeamSeasonStatSnapshot` (see §8.1 for the data-side implication).

**Page layout** (identical template for all categories):
- Standard page header (§9.2): category title, one-paragraph description,
  season selector, as-of date picker (HTMX, like existing stat pages).
- A responsive card grid, one card per stat in the category:
  - Stat title + direction chip ("higher is better" / "lower is better")
  - Top-10 leaderboard (rank, team logo+name, value) as of the selected date
  - Footer links: "Full rankings →" (stat detail, §5.3) and "Predictiveness →"
    (predictor detail, §5.4)
- Empty state matching the existing pattern ("No snapshot data for this
  date…").

**Data**: `TeamStatSnapshotRepository.findBySeasonStatAndDate` per stat (one
query per card is fine at 3–8 cards; a batched `findBySeasonStatsInAndDate`
is a nice-to-have).

### 5.3 Stat detail — `/seasons/{year}/stats/{statName}` (modified)

Loses the "Game Outcomes" scatter (moves to §5.4). Keeps:

- Header: title, description, mechanics, direction, league avg, as-of date,
  season selector — unchanged.
- **Breadcrumb changes**: "← All stats" becomes "← {Category}" linking to the
  category page.
- Team Distribution histogram + KDE + population summary — unchanged.
- Team Rankings table (value, z, percentile, GP) with date picker and find-team
  filter — unchanged.
- New compact cross-link card where the scatter used to be: "How well does
  {stat} predict winners? AUC 0.XX → See the Predictor page" (uses the cached
  predictiveness figure; text-only, no chart).

### 5.4 Predictor — `/stats/predictor` (NEW) and `/stats/predictor/{statName}` (NEW)

This is the source sketch's "give the scatter plot … its own page, with the
summary of its usefulness."

**Index page** `/seasons/{year}/stats/predictor`:
- Intro block (2–3 sentences): what the page measures — for each stat, every
  completed game is scored by which team entered with the better value; AUC
  and "better-team-wins %" summarize how predictive the stat is on its own.
  Include the caveat that single stats are weak predictors and link to
  `/models` for the real models.
- **Ranking table of all stats**, sorted by AUC descending: Stat (link to
  detail), Category, AUC (with a small horizontal bar, 0.5 anchored), "Better
  team wins", Games n. Stats below the existing `AUC_SHOW_THRESHOLD` (0.55)
  render de-emphasized with the existing "weak standalone predictor" label
  rather than being hidden.
- Season selector; computed as-of the latest snapshot date (no date picker —
  per-date AUC recomputation is expensive and low-value).

**Detail page** `/seasons/{year}/stats/predictor/{statName}`:
- Standard header + season selector; breadcrumb "← Predictor".
- The existing D3 red/green scatter, moved verbatim from stat-detail: x = home
  team's entering value, y = away team's, green = home win, red = home loss,
  with legend and the "Based on X of Y completed games…" caveat.
- **Usefulness summary panel** (this is new prose real estate the old cramped
  layout lacked): AUC with a plain-language interpretation band
  (≤0.55 "no better than a coin flip on its own", 0.55–0.65 "weak signal",
  0.65–0.75 "moderate", >0.75 "strong"), better-team-wins %, sample size, and
  one sentence on reading the chart (upper-left = away team entered better,
  etc.).
- Links: stat detail (distribution/rankings), category page, predictor index.

**Data**: `StatPageService` already computes per-stat scatter + AUC on demand.
The index page needs AUC for all ~33 stats at once — precompute per
(season, latestDate) and cache in-process (same pattern as
`SeasonWrapService.clearCache()`), invalidated when the stats calc runs.

### 5.5 Correlation — `/stats/correlation` (NEW)

Replaces the Scatter Matrix tab on `/rankings`, generalized and user-configurable.

- **Variable picker**: a checkbox panel grouped as: Results/Scoring basics
  (W%, PPG, Opp PPG, Margin), Ratings (RPI, Massey, Bradley-Terry, B-T
  Weighted, AdjO, AdjD, Tempo), and the stat categories (all §5.2 stats).
  Default selection = today's 8 matrix variables (W%, PPG, OPP, ±, RPI, Massey,
  B-T, BTW).
- **Limit**: minimum 2, maximum 8 variables (an 8×8 matrix is already at the
  edge of legibility; the picker disables further checks at 8 and shows
  "8 of 8 selected").
- **Matrix**: reuse the existing D3 component (diagonal = label+range, lower
  triangle = scatters, upper triangle = Pearson r cells with the existing
  strength-bucket coloring, hover for exact r) — extracted from the rankings
  fragment into a parameterized fragment + shared JS so both this page and any
  future embedding use one implementation.
- **State & persistence**: selection encoded in the URL
  (`?vars=win_pct,ppg,rpi,massey`) so any view is shareable/bookmarkable.
  Logged-in users get a "Save as my default" button storing the CSV in a
  `UserPreference` (new key, e.g. `stats.correlation.vars`); the page loads
  URL params first, then the preference, then the built-in default.
- Season selector + as-of date select (snapshot dates, as on `/rankings`).
- Footnote: n = teams with data on the date; correlations are across teams,
  not across games.

**Data**: team-level values per variable per date. Registry stats via
`TeamStatSnapshotRepository`, wide basics + RPI via
`TeamSeasonStatSnapshotRepository.findBySeasonAndDate`, ratings via
`TeamPowerRatingSnapshotRepository`. A small server-side assembler DTO
(teamId → {var → value}) feeds the existing client-side Pearson computation.

### 5.6 Glossary — `/stats` (light touch)

Stays as the card index, re-grouped to match the §5.2 categories exactly (it
already groups by `StatCatalog` category; Results/Scoring entries are added
when those stats become first-class). Each card links to the stat detail;
category headers link to the category pages.

---

## 6. Power Rankings section

### 6.1 Overview — `/rankings` (modified)

- Keeps the comprehensive sortable table (Team/Record/Scoring/Model Ratings
  grouped columns, sticky identity columns, as-of date select) as the **only**
  content — the Model View and Scatter Matrix tabs are removed.
  - Model View's four top-N panels are superseded by the per-model pages.
  - Scatter Matrix is superseded by `/stats/correlation` (a "Correlation
    explorer →" link goes in the header).
- Each model's column header links to its model page.
- "Scoring Pace Index" (MASSEY_TOTALS) is not a quality rating and is not in
  the menu; it remains visible only where it is today (rankings table column
  is *not* added; it currently appears only in the removed Model View tab —
  it survives on model pages only if OQ-6 says so).

### 6.2 Per-model ranking pages — `/rankings/{modelSlug}` (NEW)

Slugs: `rpi`, `massey`, `bradley-terry`, `bradley-terry-weighted`, and
(proposed, OQ-6) `adjusted-efficiency`.

Shared layout:
- Standard header: model name, a 2–4 sentence plain-language explainer of what
  the model measures and how (sourced from POWER_MODELS.md / RPI.md prose),
  season selector, as-of date picker.
- **Full ranked table** (all teams, not top-N): Rank, Team (logo+name, link),
  Conference, Record, model-specific value columns, GP. Client-side find-team
  filter like the stat rank table. Rows with GP < 5 get the existing
  `--low-gp` de-emphasis.
- **Rating-over-time chart** (nice-to-have, phase-2 of each page): line chart
  of the top-10 teams' rating trajectories across the season's snapshot dates.
  Data exists (`findByTeamAndSeason` time series; `/api/power-ratings` already
  serves Massey/BT series).
- Footnote row for model params where meaningful (Massey HCA; B-T HCA;
  adjusted-efficiency intercept/HCA) from `PowerModelParamSnapshot`.

Model-specific columns:
- **RPI**: RPI, WP, OWP, OOWP (all four live on `TeamSeasonStatSnapshot`).
  Explainer notes the 25/50/25 weighting and links the three component stat
  pages in `results`.
- **Massey**: Rating (points vs average team), GP.
- **Bradley-Terry / Weighted**: Rating (θ), implied win prob vs average team
  (nice-to-have), GP.
- **Adjusted Efficiency** (if adopted): AdjO, AdjD, Net (AdjO − AdjD), Tempo,
  GP — the classic four-column efficiency table. All four snapshot types
  exist (`ADJ_OFF`, `ADJ_DEF`, `ADJ_TEMPO`; Net computed in the view).

---

## 7. Models section ("Advanced models")

### 7.1 Which models appear

A model is **public** iff it has status `ACTIVE` in the `ml_models` registry
and its bundle is loaded/servable. `CANDIDATE` and `RETIRED` models never
appear in the public nav or pages (candidates are shadow-evaluated in admin
only). Proposed (OQ-1): `ADJ_EFF` — the classical adjusted-efficiency
*prediction* model — is also listed as a model, since it has full
`PredictionEvaluation` coverage and is the best classical predictor; it simply
has no feature manifest, so its About page uses a hand-written description.

### 7.2 Model hub — `/models/{slug}` (NEW)

A hub page with a sub-nav tab strip (shared component, §9.3):
**About | Schedule | Bracket | Matchup**. The About tab is the hub URL itself;
Schedule/Bracket are real sub-URLs (below); Matchup is a link to the shared
matchup page with this model preselected.

**About tab content** — "list the features and the performance characteristics":

1. **Identity card**: display name, slug, "Default" badge if applicable,
   version, trained date, feature-set name, train seasons, held-out test
   season. (All on `MlModel` / `MlBundleStatus`.)
2. **Features**: the ordered feature list from `features.json` via
   `MlPredictionService.featureNames(slug)`, grouped for humans (Ratings /
   Form & schedule / Box-score profile / Priors / Matchup terms — grouping
   metadata is a small static map keyed by feature-name prefix, not a new
   contract with Python). Feature count headline ("77 features").
3. **Performance**:
   - Training metrics from the manifest (spread RMSE/MAE, Brier, accuracy)
     labelled as training-time numbers.
   - **Honest evaluation** from `PredictionEvaluationRepository` filtered to
     this `ML:{slug}` (or `ADJ_EFF`): spread MAE/RMSE, winner %, log loss,
     Brier, and the vs-BOOK paired table (Δ MAE, ATS, O/U, CLV) — the same
     aggregates the compare page uses, scoped to one model, with season and
     segment selectors.
   - **In-sample honesty**: seasons in the model's train set are badged
     `in-sample` exactly as on the compare page today, and the page defaults
     its season selector to the held-out test season when one exists. This
     caveat is a hard requirement, not a nice-to-have (see punchlist baselines:
     in-sample "beats the book" readings are flattery).
   - Walk-forward per-season table when the manifest carries one.
   - Calibration chart (deciles vs actual, reusing the compare page's chart)
     scoped to this model.

### 7.3 Model schedule — `/models/{slug}/schedule?date=YYYY-MM-DD` (NEW)

"Predictions/performance by date." A date-navigated page in the visual
language of `/games` (prev/next day arrows, date picker, HTMX list swap):

- **Future or in-progress dates**: prediction cards (reuse
  `fragments/prediction-card.html`: teams, logos, predicted spread/total,
  win-prob donut) showing *this model's* numbers.
- **Past dates**: one row/card per FINAL game: predicted spread vs actual
  margin (with error), predicted total vs actual, win prob vs outcome, and a
  right/wrong pick badge (✓/✗ using the existing success/danger tokens). A
  small day-summary strip on top: "7–3 on winners, spread MAE 8.2, vs book …".
- Model switcher select in the header (jumps between models, same date).
- Default date: today during the season; otherwise the last date with games.

**Data**: past = `PredictionEvaluation` rows (needs one new repo method:
by model + date, with game/team joins); future = `SeasonPredictionCache` /
`PredictionService` for upcoming games (already how `/predictions` works —
this page is effectively `/predictions` scoped to one model plus a past-date
mode).

### 7.4 Model bracket — `/models/{slug}/bracket/{year}` (NEW)

"Bracket — prediction (future) / performance (past)."

**Past tournaments (performance mode)** — data fully exists:
- Render the season's bracket reusing `fragments/bracket` slot fragments, with
  a per-game model overlay: each decided slot is marked ✓ (model's pre-game
  favorite won) or ✗, and shows the model's pre-game home-win probability
  converted to a favorite + percentage. Source: the `PredictionEvaluation` row
  for that game × model (leakage-free pre-game numbers by construction).
- Summary header: record by round ("First round 24–8, Sweet 16 6–2, …",
  overall %, log loss over tournament games), plus "upsets called" (games
  where the model's favorite disagreed with the seed favorite and the model
  was right) and "upsets missed".
- Games with no evaluation row (e.g. model didn't exist / insufficient
  snapshots) render neutral with a "no prediction" tooltip; the summary
  denominators count only predicted games.

**Current season during the tournament (prediction mode)**:
- Same bracket; undecided slots whose matchup is known show the model's win
  probability for the scheduled game (from the schedule-page machinery);
  decided slots flip to performance marks as results arrive. This is
  matchup-by-matchup, **not** a full tournament simulation.
- Full pre-tournament bracket simulation (advancing probabilities through all
  rounds from the Selection Sunday field) is explicitly **future work** —
  see OQ-4. The page reserves a summary slot for it ("Championship odds")
  but v1 ships without it.

Year tabs on the page (same component as §4.2), listing years where **both**
tournament games and this model's evaluation rows exist.

### 7.5 Matchup predictor — `/models/matchup` (MOVED + fix)

The existing matchup page moves under Models unchanged in concept: pick two
teams, date, neutral-site toggle → result card. Spec'd changes:

- The result already contains every model's prediction
  (`PredictionResult.mlModels` map + classical models); the page presents a
  **comparison table** (one row per public model: spread, total, home win %)
  rather than a single blended card, with the entered-from model highlighted
  when the user arrived via a model hub's Matchup tab (`?model=slug`).
- Team pickers get type-ahead (HTMX search like the teams page) instead of a
  367-option `<select>`.
- Punchlist item 3 says "Fix matchup page" — the specific defect(s) should be
  enumerated before build (OQ-8).

### 7.6 Compare models — `/models/compare` (MOVED, light touch)

Today's `/predictions/performance` page, relocated with its season / window /
segment selectors and seven sections intact. Changes:

- Each model name throughout links to its hub page.
- The inline `modelColor()` palette and inline green/red hex styles migrate to
  the shared chart tokens (§9.4) — behavior identical.
- Header intro sentence updated to point at per-model About pages for detail.

### 7.7 Models index — `/models` (NEW, small)

Landing page for the section (also the redirect target if a model slug is
unknown): a card per public model (name, feature set, headline MAE/log-loss
vs BOOK, Default badge, links to its tabs) + links to Compare and Matchup.
Keeps the nav dropdown short while giving the section a home.

---

## 8. Data & backend implications (informational — not implementation)

Recorded so the spec's feasibility is explicit; sequencing/design belongs to
the implementation plan.

### 8.1 Results/Scoring stats as first-class stats

WP/OWP/OOWP/PPG/OPPG/margin/volatility currently live only as wide columns on
`TeamSeasonStatSnapshot`. The category pages, stat-detail pages, predictor and
correlation pages all consume the long-format
`TeamStatSnapshot`/`StatCatalog`/`StatPageService` machinery. Recommended
approach: a second `DailyStatCalculator` implementation ("results calculator")
emitting these ~7 stats from game results (and mirroring OWP/OOWP from the
existing RPI computation), plus matching `StatCatalog` entries. Zero
migrations (long table + registry pattern was designed for this); the existing
1:1 catalog-coverage test extends automatically. RPI itself stays a *ranking*
(§6.2), not a stat page.

### 8.2 New queries/services (all read-side)

- `PredictionEvaluation` by model × date and by model × season ×
  tournamentType=NCAA (schedule + bracket pages).
- Predictor-index AUC precompute + cache (§5.4).
- Correlation assembler joining the three snapshot families per date (§5.5).
- Nav model list advice (§3); public-model guard for `/models/{slug}` routes.
- Optional: `/api/power-ratings` additions for ADJ_OFF/ADJ_DEF/ADJ_TEMPO
  (currently absent from the JSON API) if the adjusted-efficiency page ships.

### 8.3 Explicitly no new data required

Brackets (types/rounds/regions/seeds on `Game` since V17), matchup prediction
(`PredictionService.predictMatchup`), model metadata (`MlModel` +
`features.json` + `MlBundleStatus`), all performance aggregates
(`PredictionEvaluationRepository` projections), and all ranking snapshots
already exist. The source sketch's "we have all of the data" claim checks out,
with §8.1 as the one reshaping exercise.

---

## 9. Look & feel consolidation

The redesign touches most pages; these shared patterns keep everything
consistent (and fix existing drift). All per UI.md rules: tokens only, BEM
naming, HTMX-first, light public theme, mobile-responsive.

### 9.1 Page header pattern (new shared fragment)

Every content page uses one header fragment:
`title` (Barlow Condensed), optional `subtitle`/description line, optional
breadcrumb slot on the left, and a right-aligned **controls cluster** (season
selector, as-of date picker, model switcher — whichever apply). Today each
page hand-rolls this with drifting markup; one fragment + one CSS block ends
that.

### 9.2 Season & as-of controls (standardized)

- Season selector: the `<select>` that navigates to the same page in another
  year — one fragment, used everywhere (currently three slightly different
  implementations).
- As-of date control: standardize on the **date input bounded by season
  start/latest snapshot** (the stat pages' pattern) everywhere a snapshot date
  applies, replacing the rankings page's snapshot-date `<select>`; HTMX swap
  with the standard spinner indicator.

### 9.3 Sub-nav tab strip (new shared component)

One tab component for: bracket year tabs, model hub tabs (About/Schedule/
Bracket/Matchup), and the team/conference season tabs (already similar —
converge the CSS). Tabs are links (real URLs, hx-boosted), not JS-only
state, so every tab view is bookmarkable.

### 9.4 Chart palette tokens

Chart colors are currently hardcoded hex in five different JS files/templates.
Add a `--chart-*` token group to `main.css` (series-1…8, success/danger for
win/loss marks, benchmark/muted for BOOK-style reference series, grid, axis)
and one tiny shared JS helper (`js/chart-theme.js`) that reads them via
`getComputedStyle` and exposes `chartColor(i)` / `modelColor(type)` — used by
both Chart.js and D3 pages. The model→color mapping (Massey sky, B-T blue,
BTW violet, BOOK amber, ML greens) is preserved but defined once. Kill the
inline `style="color:#065f46"`-type styles in model-performance in the same
pass (`.delta--good` / `.delta--bad` classes on the shared tokens).

### 9.5 Chart library rule

Both libraries stay (Chart.js for standard axes/lines/bars, D3 for the custom
scatter/matrix/bracket work) but the split is documented in UI.md and both
consume §9.4 tokens. No new charting dependencies.

### 9.6 Misc cleanup riding along

- Footer: add Glossary, Compare Models, and (existing) About links.
- Empty states: one `.empty-state` pattern (icon-less card, muted text,
  optional action link) replacing per-page variants.
- The quote banner, home page, auth pages, and account pages are untouched.
- `main.css` is 4,700 lines of append-ordered sections; this project does
  **not** include a CSS rewrite, but every touched section should land in a
  clearly delimited block, and new spacing/radius values should reference the
  existing `--radius` / shadow conventions.

### 9.7 Account page (light touch)

The source sketch's User section (profile name, email, favorite teams, daily
email toggle) already exists on `/account`. Only change: the nav dropdown's
"Profile" wording stays, and the account page's section order is checked
against the sketch (Profile → Email → Favorites → Email digest preference) —
cosmetic reordering at most.

---

## 10. Open questions — RESOLVED 2026-08-21

**Decision: every recommendation below is adopted as written**, with one
exception — OQ-8 ("fix matchup page", punchlist #3) was a stale note and is
set aside; the matchup changes spec'd in §7.5 (move, per-model comparison
table, type-ahead pickers) are the complete matchup work list. In summary:
models = ACTIVE ML + ADJ_EFF (OQ-1); registry display names renamed before
launch (OQ-2); `/predictions` redirects (OQ-3); bracket overlay in v1,
simulator deferred to a future spec (OQ-4); bracket always in nav (OQ-5);
Adjusted Efficiency page ships under Power Rankings (OQ-6); correlation max
8 / default 6 with URL state + saved preference (OQ-7); predictor AUC uses an
in-process cache, no new table (OQ-9); Results stats get friendly titles with
acronyms secondary (OQ-10).

- **OQ-1 — What counts as an "advanced model"?** ML models only, or also
  `ADJ_EFF` (and other classical prediction models)?
  *Recommendation:* public ACTIVE ML models **plus ADJ_EFF** as a named model
  ("Adjusted Efficiency (classic)") — it has full evaluation coverage, is the
  best classical predictor, and gives the section content even if ML models
  are retired; Massey/B-T stay under Power Rankings only (their predictive
  performance remains visible on Compare).

- **OQ-2 — Model naming in the menu.** `ml_models.display_name` values were
  chosen for admin (e.g. "model-4"). Are they presentable, or do public models
  get friendlier display names first?
  *Recommendation:* rename in the registry before launch (it's a plain DB
  field); no code implication beyond doing it.

- **OQ-3 — Fate of `/predictions` (upcoming-games cards, all models).**
  Redirect to the default model's schedule (spec'd), or keep a multi-model
  "today's predictions" page?
  *Recommendation:* redirect. The schedule page with a model switcher covers
  it; two near-identical pages will drift. The home page's slate panel already
  gives a modelless daily view.

- **OQ-4 — Bracket "prediction (future)" ambition.** Matchup-by-matchup
  probabilities on the live bracket (spec'd, data exists) vs a true
  pre-tournament simulation with round-by-round advancement odds
  (new simulation engine + a frozen Selection-Sunday snapshot).
  *Recommendation:* ship the overlay in v1; treat the simulator as its own
  future spec (it's genuinely new modeling surface, incl. how to handle First
  Four and re-normalization as results land).

- **OQ-5 — Bracket nav gating.** Always-visible menu entry landing on the most
  recent tournament (spec'd), or keep the current phase gate?
  *Recommendation:* always visible — historical brackets are evergreen content
  and the phase gate made the feature undiscoverable for ~7 months a year.
  Keep the phase logic for the home-page panel only.

- **OQ-6 — Adjusted Efficiency under Power Rankings.** The sketch lists only
  RPI/BT/BTW/Massey, but ADJ_OFF/ADJ_DEF/ADJ_TEMPO snapshots exist and the
  AdjO/AdjD/Net/Tempo table is the genre-standard page.
  *Recommendation:* include it; it's the most-requested table format in this
  space and it's nearly free. (MASSEY_TOTALS stays out of the menu either
  way — it measures pace, not quality.)

- **OQ-7 — Correlation variable cap.** Sketch says "limit the number and make
  it configurable"; spec picks max 8 with URL + saved-preference persistence.
  Confirm 8, and confirm preference-saving is worth it vs URL-only.
  *Recommendation:* 8 max / 6 default; ship URL-state first, add the saved
  preference only if it's cheap in the same pass (it is — one `PreferenceKeys`
  entry).

- **OQ-8 — "Fix matchup page" (punchlist #3).** What specifically is broken
  or unsatisfying today? The spec assumes: no type-ahead, single-card result
  instead of per-model comparison, and (per an old note) a possible spread
  sign-convention display issue. Needs confirmation so the fix list is
  complete.

- **OQ-9 — Predictor index cost.** Computing AUC for ~33 stats × a season's
  games on first hit is nontrivial. In-process cache keyed on
  (season, snapshotDate) invalidated by the stats-calc run (spec'd) — confirm
  that's acceptable vs persisting predictiveness into a table.
  *Recommendation:* cache first; persist only if cold-start latency proves
  annoying.

- **OQ-10 — Category page for "Results" naming.** WP/OWP/OOWP are RPI
  jargon; on a public page they need friendlier titles ("Win %", "Opponents'
  win %", "Opponents' opponents' win %") with the acronyms in the mechanics
  line. Confirm the sketch's WP/OWP labels weren't a hard requirement.
  *Recommendation:* friendly titles, acronyms secondary.

---

## 11. Acceptance criteria (redesign-level)

1. Every public page is reachable from the nav in ≤ 2 interactions (open
   dropdown → click), on desktop and mobile.
2. No orphan pages: `/stats`, `/models/compare` (née performance), `/about`
   all linked from nav or footer.
3. All pre-existing URLs either serve their old content or 301 to the new
   location (table in §2.3).
4. The nav renders correctly logged-out, as USER, and as ADMIN; dropdowns are
   keyboard- and screen-reader-operable; mobile accordion works at 375px.
5. Every new page uses the shared header/tabs/empty-state/chart-token
   components; no new inline hex colors or inline styles.
6. Model pages never surface CANDIDATE/RETIRED models; in-sample seasons are
   always badged on model performance displays.
7. Season-scoped URL twins exist for every season-dependent page and the
   unscoped form redirects to the latest season.
