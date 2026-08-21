# Menu & GUI Reorganization — Implementation Plan

Status: **READY** (spec approved 2026-08-21)
Spec: [MENU_AND_GUI_SPEC.md](MENU_AND_GUI_SPEC.md) — section references below (§n) point there.

---

## Guiding principles

1. **Every phase is independently deployable** and leaves the site fully
   working. No phase depends on a later one.
2. **Redirects land in the same commit as any URL move.** No dead bookmarks,
   ever.
3. **Build shared components first, content second.** Phases 3–5 consume the
   Phase-1 fragments/tokens; nothing hand-rolls a header, tab strip, or chart
   palette after Phase 1.
4. **Nav items appear when their page ships.** The dropdown structure lands in
   Phase 2; later phases append entries to their section's dropdown as pages
   arrive, so the menu never links to a 404.
5. **Visual verification is mandatory for new templates.** Two classes of
   Thymeleaf bug (attribute-precedence nulls, ternary-inside-`@{...}` garbage
   URLs) are invisible to MockMvc — every new/reshaped page gets a look on the
   dev server (`./mvnw -q resources:resources` hot-deploys templates/CSS).

Phase sizes: **S** ≈ half a day, **M** ≈ 1–2 days, **L** ≈ 3+ days of focused work.

| Phase | Contents | Size | Depends on |
|---|---|---|---|
| 1 | Foundations: chart tokens, shared fragments, nav dropdown component | M | — |
| 2 | IA flip: new nav structure, URL moves + redirects, bracket year tabs | M | 1 |
| 3 | Statistics: Results/Scoring stats, category pages, Predictor, Correlation | L | 1, 2 |
| 4 | Power Rankings: simplify overview, per-model pages incl. Adjusted Efficiency | M | 1, 2 |
| 5 | Models: index, About, Schedule, Bracket overlay, Matchup upgrade | L | 1, 2 (3/4 not required) |
| 6 | Polish, QA sweep, acceptance checklist, docs | M | all |

Phases 3, 4, 5 are mutually independent and can be built in any order (or in
parallel); the listed order front-loads the biggest user-visible win.

---

## Phase 1 — Foundations (no visible IA change)

Goal: the shared vocabulary every later phase uses. Ships as a pure
refactor/polish release.

### 1.1 Chart theme tokens (§9.4)
- `static/css/main.css`: add a `--chart-*` token group to `:root` (and the
  dark block): `--chart-1..8` (series), `--chart-win`/`--chart-loss` (alias
  success/danger), `--chart-benchmark` (BOOK amber), `--chart-grid`,
  `--chart-axis`, plus `.delta--good`/`.delta--bad` utility classes.
- New `static/js/chart-theme.js`: reads tokens via `getComputedStyle`,
  exposes `chartColor(i)`, `modelColor(type)` (preserving today's fixed
  model→color map), `chartTheme()` for grid/axis. Loaded in
  `layout/default.html` before page scripts.
- Migrate the five hardcoded palettes to it: `pages/model-performance.html`
  (inline `modelColor` + inline `#065f46`/`#991b1b` styles → classes),
  `js/stat-page.js` (GREEN/RED/AXIS/GRID consts), `fragments/scatter-matrix.html`,
  `js/game-chart.js`, `pages/season-stats.html` chart config.
- **Verify:** each chart looks pixel-identical before/after (dev server).

### 1.2 Shared page fragments (§9.1–9.3, §9.6)
New fragments + CSS blocks, each tiny:
- `fragments/page-header.html` — title / subtitle / breadcrumb slot /
  right-aligned controls slot.
- `fragments/controls.html` — `seasonSelect(currentYear, years, urlTemplate)`
  and `asOfDate(...)` (the bounded date-input + HTMX-swap pattern from
  stat-detail, with the standard spinner).
- `fragments/tabs.html` — link-based tab strip (used later for bracket years,
  model hubs; converge team/conference season-tab CSS onto it in Phase 6).
- `fragments/empty-state.html` — message + optional action link.
- Adopt on two existing pages to prove the pattern: `pages/stat-detail.html`
  and `pages/season-stats.html` (header + controls only, content untouched).
