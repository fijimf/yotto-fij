# ML Improvement Implementation Plan — Tiers 0–3

*Created 2026-07-26. Implements items #1–#19 of
[ML_MODELS_REVIEW_2026-07.md](ML_MODELS_REVIEW_2026-07.md) (Tiers 0–3; the market-aware
Tier 4 track is out of scope here). Update the checklist below as work proceeds — check
a box only after its step's build-and-test gate has passed.*

## Ordering rationale

**Phase 0 (measurement) → Phase 1 (data hygiene) → Phase 2 (feature set `prior-v3`) →
Phase 3 (efficiency ratings + feature set `eff-v4`) → Phase 4 (training methodology).**

Measurement first because every later phase is judged by it. Hygiene second — and before
any feature work — because two hygiene items (season-scoped windows, OT handling) change
the *semantics* of existing features and training targets, and every bundle trained after
them must be trained on clean data exactly once, not retrained again when the data
changes underneath. Feature work third, cheapest-per-point first (`prior-v3` is pure
plumbing; `eff-v4` needs a new rating engine). Methodology last, so hyperparameters and
residual targets are tuned against the final feature sets, not throwaway ones.

Every step ends with the same gate:

> **Gate G:** `./mvnw -q compile` → targeted new/updated tests → full `./mvnw test`
> (Docker running; Testcontainers). Python-touching steps additionally run
> `python -m pytest scripts/tests -q` and a smoke training run (see §Conventions).

---

## Active checklist

### Phase 0 — Honest measurement (review #1–#5)
- [x] **0.1** Record train/test seasons in manifest + `ml_models` (V30) — *done 2026-07-26, full suite 820/820 green*
- [x] **0.2** In-sample badging on `/predictions/performance` — *done 2026-07-26, full suite 823/823 green (also fixed missing `ml_models` in test truncation list)*
- [x] **0.3** Identical-game-set head-to-head + ATS / O-U / CLV card — *done 2026-07-26, hand-computed ATS/OU/CLV fixtures incl. handicap sign-convention guard; full suite green*
- [x] **0.4** Walk-forward summary on the admin model table (promotion metric) — *done 2026-07-26, full suite 826/826 green*
- [ ] **0.5** Ops: flip default bundle to `baseline-plus` (server action — after deploying 0.1–0.4, retrain both bundles so manifests carry `train_seasons`, then `POST /admin/ml/models/baseline-plus/promote`)

### Phase 1 — Data hygiene (review #16–#19)
- [x] **1.1** Season-scoped rolling windows (Java + Python, retrain required) — *done 2026-07-26, full suite 827/827 + 6 pytest green; also scoped the game-page last-5 record*
- [x] **1.2** Non-D-I training-row filter (Python) — *done 2026-07-26; rule hardened to "no membership AND < 8 season games" after discovering 2021–2022 membership rows are missing for whole conferences (see Phase-1 notes)*
- [x] **1.3** Overtime handling: totals mask + spread winsorization (Python) — *done 2026-07-26; walk-forward uses the same fit-time targets; 13 pytest green; smoke: 315 OT games masked, 327 margins clipped*
- [x] **1.4** Investigate 2026 totals anomaly — *done 2026-07-26: no data bug; scoring-environment shift + stale training prior (see Phase-1 notes)*
- [ ] **1.5** Ops: retrain `baseline` + `baseline-plus`, rebuild evaluations (deploy-time, user action)

### Phase 2 — Feature set `prior-v3` (review #6, #9, #10)
- [ ] **2.1** Preseason-prior features (prev-season final β/θ + availability flags)
- [ ] **2.2** Complete four factors + extra box stats (orb/drb/ft_rate/fg3_rate…)
- [ ] **2.3** Season-snapshot features (`stddev_margin`, `rpi_owp`) + rolling-10
- [ ] **2.4** Massey-residual form feature (`*_massey_resid_l5`)
- [ ] **2.5** Register `prior-v3` set both sides; regenerate ONNX test fixtures
- [ ] **2.6** Ops: train `prior-v3` as CANDIDATE; compare walk-forward + shadow eval

### Phase 3 — Adjusted efficiency ratings (review #7)
- [ ] **3.1** `AdjustedEfficiencyRatingService` (ridge, off/def per team, daily series)
- [ ] **3.2** Orchestrator + admin wiring; backfill all seasons
- [ ] **3.3** Efficiency features + `eff-v4` set both sides; fixtures
- [ ] **3.4** Ops: train `eff-v4` as CANDIDATE; compare

### Phase 4 — Training methodology (review #8, #11–#15)
- [ ] **4.1** Residual spread head (`spread_target: residual_massey` manifest mode)
- [ ] **4.2** Derived win probability (`winprob_mode: derived`, fitted σ)
- [ ] **4.3** Hyperparameter tuning (`--tune N`, Optuna, walk-forward objective)
- [ ] **4.4** Season-recency sample weights + 2021 ablation
- [ ] **4.5** *(stretch)* Quantile spread/total heads + UI intervals
- [ ] **4.6** Ops: final champion selection on out-of-sample metrics only

---

## Conventions used throughout

- **Migrations**: next free version is **V30**; number sequentially as noted per step.
- **Java tests**: integration tests extend `BaseIntegrationTest` (singleton
  Testcontainers Postgres); respect the FK-safe `@BeforeEach` delete order
  (children before parents — see `project_test_cleanup_order` memory: evaluations/odds/
  stats → games → memberships → teams/conferences/seasons). Scraper-style tests mock
  `EspnApiClient` with `@MockBean`.
- **ONNX fixtures**: `src/test/resources/ml-models/` — tiny deterministic models built
  by `generate_fixtures.py` (input `float_input [None, N]`). Any feature-set change
  regenerates fixtures (`pip install onnx numpy && python generate_fixtures.py .`) and
  commits the binaries.
- **Python tests (new)**: create `scripts/tests/test_train_models.py` in step 1.1 (first
  Python-logic step); add `pytest` to `scripts/requirements.txt`. Pure-function tests
  only (feature fns, indexes, masks) — no DB. Run: `python -m pytest scripts/tests -q`.
- **Python smoke run** (steps touching the trainer): against local dev Postgres,
  ```bash
  python scripts/train_models.py --train-seasons 2026 --test-season 2026 \
      --db-url postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB \
      --output-dir /tmp/ml-smoke --model-name smoke-test
  ```
  (exercises the in-sample fallback path end-to-end; local DB has season 2026). Verify it
  completes, prints per-season kept/skipped counts, and writes 3 ONNX + `features.json`.
- **Registry parity rule**: every feature added to `FEATURE_REGISTRY`
  (`scripts/train_models.py`) gets a same-named entry in `MlFeatureRegistry`
  (`src/main/java/com/yotto/basketball/service/MlFeatureRegistry.java`), computed
  identically, appended (never reordered), and — if it needs box/RPI-style context —
  added to both `BOX_FEATURES`/`BOX_STAT_KEYS` (Python) and `EXTENDED_STAT_FEATURES`
  (Java). New sets are new named lists; existing sets are never edited.

---

# Phase 0 — Honest measurement

### Step 0.1 — Record train/test seasons in the manifest and registry

**Why first:** every other Phase-0 item consumes this metadata.

**Python** (`scripts/train_models.py`):
- Add to `features_meta`: `"train_seasons": sorted(set(train_seasons) - {test_season})`,
  `"test_season": test_season`. (Note: the deployed runs passed 2026 in
  `--train-seasons` *and* as `--test-season`; the training mask already excludes it —
  the manifest must record what the model actually trained on.)

**Migration** `V30__ml_model_train_seasons.sql`:
```sql
ALTER TABLE ml_models ADD COLUMN train_seasons VARCHAR(200);
ALTER TABLE ml_models ADD COLUMN test_season  INTEGER;
```

**Java**:
- `MlPredictionService.loadBundle(...)`: parse `train_seasons` / `test_season` from the
  manifest (default: empty/null for old bundles) into `MlModelStatus` (new fields).
- `MlModelRegistryService.syncFromBundles(...)` (the code path that upserts `ml_models`
  rows from loaded bundles): persist both columns; expose them on `MlModelView` and
  `ServingPlan` (add `Map<String, Set<Integer>> trainSeasonsBySlug()` or equivalent).
- `MlModel` entity: two new fields mapped to the columns.

**Tests**:
- Update `src/test/resources/ml-models/features.json` (add the two keys) and
  `generate_fixtures.py` to emit them.
- `MlPredictionServiceTest`: bundle with the keys → status exposes them; bundle without
  → empty (backward compat).
- Registry sync test (extend existing registry/`MlTrainingServiceTest` coverage):
  columns persisted on reload.

**Gate G.** Nothing user-visible yet; pure metadata.

---

### Step 0.2 — In-sample badging on the performance page

**Java** (`ModelPerformanceController`):
- Build `Map<String /*modelType*/, Set<Integer>> inSampleSeasons` from
  `mlModelRegistryService` (`ML:<slug>` → train seasons; empty for fixed models — the
  classical ratings and BOOK are always out-of-sample).
- Add to the model: `inSampleTypes` = set of model types for which the *selected* year is
  in-sample; for the ALL-seasons view, per-type the list of in-sample years.

**Template** (`templates/pages/model-performance.html`):
- Badge (`<span class="badge badge-warn" title="…">in-sample</span>`) next to the model
  name in the spread/total/win-prob tables when flagged; on ALL view, badge with tooltip
  "trained on 2021–2025; those seasons flatter this model". One shared fragment.
- A short explanatory note under the page header (one sentence + link to the review doc).

**Tests** (`ModelPerformanceControllerTest`):
- Seed an `ml_models` row with `train_seasons='2021,2022'`, evaluations in 2021 and 2026;
  assert badge markup present for year=2021, absent for year=2026, and absent for BOOK.

**Gate G.**

---

### Step 0.3 — Identical-game-set head-to-head + ATS / O-U / CLV card

The single most important new measurement. All comparisons pair each model's row with
the BOOK row **for the same game** (inner join on `game_id`), eliminating coverage bias.