- **Thymeleaf gotchas apply** (memory): keep iteration on `th:block`, hoist
  ternaries out of `@{...}` into `th:with`.

### 1.3 Nav dropdown component (§3)
- Extend `.nav__dropdown` CSS/JS into a general component: click-to-open,
  outside-click/`Esc`/sibling-open closes, `aria-haspopup`/`aria-expanded`,
  optional labelled dividers inside a panel; mobile accordion behavior in
  `js/app.js` (one section open at a time under the hamburger).
- The account dropdown is refit onto the general component (behavior
  unchanged) — this phase ships with the nav *content* still flat.

### 1.4 Section-aware active state
- Convention: controllers set `currentSection` + `currentPage`;
  `nav.html` highlights the top-level item on `currentSection`. Add the
  attribute to existing controllers now (mechanical), values per the §2.1 tree.

### Tests
- Existing MockMvc suite green (no route changes).
- Fragment render smoke tests (header/controls/tabs render with model attrs).
- Manual: mobile hamburger + account dropdown at 375px; keyboard walk.

---

## Phase 2 — IA flip: new nav, URL moves, bracket tabs

Goal: the new menu skeleton, orphan pages rescued, bracket always reachable.
After this phase the menu matches §2.1 with placeholders resolved to existing
pages; Statistics/Power Rankings/Models dropdowns grow in Phases 3–5.

### 2.1 New nav structure (`fragments/nav.html`)
- **Games ▾**: Scores & Schedule (`/games`), NCAA Bracket (`/bracket`).
- **Statistics ▾**: League Overview (`/seasons/stats`), Glossary (`/stats`).
- **Power Rankings ▾**: Overview (`/rankings`).
- **Models ▾**: Compare Models (`/models/compare`), Matchup Predictor
  (`/models/matchup`), Today's Predictions (`/predictions` — temporary item,
  replaced in Phase 5.3).
- News, Home, Teams, Conferences stay flat. Right side (Admin / account /
  Sign in) unchanged.
- Remove the flat `Season Stats`, `Rankings`, `Bracket`, `Predictions`,
  `Matchup` items.

### 2.2 URL moves + permanent redirects (§2.3)
- `ModelPerformanceController`: primary mapping `GET /models/compare`;
  `GET /predictions/performance` → 301.
- Matchup: `GET /models/matchup` + HTMX `GET /models/matchup/result`;
  old `/predictions/matchup[/result]` → 301 (HTMX endpoint must redirect too —
  or simpler, keep both mappings on the fragment endpoint for one release).
- `/predictions` untouched this phase.
- Update all in-template links to the new URLs (grep for the old paths).

### 2.3 Bracket changes (§4.2)
- `BracketWebController`: `/bracket` redirects to the most recent season
  having ≥1 `NCAA_TOURNAMENT` game (new repository query;
  drop the redirect-home phase fallback). Nav entry is unconditional —
  delete the `showBracketLink()` check from `nav.html` (the method stays for
  the home-page panel).
- `pages/bracket.html`: year tab strip via `fragments/tabs.html`, listing
  seasons with tournament games (newest first), current year active.
- Cross-link block: "How did the models do?" — deferred to Phase 5.4 (needs
  the model bracket pages); leave a TODO comment, not a dead link.

### 2.4 Footer (§9.6)
- `fragments/footer.html`: About · Stats Glossary · Compare Models.

### Tests
- MockMvc: 301s land correctly; new mappings render; nav renders for
  anonymous / USER / ADMIN (three `@WithMockUser` variants); active-section
  assertions for one page per section; `/bracket` redirect chooses the right
  year (fixture with two tournament seasons).
- Manual: full menu walk on desktop + mobile.

---

## Phase 3 — Statistics section

Goal: category pages, Predictor, Correlation; stat-detail slims down.