**Repository** (`PredictionEvaluationRepository`) — new native-query projection
`VsBookMetrics` per model type (params: seasonId incl. `-1` = all, `from` date,
segment types — mirror the existing metric queries):
```sql
SELECT m.model_type,
       COUNT(*) FILTER (WHERE m.predicted_spread IS NOT NULL AND b.predicted_spread IS NOT NULL) AS n_common,
       AVG(ABS(m.spread_error)) FILTER (WHERE b.predicted_spread IS NOT NULL)  AS model_mae,
       AVG(ABS(b.spread_error)) FILTER (WHERE m.predicted_spread IS NOT NULL)  AS book_mae_common,
       -- ATS: model's side vs the closing number, pushes excluded
       AVG(CASE WHEN m.predicted_spread IS NULL OR b.predicted_spread IS NULL
                  OR m.predicted_spread = b.predicted_spread
                  OR m.actual_margin = b.predicted_spread THEN NULL
                WHEN (m.predicted_spread > b.predicted_spread) = (m.actual_margin > b.predicted_spread)
                THEN 1.0 ELSE 0.0 END)                                          AS ats_rate,
       -- same shape for totals → ou_rate
       ...
FROM prediction_evaluations m
JOIN prediction_evaluations b ON b.game_id = m.game_id AND b.model_type = 'BOOK'
JOIN games g ON g.id = m.game_id
WHERE m.model_type <> 'BOOK' AND (:seasonId = -1 OR m.season_id = :seasonId) ...
GROUP BY m.model_type
```
- **CLV** (closing-line value) needs the opening line, which lives only in
  `betting_odds.opening_spread` (handicap orientation — home margin is `−opening_spread`;
  see `project_betting_odds_sign_convention` memory). Join
  `betting_odds bo ON bo.game_id = m.game_id`; CLV rate =
  share of games where `sign(close_hm − open_hm) = sign(model_spread − open_hm)` among
  games where the line moved and the model disagreed with the open. Keep it in the same
  query.

**Controller/Template**:
- New "vs. Closing Line" card on `/predictions/performance`: one row per model —
  n(common), model MAE, book MAE (same games), Δ, ATS%, O-U%, CLV%, with the 52.4%
  breakeven line noted in the header. Reuse the in-sample badge from 0.2.

**Tests** (`ModelPerformanceControllerTest` + a focused repository test):
- Seed 4–5 games with hand-computed BOOK + `ML:test` rows covering: model right ATS,
  model wrong ATS, push (excluded), model==book (excluded), missing book row (excluded
  from n_common). Assert exact rates. Seed one game with opening≠closing spread for CLV
  sign check — this test is the guard on the handicap sign convention, which has already
  produced two shipped bugs.

**Gate G.**

---

### Step 0.4 — Walk-forward summary as the admin promotion metric

**Java**:
- `MlPredictionService.parseMetrics` / `MlModelStatus`: also parse the `walk_forward`
  array (season, spread_rmse, total_rmse, brier) already written by the trainer.
- Admin model-lifecycle table (`admin` fragment for ML models): add columns
  "WF spread RMSE (mean over held-out seasons)" and "WF Brier (mean)"; tooltip listing
  per-season values. Show test-season metrics as secondary, labelled "test (2026)".
- Persist the walk-forward block into `ml_models.metrics_json` if the sync path doesn't
  already carry it (it stores the trainer's metrics dict — verify and extend).

**Tests**: `MlStatusCardRenderingTest` / `AdminControllerTest`: fixture manifest with a
`walk_forward` array renders means; absent array renders "—".

**Gate G.**

---

### Step 0.5 — Ops: make `baseline-plus` the default

Server action, after 0.1–0.4 deploy: `POST /admin/ml/models/baseline-plus/promote`
(existing endpoint flips `is_default`). Verify `/predictions` shows Baseline Plus as the
headline model and `ML:baseline-plus` drives the by-conference card default.
No code; checklist item so it isn't forgotten.

---

# Phase 1 — Data hygiene

> **Ordering note:** 1.1 changes serving-side feature semantics for *already deployed*
> bundles (they were trained with cross-season windows). Land 1.1–1.3 together in one
> deploy, then immediately run step 1.5 (retrain both slugs + rebuild evaluations) so
> train/serve skew exists only for the minutes between deploy and retrain.

### Step 1.1 — Season-scoped rolling windows

**Java**:
- `GameRepository.findRecentFinalGamesForTeam(...)`: add `AND g.season.id = :seasonId`
  (new parameter; callers in `PredictionService` already know the game's season).
- Audit the other consumer of rolling form (`PredictionContext` build path) — one query,
  one call site.
- Effect: a team's first game of a season now has no rolling stats → suppliers return
  null → ML declines (consistent with the snapshot cold-start rule, which already
  requires a prior in-season game for ratings — the windows were the only cross-season
  leak).

**Python** (`train_models.py`):
- `build_team_game_index`: key by `(team_id, season_id)` (add `season_id` to the entry
  loop); `rolling_stats_fast` takes and passes `season_id`. `build_game_context` passes
  `int(row.season_id)`.
- Expect ratings-skips to rise by roughly one game per team per season (~6%); the
  `MAX_SKIP_PCT = 30` guard has headroom. Log line already reports per-season skips —
  eyeball in the smoke run.

**Tests**:
- Java: new `@DataJpaTest`-style or `BaseIntegrationTest` case in
  `PredictionServiceTest`: seed team with 5 FINAL games in season N−1 and 1 in season N;
  assert the season-N game after the first has a window of size 1 (not 5), and the first
  game of season N yields null rolling stats → no ML prediction.
- Python (`scripts/tests/test_train_models.py`, created in this step):
  `rolling_stats_fast` over a fabricated two-season index — window never crosses the
  season key; first-game-of-season returns all-None.

**Gate G** (incl. pytest + smoke run).

---

### Step 1.2 — Non-D-I training-row filter

**Scope decision (deliberate):** filter **training rows only** — games where either team
has no conference membership that season are dropped as *targets*. Rolling windows and
rating fits keep those games on **both** sides (Java serving can't cheaply exclude them
from windows, and trainer must mirror serving — consistency beats purity here). Revisit
excluding them from Massey/BT fits as a separate experiment later.

**Python**:
- `load_games`: add
  `LEFT JOIN conference_memberships hm ON hm.team_id = g.home_team_id AND hm.season_id = g.season_id`
  (and `am` for away), select `(hm.id IS NOT NULL AND am.id IS NOT NULL) AS both_d1`.
- In the feature loop: `if not row.both_d1: skip` with a new counter `skipped_non_d1`
  reported per season (excluded from the `MAX_SKIP_PCT` ratings guard — it's intentional).

**Tests**: pytest on the skip logic (feed rows with `both_d1` False/True through the loop
extraction — refactor the per-row decision into a small function `should_skip_row(row)`
to make it testable). Smoke run: verify the new counter appears and is plausible
(~1–3% of games).

**Gate G.**

---

### Step 1.3 — Overtime handling in training targets

**Python**:
- `load_games`: select `g.periods`. `is_ot = periods IS NOT NULL AND periods > 2`.
- **Totals head**: train on non-OT rows only — build `rows_is_ot` alongside the label
  lists; mask when fitting `total_model` (and the totals column of the walk-forward
  report). Evaluation stays on all games (apples-to-apples with BOOK).
- **Spread head**: winsorize the training target at ±30
  (`y_spread_train = np.clip(y_spread, -30, 30)` — training only, never eval), and keep
  OT rows (their small margins are real information about closeness).
- Log: OT-game count per season, clipped-margin count.

**Tests**: pytest for the mask/clip helpers (extract `prepare_targets(y_spread, y_total,
is_ot)` returning the fit-time arrays). Smoke run sanity.

**Gate G.**

---

### Step 1.4 — 2026 totals anomaly investigation (timeboxed)

Not a code change until the cause is known. Checks, in order, against the production DB:
1. Box coverage: `SELECT s.year, COUNT(*) FROM team_game_stats tgs JOIN games g … GROUP
   BY s.year` — compare games×2; look for a 2026 shortfall or mid-season gap.
2. Pace/efficiency snapshot coverage per month for 2026 in `team_stat_snapshots`
   (`stat_name IN ('pace','off_efficiency','def_efficiency')`).
3. Trainer log (`ml_training_runs.log_tail` for run 2): per-season box-skip counts —
   was 2026 quietly thinner?
4. League scoring environment: avg total by season (`AVG(home_score+away_score)`) — a
   real scoring shift hurts a model trained mostly on earlier seasons and is not a bug.
5. BOOK total MAE also rose in 2026 (13.37 vs ~12.7–13.0 prior) — quantify how much of
   the ML degradation is environment (book moved too) vs model-specific.

**Deliverable**: findings appended to this doc under Phase-1 notes + fix (if a data bug)
or a "no bug — environment/OT mix" conclusion. If a coverage bug is found, its fix gets
its own tests before 1.5.

---

### Step 1.5 — Ops: retrain + rebuild after hygiene

On the server, after deploying 1.1–1.3:
1. `POST /admin/ml/train` for `baseline` (feature set `baseline`) and `baseline-plus`
   (`pace-v2`), train seasons 2021–2026, test 2026 (completion auto-reloads + re-runs
   evaluation).
2. `POST /admin/ml/evaluate/rebuild` — full rebuild so classical-model and ML rows all
   reflect season-scoped windows.
3. Record before/after 2026 out-of-sample metrics in this doc. **Expectation:** modest
   improvement or neutral; the point is correctness, but if MAE *worsens* materially,
   stop and investigate before Phase 2.

---

# Phase 2 — Feature set `prior-v3`

All-new features follow the registry parity rule (§Conventions). Feature list additions
are append-only in `FEATURE_REGISTRY` / `MlFeatureRegistry`; the new set is
`"prior-v3": BASELINE_FEATURES + PACE_V2_EXTRAS + PRIOR_V3_EXTRAS`.

### Step 2.1 — Preseason-prior features

Features (6): `home_prev_beta`, `away_prev_beta`, `home_prev_theta`, `away_prev_theta`,
`home_prev_available`, `away_prev_available`. Prev-rating = the team's rating at the
**final snapshot date of season N−1** (MASSEY β, BRADLEY_TERRY θ); missing (newcomer /
first tracked season) ⇒ 0.0 with the availability flag 0.0 — an explicit, flagged
imputation (deliberate exception to no-imputation, mirrored exactly on both sides; the
flags let trees learn the blend by `games_played`).

**Python**: snapshots for season N−1 are already loaded when N−1 is in the season list;
extend `load_massey_snapshots`/`load_bt_snapshots` season range to
`min(all_seasons)-1 … max` and add a helper `final_rating(index, team, season_id-1?)` —
implement as: build once per (team, prior-season) a "last snapshot" map keyed by
`(team_id, season_year)` (need season year↔id mapping; `load_games` already returns
both; add a tiny `seasons` query for the prior year's id). Context keys
`h_prev_beta`, … set in `build_game_context`.

**Java**: new repository method
`TeamPowerRatingSnapshotRepository.findLatestForTeamSeasonModel(teamId, seasonId,
modelType)` (`ORDER BY snapshot_date DESC LIMIT 1`); `PredictionService` resolves the
previous `Season` by year−1 and populates four new `PredictionContext` fields (+ two
derived flags in `MlFeatureRegistry`). Cache per prediction batch the prior-season id
lookup. These are *not* in `EXTENDED_STAT_FEATURES` (they're rating-table lookups); add
a parallel `PRIOR_RATING_FEATURES` set so `PredictionService` only queries when the
manifest asks.

**Tests**:
- Java: `MlFeatureRegistryTest` (new unit test class — the registry currently has no
  dedicated unit test; create it this phase): supplier outputs for present/absent prior
  ratings (0.0 + flag semantics, *not* null).
- `PredictionServiceTest`: seed season N−1 snapshots; assert context carries the final
  N−1 value (not an earlier one, not a season-N value).
- Python: pytest for the prior-lookup helper (newcomer → (0.0, 0.0-flag)).

**Gate G.**

### Step 2.2 — Complete four factors + extra box stats

Features (12): `home/away_orb_pct`, `home/away_drb_pct`, `home/away_ft_rate`,
`home/away_opp_ft_rate`, `home/away_opp_tov_rate`, `home/away_fg3_rate`. All exist today
as `team_stat_snapshots` stat names (verified against `BoxScoreStatCalculator`:
`orb_pct`, `drb_pct`, `ft_rate`, `opp_ft_rate`, `opp_tov_rate`, `fg3_rate`).

**Python**: append to `BOX_STAT_KEYS` (they ride the existing box-index machinery) and
`BOX_FEATURES`. **Java**: `MlFeatureRegistry` suppliers via `homeBoxStats()/awayBoxStats()`
(the box-stat map is already keyed by stat name — confirm `PredictionService` loads *all*
stat names the manifest needs, not a hardcoded list; if hardcoded, drive the fetched
stat-name set from the manifest's feature list). Add to `EXTENDED_STAT_FEATURES`.

**Tests**: registry unit test rows; `PredictionServiceTest` seeds the new stat names;
pytest asserts `BOX_STAT_KEYS` ↔ `BOX_FEATURES` consistency and registry ordering
stability (golden list of the first 41 names — guards against accidental reordering).

**Gate G.**

### Step 2.3 — Season-snapshot features + rolling-10

Features (8): `home/away_stddev_margin`, `home/away_rpi_owp` (from
`team_season_stat_snapshots.stddevMargin` / `rpiOwp` — same point-in-time pattern as
RPI), `home/away_win_pct_l10`, `home/away_avg_margin_l10`.

**Python**: extend `load_rpi_snapshots` into a generalized
`load_season_snapshot_values(columns=[rpi, stddev_margin, rpi_owp])` (one query, three
indexes); rolling-10 = second call to `rolling_stats_fast(..., n=10)` (already
parameterized). **Java**: extend the `team_season_stat_snapshots` lookup in
`PredictionService` to also select the two columns; rolling-10 = widen the existing
`findRecentFinalGamesForTeam` fetch to `PageRequest.of(0, 10)` and compute both windows
from one list. l10 features return null before 2 games (same rule as l5's 1 — trainer
must match: `rolling_stats_fast` returns None at pos==0 only, so l10 with 1–9 games uses
what exists on both sides — verify equivalence explicitly in tests).

**Tests**: Java integration (seed 12 games; assert l5 vs l10 differ correctly; assert
l10 with 7 available games averages 7 on *both* sides — add matching pytest case with
identical numbers; use the same literal fixture values in both tests as a cross-language
contract check).

**Gate G.**

### Step 2.4 — Massey-residual form feature

Features (2): `home/away_massey_resid_l5` — mean over the last 5 in-season FINAL games
of (actual margin from the team's perspective − Massey-predicted margin), where the
prediction for each past game g uses the snapshot **strictly before g** and the HCA
param at g (0 if neutral) — i.e., what the rating *would have said then*: hot/cold vs.
rating, schedule-adjusted.

**Python**: computed inside the game loop from `team_game_index` +
`snapshot_index`/`param_index` (all present); needs home/away team ids per past game in
the index (already stored) plus neutral flags — extend the index entry tuple with
`neutral`. Null (skip-feature) when any of the 5 games lacks a prior snapshot.
**Java**: compute in `PredictionService` from the (now 10-game) recent-games list + a
batched snapshot query per past game date
(`findLatestForTeamSeasonModelBefore(teamId, seasonId, 'MASSEY', date)`) — 10 small
indexed lookups per prediction; acceptable at page scale, and the evaluation job already
runs per-game. Gate the queries on the manifest requesting the feature (same pattern as
`EXTENDED_STAT_FEATURES`; add to a `RESIDUAL_FEATURES` set).

**Tests**: this is the most skew-prone feature — write the **cross-language contract
test**: one hand-computed scenario (3 games, known snapshots/HCA, expected residual mean
to 6 decimals) implemented with identical literals in `MlFeatureRegistryTest`/
`PredictionServiceTest` and pytest.

**Gate G.**

### Step 2.5 — Register the set + fixtures

- Python: `PRIOR_V3_EXTRAS` list (28 names from 2.1–2.4), `FEATURE_SETS["prior-v3"]`.
- Java: nothing set-specific (manifest-driven) — but regenerate ONNX fixtures: extend
  `generate_fixtures.py` to also emit a `prior-v3`-width bundle
  (`src/test/resources/ml-models/prior-v3/`), and add an `MlPredictionServiceTest` case
  loading it (69-feature vector width, scoring passes, null-feature → no prediction).
- `ADMIN_MANUAL.md` + `CLAUDE.md` one-liner: document the new set.

**Gate G.**

### Step 2.6 — Ops: train and judge

`POST /admin/ml/train` `modelSlug=prior-v3, featureSet=prior-v3` → arrives as CANDIDATE
→ shadow-evaluated automatically. Judge on: (a) walk-forward means vs `baseline-plus`
(same trainer run output), (b) 2026 out-of-sample rows in `prediction_evaluations`,
(c) the new vs-book card. Promote to ACTIVE only if (a) and (b) both improve. Record
numbers here.

---

# Phase 3 — Adjusted offense/defense efficiency ratings

### Step 3.1 — `AdjustedEfficiencyRatingService`

New rating engine (pattern-match `MasseyRatingService`): for each game, **two
observations** — home points and away points per 100 possessions:
```
pts_home/poss_g × 100 ≈ μ + off_h − def_a + hca_o·home_ind
pts_away/poss_g × 100 ≈ μ + off_a − def_h − hca_d·home_ind   (start with a single shared hca term)
poss_g = mean of the two teams' (FGA − ORB + TOV + 0.44·FTA) from team_game_stats
```
Parameters: 2T team params (off_i, def_i) + unpenalized intercept μ + hca. Ridge λ on
team params only (config `yotto.models.adjusted-efficiency.lambda`, default 1.0).
Incremental accumulator A/b like Massey (system size 2T+2; T≈360 → 722² solve per date;
Cholesky with LU fallback — same as Massey). Daily series over game dates; skip games
lacking `team_game_stats` for either team (count + log; box coverage is near-complete
since May 2026 backfill).

Persistence: `team_power_rating_snapshots` with model_types **`ADJ_OFF`** and
**`ADJ_DEF`** (both VARCHAR(20)-safe; two rows per team-date — reuses all existing
snapshot infra); params `eff_intercept`, `eff_hca` in `power_model_param_snapshots`.
Ranks: rank by `off − def` under `ADJ_OFF` rows only (document: ADJ_DEF rank column
null or defensive rank — pick defensive rank, it's free and useful).

**Tests** (`AdjustedEfficiencyRatingServiceTest`, mirror `MasseyRatingServiceTest`):
- 3-team synthetic season with hand-solvable efficiencies (construct games with equal
  pace so the closed form is checkable; assert off/def recovery within tolerance, μ ≈
  league mean efficiency, snapshot-per-date counts, deletion/re-run idempotency).
- Missing-box-stats game skipped without failing the season.
- Incremental equivalence: full recompute == date-loop accumulation (pattern from
  `IncrementalRecalcEquivalenceTest`).

**Gate G.**

### Step 3.2 — Wiring + backfill

- `PowerRatingService.calculateAndStoreForSeason`: add the new service call (after the
  existing four).
- It thereby joins the orchestrator's stats-calc block and the admin "Power Ratings"
  action automatically — verify the stat-calc gate/watermark path re-runs it on new
  games (`ScrapeOrchestratorTest` addition with the service mocked).
- Ops: run Power Ratings for 2021–2026 on the server (per-season admin action) — the
  trainer needs the full history before Phase 3.4. Verify snapshot counts per season.

**Gate G.**

### Step 3.3 — Efficiency features + `eff-v4`

Features (8): `home/away_adj_off`, `home/away_adj_def`,
`adj_eff_matchup_home` (= off_h − def_a), `adj_eff_matchup_away` (= off_a − def_h),
`adj_eff_diff` (= matchup_home − matchup_away), `adj_eff_total`
(= matchup_home + matchup_away — the natural totals driver, pace-free; pace features
already exist to scale it).

- Python: snapshots load via the existing `team_power_rating_snapshots` queries (add the
  two model_types to `load_massey_snapshots`' IN-list or a third loader); context keys +
  registry entries; absence ⇒ ratings-skip? **No** — early seasons may lack box data ⇒
  treat like box features (skip-with-warn, `BOX_FEATURES` semantics) so `eff-v4`
  degrades per-game rather than failing runs.
- Java: `PredictionContext` fields + suppliers; gate the extra snapshot queries on the
  manifest (extend the feature-dependency sets).
- `FEATURE_SETS["eff-v4"] = prior-v3 + EFF_V4_EXTRAS`. Monotone constraints: add
  `adj_eff_diff` to `SPREAD_MONO_POS`/`WINPROB_MONO_POS`, `adj_eff_total` to
  `TOTAL_MONO_POS`.
- Fixtures: `eff-v4` bundle in test resources; registry ordering golden-list updated.

**Tests**: same trio as Phase 2 (registry unit rows, integration seeding `ADJ_OFF/DEF`
snapshots, pytest incl. monotone-string correctness for the new sets).

**Gate G.**

### Step 3.4 — Ops: train `eff-v4` CANDIDATE, judge as in 2.6. Record numbers.

---

# Phase 4 — Training methodology

### Step 4.1 — Residual spread head

Manifest-driven so old bundles stay valid:
- Python: `--spread-target {margin,residual_massey}` (default `margin`). For
  `residual_massey`: per-row baseline `m_g = beta_home − beta_away + hca_massey(g)`
  (hca from `param_index`, 0 if neutral — all already in the context); train the spread
  head on `y_spread − m`, and write `"spread_target": "residual_massey"` to the
  manifest. Walk-forward/metrics reconstruct full-scale predictions (`pred + m`) so
  reported RMSE stays comparable.
- Java: `MlModelStatus`/bundle parses `spread_target` (default `margin`);
  `MlPredictionService.predictSpread` adds the same `m` (from `PredictionContext` massey
  fields + `hca` param — already loaded) when mode is residual. Win-prob head input
  vector is unchanged (features are identical; only the regression target moved).
- Tests: Java — fixture bundle with `"spread_target": "residual_massey"` whose ONNX
  returns a constant; assert final spread = constant + massey baseline for a seeded
  game. Python — pytest on target construction/reconstruction round-trip.

**Gate G.** Train A/B candidates (same feature set ± residual) and compare walk-forward.

### Step 4.2 — Derived win probability

- Python: `--winprob-mode {classifier,derived}` (default `classifier`). Derived mode:
  fit σ = std of (y_spread − spread_pred) on the early-stopping validation slice; write
  `"winprob_mode": "derived", "margin_sigma": σ`; skip classifier training/export
  (manifest omits `winprob_model`); report Brier of Φ(spread_pred/σ) in walk-forward and
  test metrics so modes are comparable.
- Java: when `winprob_mode == derived`, `MlPredictionService` computes
  `Φ(spread/σ)` (use `org.apache.commons.math3.distribution.NormalDistribution` —
  commons-math3 already on the classpath) instead of running the classifier session;
  bundle loading tolerates the missing ONNX file in this mode (and only this mode).
- Tests: Java — derived-mode fixture bundle: probability matches Φ hand-calc; spread>0
  ⇔ prob>0.5 consistency assertion (the whole point). Python — pytest for σ fitting and
  Brier path.

**Gate G.** Ship as challenger; judge on walk-forward + shadow Brier/calibration curve.

### Step 4.3 — Hyperparameter tuning

- Python: `--tune N` (default 0 = off; requirements.txt + `Dockerfile.trainer` add
  `optuna`). Search space: `max_depth 3–8`, `learning_rate 0.01–0.1 (log)`,
  `min_child_weight 1–20`, `subsample/colsample 0.6–1.0`, `reg_alpha/lambda 0–5`.
  Objective: mean walk-forward spread RMSE (reuse `walk_forward_report` internals with
  trial params; totals and winprob tuned by the same trial's params — one search, not
  three). Fixed `random_state`; persist best params into the manifest
  (`"hyperparams": {...}`) and use them for the final fits.
- `trainer_service.py`: pass through an optional `tune` field on `POST /train`;
  `MlTrainingService`/admin form gets an optional "tuning trials" input (0 default —
  tuned runs take much longer; the always-on trainer already runs async with status
  polling).
- Tests: pytest — trial-param plumbing (objective callable returns finite value on a
  tiny synthetic matrix; best params serialize). Java — `MlTrainingServiceTest`: `tune`
  field serialized into the trainer request when set.

**Gate G.** One tuned run (~100 trials) per surviving feature set; record deltas.

### Step 4.4 — Season-recency weights + 2021 ablation

- Python: `--season-decay d` (default 1.0 = off): `sample_weight = d^(max_train_season −
  season_year)` on regressor/classifier fits (early-stopping fit included) — grid {1.0,
  0.9, 0.8, 0.7} via four candidate runs, judged on walk-forward.
- Ablation (no code): train best candidate with `--train-seasons 2022..2026`; if
  dropping 2021 is neutral-or-better, prefer the shorter window going forward.
- Tests: pytest — weight-vector construction (shape, monotonic by season, d=1 ⇒ all
  ones).

**Gate G.**

### Step 4.5 — *(stretch)* Quantile heads

Only after a champion stabilizes: `spread_q10/q90` via
`XGBRegressor(objective="reg:quantileerror", quantile_alpha=…)`, exported as extra ONNX
files listed in the manifest (`"spread_q10_model": …`); Java loads-if-present, exposes
interval on `MlPrediction` (nullable), UI shows "−3.2 ± (q-range)" on game pages.
Skippable without blocking 4.6.

### Step 4.6 — Ops: champion selection

Final comparison across surviving candidates strictly on: walk-forward means + 2026 (and
by then early-2027) out-of-sample `prediction_evaluations` + the vs-book card
(same-game MAE Δ, calibration). Promote one champion per the existing lifecycle; retire
the rest. Update `ADMIN_MANUAL.md` and the review doc's §5 table with the new honest
numbers.

---

## Phase notes / results log

**Phase 0 (2026-07-26)** — steps 0.1–0.4 implemented and green (826 tests, up from 808
before the phase). Notes:
- Trainer now writes `train_seasons`/`test_season` into `features.json`; the deployed
  bundles' manifests won't carry them until the next retrain, so badges appear only
  after retraining (step 1.5 covers this anyway).
- `ml_models` was missing from the integration-test truncation list
  (`SharedPostgresContainer`) since V27 — fixed in passing.
- The vs-book card's CLV/ATS SQL is guarded by a hand-computed integration fixture that
  encodes the handicap sign convention (`−opening_spread`/`−spread` → home margin).
- Admin model table now leads with walk-forward means (WF RMSE / WF Brier, per-season
  tooltip); single-test-season metrics are demoted to "Test" columns.
- 0.5 (default flip to `baseline-plus`) is a deploy-time server action, deliberately
  not automated here.

**Phase 1 (2026-07-26)** — steps 1.1–1.4 implemented and green (Java suite 827/827;
13 Python tests in the new `scripts/tests/` suite; smoke training runs verified against
the local 2026 DB). Notes:

- *1.1*: `findRecentFinalGamesForTeam` and the trainer's `team_game_index` are both
  season-scoped now; the game page's "last 5" record is scoped too. Local skip-rate
  impact was negligible (221 skips on 5,752 games, unchanged) because rating-snapshot
  availability was already the binding constraint for early-season games.
- *1.2 — membership data gap discovered*: a pure membership-based D-I filter would have
  discarded 775 games in 2021 and 1,002 in 2022 (~18–20%) because membership rows are
  missing for entire conferences in those seasons (59/51 teams incl. the whole Pac-12
  and C-USA — Washington St, Arizona, UCLA, USC, Oregon, Stanford, UTEP, UAB…). The
  shipped rule skips only teams with no membership AND < 8 season appearances
  (`MIN_D1_GAMES`); on production that matches just 8/2/0/1/0/0 games per season
  2021→2026 — true non-D-I games are nearly absent from this dataset. **Follow-up
  worth doing**: backfill 2021/2022 conference memberships (standings scrape) so
  per-conference views and RPI treat those seasons correctly.
- *1.3*: totals head now fits on regulation games only; spread target winsorized at
  ±30 at fit time; evaluation everywhere stays on raw actuals. Walk-forward report
  uses the same methodology.
- *1.4 — 2026 totals anomaly root-caused, no bug*: box-score coverage is 100% in every
  season and pace snapshots are continuous through 2026. The cause is a scoring-
  environment shift: league average total jumped 145.65 → 149.43 (+3.8 pts, and sd
  18.46 → 19.25) in 2026. Everyone's totals MAE rose (book 12.7 → 13.4), but the
  model also carries a stale level prior: `ML:baseline-plus` mean totals bias
  (actual − predicted) is **+3.68 pts in 2026** vs +1.1/+1.6 in its training seasons,
  while the book holds at +0.6. That bias accounts for almost exactly the model's
  ~0.5 MAE gap vs the book on totals. Direct ammunition for Phase 4: season-decay
  weights (4.4) and residual-style totals learning off `massey_gamma_sum` (4.1's
  totals analog), since the daily Massey-totals intercept already tracks the current
  environment.

**Phase 1 deploy checklist (step 1.5, user action)**: deploy → retrain `baseline` and
`baseline-plus` (2021–2026, test 2026) → `POST /admin/ml/evaluate/rebuild` → record
before/after 2026 out-of-sample metrics here. Also the moment to do step 0.5's
default flip. Baseline numbers to beat (2026, out-of-sample, pre-hygiene):
spread MAE 9.286 / RMSE 11.793, total MAE 13.893, Brier 0.1881, win acc 70.5%.