### 3.1 Results/Scoring as first-class stats (§8.1)
The one data-shaping task. **Naming hazard (do not skip):**
`SeasonPopulationStat` deletes are *stat-name-scoped* and
`StatisticsTimeSeriesService` already writes population rows named `win_pct`,
`mean_pts_for`, `mean_pts_against`, `mean_margin`, `correlation_pts` (used for
the wide snapshot's z-scores). The new long-format stats MUST use distinct
names or the two writers will clobber each other's rows. Chosen names:

| stat_name | Title (OQ-10: friendly first) | higherIsBetter | Source |
|---|---|---|---|
| `wp` | Win % (WP) | ↑ | game results |
| `ppg` | Points per game (PPG) | ↑ | game results |
| `opp_ppg` | Opponent points per game | ↓ | game results |
| `scoring_margin` | Scoring margin | ↑ | game results |
| `margin_volatility` | Scoring volatility (σ of margin) | ↓ | game results |
| `owp` | Opponents' win % (OWP) | ↑ | RPI component logic |
| `oowp` | Opponents' opponents' win % (OOWP) | ↑ | RPI component logic |

Tasks:
- New `service/ResultsStatCalculator.java` implementing `DailyStatCalculator`
  (same registry pattern as `BoxScoreStatCalculator`; per-run instance per the
  pipeline invariant). Inputs are the season's games from
  `SeasonGameDataLoader` — no box scores needed, so these stats cover all
  teams/dates even where `team_game_stats` is sparse.
- OWP/OOWP: extract the existing RPI component computation from
  `StatisticsTimeSeriesService` (its `RpiComponents` record) into a reusable
  helper both callers share — do not duplicate the exclude-own-games logic.
- `TeamStatTimeSeriesService.createCalculators()` returns both calculators.
- `StatCatalog`: add the seven `StatInfo` entries under categories
  **Results** (`wp`, `owp`, `oowp`) and **Scoring** (`ppg`, `opp_ppg`,
  `scoring_margin`, `margin_volatility`); the existing 1:1
  catalog-coverage test extends automatically and will fail until both sides
  match.
- `controller/TeamStatDisplay.java`: decide whether the team-page stat panel
  shows the new stats (recommend yes: Results + Scoring groups at the top).

**Backfill (ops, after deploy):** `POST /admin/scrape/timeseries/{year}` for
each season (2021–2026) — verified to run the full calculation directly,
bypassing the `StatCalcGateService` watermark. ~6 manual clicks or a one-line
curl loop with basic auth. Until run, new stat pages show the standard empty
state — acceptable.

### 3.2 Category pages (§5.2)
- New `StatCategory` enum/registry: slug, title, description, ordered stat
  names — the single source for §5.2's table. Unit test pins that category
  slugs + reserved words (`predictor`, `correlation`) are disjoint from
  `StatCatalog` names.
- Routes on `StatPageController` (or a sibling `StatCategoryController`):
  `GET /stats/{categorySlug}` (redirect to latest year) and
  `GET /seasons/{year}/stats/{categorySlug}` — matched before the stat-name
  fallback.
- `pages/stat-category.html`: Phase-1 header + season/as-of controls; card
  grid, one card per stat: title, direction chip, top-10 via
  `TeamStatSnapshotRepository.findBySeasonStatAndDate`, links to stat detail +
  predictor detail.
- Nav: append the eight category items (with divider) to Statistics ▾.

### 3.3 Predictor pages (§5.4)
- New `service/StatPredictivenessService`: computes {auc, naiveAccuracy,
  gamesPlotted, gamesTotal} for every catalog stat at (season, latest
  snapshot date), via the existing `StatPageService`/`StatMath` internals;
  in-process cache keyed (seasonId, snapshotDate) with a `clearCache()`
  invalidated from the stats-calc completion path (same lifecycle as
  `SeasonWrapService`) — OQ-9 decision: no new table.
- Routes: `GET /stats/predictor[→latest]`, `GET /seasons/{y}/stats/predictor`
  (index), `GET /seasons/{y}/stats/predictor/{statName}` (detail).
- `pages/predictor-index.html`: intro block, all-stats table sorted by AUC
  (AUC bar anchored at 0.5, de-emphasized below 0.55), season selector.
- `pages/predictor-detail.html`: the D3 red/green scatter moved from
  stat-detail (split `js/stat-page.js`: scatter code → `js/predictor-page.js`,
  histogram/KDE stays), plus the usefulness panel with the §5.4
  interpretation bands.
- Slim `pages/stat-detail.html`: remove scatter section; add the cross-link
  card ("Predicts home win: AUC 0.NN → Predictor page") using the cached
  figure; breadcrumb now "← {Category}".
- Nav: Predictor entry under Statistics ▾.

### 3.4 Correlation explorer (§5.5)
- Extract the scatter-matrix D3 from `fragments/scatter-matrix.html` into a
  parameterized fragment + `js/scatter-matrix.js` (var list and data supplied
  by the caller). `/rankings` keeps consuming it until Phase 4 removes its tab.
- New `service/CorrelationDataService`: assembles teamId → {var → value} for a
  (season, date, varList) across the three snapshot families
  (`TeamStatSnapshot`, `TeamSeasonStatSnapshot` wide basics + RPI,
  `TeamPowerRatingSnapshot` ratings incl. ADJ_OFF/ADJ_DEF/ADJ_TEMPO).
  Variable registry: id, label, group, source, fetch key.
- Routes: `GET /stats/correlation[→latest]`,
  `GET /seasons/{y}/stats/correlation?vars=a,b,c&date=…`.
- `pages/correlation.html`: grouped checkbox picker (max 8, min 2, counter,
  disable at cap), matrix, as-of date, season selector. State precedence:
  URL `vars` → saved preference → default 8 (§5.5).
- Preference save (OQ-7): new `PreferenceKeys.STATS_CORRELATION_VARS`;
  `POST /stats/correlation/save-default` (auth'd, CSRF form, HTMX) storing the
  CSV; button hidden for anonymous.
- Nav: Correlation entry under Statistics ▾.

### 3.5 Glossary + League Overview touch-ups (§5.1, §5.6)
- `pages/stats-index.html`: category headers link to category pages; new
  Results/Scoring cards appear automatically once catalog entries exist.
- `pages/season-stats.html`: retitle "League Overview", drop the Bracket
  button, add Predictor/Correlation links.

### Tests
- `ResultsStatCalculator` unit tests (known fixture games → WP/PPG/OWP by
  hand); OWP helper parity test against existing RPI values.
- Catalog coverage + slug-disjointness tests.
- Predictiveness cache test (compute-once, invalidation).
- `CorrelationDataService` join test across the three sources.
- MockMvc for every new route incl. bad slug → 404, `vars` over cap → clamped.
- Integration tests follow the FK-safe cleanup order (memory note).
- Manual dev-server pass on all four new page types.

---

## Phase 4 — Power Rankings section

### 4.1 Simplify `/rankings` (§6.1)
- Remove the tab strip + Model View and Scatter Matrix tabs from
  `pages/comprehensive-rankings.html` (and the `switchCompTab` lazy-load JS);
  delete `fragments/rankings-table.html` once nothing references it; the
  scatter-matrix fragment survives as the shared component (Phase 3.4).
- Header gains "Correlation explorer →" link; model column headers link to
  the per-model pages.
- Retire `ComprehensiveRankingsController` endpoints `/rankings/{year}/model-view`
  and `/rankings/{year}/scatter-matrix`.

### 4.2 Per-model pages (§6.2)
- New `PowerRankingPageController`: `GET /rankings/{modelSlug}` (→ latest
  year), `GET /seasons/{y}/rankings/{modelSlug}`; slug registry: `rpi`,
  `massey`, `bradley-terry`, `bradley-terry-weighted`, `adjusted-efficiency`
  (OQ-6: adopted). Unknown slug → 404. (Path order: these registrations must
  not shadow `/rankings/{year}/table` — year segment is numeric, slugs are
  not; route with a regex or explicit slug set.)
- One template `pages/power-ranking.html` parameterized by a per-model view
  model: explainer paragraph (prose sourced from POWER_MODELS.md / RPI.md),
  full ranked table with model-specific columns (§6.2 list), GP<5
  de-emphasis, client-side find-team filter (reuse stat-rank pattern),
  params footnote from `PowerModelParamSnapshotRepository.findLatestParamBefore`.
- Data assembly: `TeamPowerRatingSnapshotRepository` for Massey/BT/BTW;
  wide snapshot for the RPI page (RPI/WP/OWP/OOWP columns, rank by RPI);
  adjusted-efficiency joins ADJ_OFF/ADJ_DEF/ADJ_TEMPO rows per team+date,
  Net = AdjO − AdjD computed in the assembler, ranked by Net.
- Nav: five entries under Power Rankings ▾.

### 4.3 Nice-to-haves (build only if the phase has slack; otherwise backlog)
- Top-10 rating-trajectory line chart per model page (Chart.js, data via
  existing per-team series queries).
- `/api/power-ratings/{year}/adjusted-efficiency` JSON endpoints for parity.

### Tests
- MockMvc per slug + 404; adjusted-efficiency join unit test (team missing
  tempo row, etc.); RPI page ties out against the season-stats table's RPI
  column for a fixture date.

---

## Phase 5 — Models section

### 5.1 Public-model foundation + index (§7.1, §7.7)
- New `service/PublicModelService`: list of public models = ACTIVE + loaded
  ML models (from `MlModelRegistryService`) **plus** the `ADJ_EFF`
  pseudo-model (OQ-1) with a hand-written descriptor (name "Adjusted
  Efficiency (classic)", no manifest). Single guard used by every
  `/models/{slug}` route (unknown/non-public slug → 404, never leaks
  CANDIDATE/RETIRED).
- `NavModelsAdvice` (`@ControllerAdvice`): exposes `navModels`
  (default first, then alphabetical) for the dropdown; reads the in-memory
  registry — no per-request query.
- `GET /models` — `pages/models-index.html`: card per public model (name,
  feature set, headline MAE/log-loss vs BOOK from the compare aggregates,
  Default badge, tab links) + Compare/Matchup links.
- **Ops (OQ-2):** rename `ml_models.display_name` values to public-facing
  names before flipping the nav (plain SQL UPDATE; record chosen names in
  ADMIN_MANUAL).
- Nav: Models ▾ gains the dynamic model entries.

### 5.2 About pages (§7.2)
- Refactor first: pull `ModelPerformanceController`'s aggregate/calibration
  assembly into a shared `service/ModelMetricsService` with an optional
  single-model scope (the repository projections already return per-modelType
  rows — scoping is a filter; calibration needs a per-model bucket query
  variant). Compare page refits onto it with zero visual change.
- `GET /models/{slug}` — `pages/model-about.html`: identity card
  (`MlBundleStatus`), grouped feature list (static prefix→group map over
  `MlPredictionService.featureNames(slug)`; ADJ_EFF gets prose instead),
  manifest training metrics, honest-evaluation block with season/segment
  selectors, in-sample badges (`trainedSeasonsBySlug`), **season selector
  defaulting to the held-out test season**, walk-forward table, calibration
  chart via chart-theme tokens.
- Hub tab strip (About | Schedule | Bracket | Matchup) via `fragments/tabs.html`.

### 5.3 Schedule pages (§7.3)
- New repo method: `PredictionEvaluationRepository.findByModelAndDate`
  (modelType, gameDate, joined game+teams).
- `GET /models/{slug}/schedule?date=…` — `pages/model-schedule.html`:
  date nav in the `/games` visual language; past dates render evaluation rows
  (predicted vs actual, ✓/✗ badge, day-summary strip); future dates render
  prediction cards scoped to this model (reuse
  `PredictionsPageService`/`SeasonPredictionCache` with a model filter —
  `PredictionResult.mlModels` already carries per-slug predictions; ADJ_EFF
  comes from its prediction object). Model-switcher select preserves the date.
- **Retire `/predictions`** (OQ-3): 301 → default model's schedule
  (→ `/models/compare` when no servable model); drop the temporary
  "Today's Predictions" nav item; `PredictionsPageController` list endpoints
  fold into the schedule controller.

### 5.4 Bracket overlay pages (§7.4)
- New repo method: evaluations by (modelType, seasonId,
  tournamentType = NCAA_TOURNAMENT).
- Overlay assembly: `BracketView` slots decorated via a gameId→evaluation map
  — new wrapper (slot + verdict: favorite, prob, correct?, no-prediction)
  rather than mutating `BracketService`. Upset called/missed: model favorite
  vs seed favorite using `homeSeed`/`awaySeed`.
- `GET /models/{slug}/bracket/{year}` — `pages/model-bracket.html`: reuses
  `fragments/bracket` team/game fragments with an overlay chip per decided
  slot; summary header (record by round, overall %, tournament log loss,
  upsets called/missed); year tabs limited to years with both tournament
  games and this model's rows; prediction-mode probabilities for known
  future matchups (from 5.3's machinery); reserved "Championship odds" slot
  renders nothing in v1 (OQ-4: simulator deferred to its own spec).
- Add the deferred cross-link on `pages/bracket.html` ("How did the models
  do?" → model bracket pages).
- Edge cases to test explicitly: First Four slots, games with no evaluation
  row (neutral render, excluded from denominators), pre-Selection-Sunday
  (page shows empty state for current year).

### 5.5 Matchup upgrade (§7.5; punchlist #3 noted as stale — this is the work list)
- `pages/matchup.html`: replace the two giant `<select>`s with HTMX
  type-ahead pickers (reuse the teams-page search endpoint/fragment pattern).
- `fragments/matchup-result.html`: per-model comparison table — one row per
  public model (spread, total, home win %), classical rows included, book
  line shown when the matchup corresponds to a real upcoming game;
  `?model=slug` (from a hub's Matchup tab) highlights that row.
  **Sign convention** (memory): model spreads are home margins; render with
  the established convention and never compare raw to `betting_odds.spread`
  without negating.

### Tests
- PublicModelService visibility matrix (ACTIVE/CANDIDATE/RETIRED/unloaded).
- Repo tests for the two new queries; overlay unit tests (fixture bracket
  with upsets + missing rows); MockMvc per route incl. candidate slug → 404;
  `/predictions` redirect; matchup fragment renders all public models.
- Manual: full model-hub walk for one ML model + ADJ_EFF, on a past season.

---

## Phase 6 — Polish, QA, acceptance

- Converge team/conference season tabs onto `fragments/tabs.html`;
  empty-state sweep (replace per-page variants).
- Dead-code removal: old rankings tab endpoints/fragments, retired
  predictions-page pieces, orphaned CSS sections (delete blocks, don't strand
  them in the 4,700-line file).
- Acceptance walkthrough per spec §11 (reachability ≤2 interactions, no
  orphans, redirect table, three auth states, keyboard/mobile at 375px,
  no new inline hex, candidate models never visible, in-sample badges,
  season-scoped URL twins).
- Cross-browser/mobile pass; Lighthouse sanity on the heaviest new pages
  (predictor index, correlation, model bracket).
- Docs: update CLAUDE.md (nav/urls/entities notes), UI.md (chart-library rule
  §9.5, new shared fragments), STAT_PAGE_SPEC.md (predictor split),
  ADMIN_MANUAL.md (display names, backfill note), punchlist (close #2
  properly, annotate #3 superseded by §7.5).

---

## Cross-cutting notes

**Rollout/ops sequence per deploy:** normal `deploy.sh`; Phase 3 additionally
needs the per-season time-series backfill (§3.1) run once after deploy —
new stat pages show empty states until it completes (~minutes per season).
No Flyway migrations anywhere in this plan; no evaluation rebuilds needed.

**Known hazards carried from memory/spec (checked into the relevant phase):**
- Population-stat name collision with `StatisticsTimeSeriesService` (3.1).
- Thymeleaf attribute precedence + ternary-in-URL gotchas — MockMvc-invisible,
  dev-server checks required (every phase).
- FK-safe `@BeforeEach` cleanup order in new integration tests.
- Betting-odds sign convention on any spread display (5.5, 5.3).
- Snapshot writes must keep going through `SnapshotJdbcWriter`; new calculator
  instances are per-run (3.1).
- `MlFeatureRegistry` and the Java↔Python contract are untouched by this
  project — no feature-set changes ride along.

**Risk register:**
- *Predictor index cold start* (33 stats × season games): mitigated by the
  cache; if first-hit latency still annoys, warm it from the stats-calc
  completion hook before considering a table (OQ-9).
- *Nav regression risk* is the widest blast radius (every page). Phase 2 is
  deliberately small and mostly markup so it can be reverted in isolation.
- *Bracket slot alignment* for overlays inherits `BracketService`'s canonical
  seed-order assumptions; overlay tests use a real past season's data shape.
- *`/rankings/{slug}` vs `/rankings/{year}/table` route ambiguity* — pinned by
  an explicit slug set + a routing test (4.2).
