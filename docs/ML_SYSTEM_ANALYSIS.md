# ML Prediction System — Analysis and Improvement Proposal

*Analysis date: 2026-07-09. Covers the Phase 2/2a ML system (`scripts/train_models.py`,
`MlPredictionService`, `PredictionService`) built March 2026 per `PREDICTION_SERVICE.md`.*

## 1. Executive summary

The ML system is architecturally sound (offline Python training → ONNX → in-JVM scoring,
hot reload, graceful degradation) but has stagnated relative to the rest of the codebase:

1. **The trainer is broken or silently degraded**: it queries `model_type = 'MASSEY_TOTAL'`
   but the Java services have written `MASSEY_TOTALS` since 2026-03-15. Depending on
   whether legacy rows survive in the DB, training either hard-fails or silently trains on
   frozen mid-March snapshots.
2. **The model predates the data pipeline.** Box-score scraping (May 2026) and the derived
   stats pipeline (25 four-factors/efficiency stats in `team_stat_snapshots`, RPI and
   z-scores in `team_season_stat_snapshots`) did not exist when the 27-feature vector was
   designed. None of it is used.
3. **Training requires SSH** (`scripts/retrain.sh` on the server). There is no admin-UI
   trigger, no run history, no scheduled retraining.
4. **Exactly one model set is supported** — one `/models` directory, hardcoded 27-feature
   `MlFeatureVector` record, `EXPECTED_FEATURES = 27` gate. Retraining overwrites the only
   model; there is no registry, versioning, or champion/challenger.
5. **No accuracy is visible to anyone.** The trainer writes a `metrics` block into
   `features.json` that the Java side never parses; the admin card shows only
   version/date/feature-count; there is no backtest endpoint, no performance page, and the
   spec'd predicted-vs-actual error display on the game page (§6.2) was never built.
   (Tracked in `docs/TODO.md:5` and `docs/punchlist.md:9`.)

Recommended order of work: **fix defects → build accuracy/evaluation infrastructure →
in-app training trigger → multi-model registry + feature expansion.** Measurement first:
without evaluation infrastructure there is no way to prove any model change is an
improvement.

## 2. Defects in the current implementation

### 2.1 `MASSEY_TOTAL` vs `MASSEY_TOTALS` (critical)

- `MasseyRatingService.MODEL_TYPE_TOTALS = "MASSEY_TOTALS"` (renamed from `MASSEY_TOTAL`
  on 2026-03-15, commit `ceae10a`/`810cdb1`).
- `scripts/train_models.py` still loads and looks up `'MASSEY_TOTAL'` (lines 134, 336-337).
- `build_feature_row()` returns `None` when the totals snapshot is missing, so affected
  games are **silently skipped**.
- Compounding: `calculateAndStoreForSeason()` deletes only `MASSEY`/`MASSEY_TOTALS` rows,
  so pre-rename `MASSEY_TOTAL` rows are never cleaned up. If they exist, the trainer
  trains on stale (frozen ~March 2026) gamma features for old dates and skips everything
  after; for new seasons it builds zero rows and exits with an error.

**Fix:** change the trainer to `MASSEY_TOTALS`; add a one-off cleanup
(`DELETE FROM team_power_rating_snapshots WHERE model_type = 'MASSEY_TOTAL'`, same for
params); add a trainer sanity check that per-season kept-row percentage is above a
threshold (e.g. fail if >30% skipped).

### 2.2 Train/serve skew on Weighted Bradley-Terry

- Trainer: skips any game missing a `BRADLEY_TERRY_W` snapshot (`build_feature_row`
  returns `None`).
- Server: `GameRatings.hasAll()` does **not** require weighted BT;
  `PredictionService.buildMlFeatureVector` imputes `0.0` for missing `θ_w`
  (`PredictionService.java:310-311`). The model never saw 0-imputed weighted-BT features
  in training. Make the two sides consistent (require BT_W in `hasAll()`, or impute
  identically in training).

### 2.3 Metrics computed but dropped

`train_models.py:701-710` writes `spread_rmse`, `spread_mae`, `total_rmse`, `total_mae`,
`brier_score`, `win_accuracy`, `in_sample` into `features.json`.
`MlPredictionService.loadModels()` reads only `features` (count) and `version`;
`MlModelStatus` has no metrics fields; `admin/fragments/ml-status.html` shows none of it.
Trivial quick win: parse the block, add to `MlModelStatus`, render on the admin card.

### 2.4 Smaller issues

- `MlModelStatus` javadoc says "should always be 24" — actual is 27 (stale).
- `features.json` `version` is `YYYY-MM-DD` only — two same-day trainings are
  indistinguishable. Use a timestamp + git-style short hash.
- `MlPrediction.featuresComplete` is hardcoded `true`; the spec's softer cold-start
  flagging (§4.6) was never implemented. Spec says predict only at ≥5 games; the
  implementation predicts with a 1-game rolling window (trainer and server are at least
  consistent with each other).
- Rolling last-5 windows cross season boundaries on both sides (no season filter in
  `findRecentFinalGamesForTeam` or the trainer's `team_game_index`). Consistent, but the
  first games of a season use November-vs-last-March "form"; consider season-scoping.
- Zero test coverage of the ONNX path: no test touches `MlPredictionService`, `.onnx`,
  or `features.json`. Add a smoke test with tiny committed ONNX fixtures (load, score,
  reload, missing-file disable).
- Trainer computes `massey_hca` but never uses it (dead code).
- The spec'd game-detail prediction section with per-model error (§6.2) is unimplemented:
  `GameDetailController` puts `prediction` in the model but `game-detail.html` never
  renders a forecast section; `actualMargin/actualTotal` are only used for book-line
  cover checks.

## 3. Model improvement opportunities

### 3.1 Features now available (all point-in-time snapshot tables — leakage-free joins)

The current 27 features use only power ratings + rolling last-5 from `games`. Unused:

| Source | What it adds |
|---|---|
| `team_stat_snapshots` (long, 25 stats from `BoxScoreStatCalculator`) | `off_efficiency`, `def_efficiency`, `pace`, four factors (own + opp): `efg_pct`, `tov_rate`, `orb_pct`/`drb_pct`, `ft_rate`; `ts_pct`, `fg3_rate`, `ast_to_ratio`, `stl_rate`, `blk_pct`, … each with `zscore`, `confZscore`, `rank`, `gamesPlayed` |
| `team_season_stat_snapshots` (wide, daily) | `rpi` (+ `rpiOwp`/`rpiOowp` = SOS proxies), `meanMargin`/`stddevMargin`, `correlationPts`, rolling wins/points, league + conference z-scores |
| `season_population_stats` | per-date league/conference distributions for normalization |
| `betting_odds` | closing + opening spread/OU/moneylines — best used as a **benchmark** (and optionally a feature for a separate "market-aware" model; keep the main model market-free so it can predict games without lines) |

Highest-expected-value additions:
- **Totals model:** `pace_home`, `pace_away`, `pace_sum`, offensive/defensive efficiency —
  the spec itself predicted the biggest ML gain (~2-4 RMSE pts) would come from pace-type
  features; none were available then. This is the single biggest modeling win available.
- **Spread/win-prob:** efficiency differentials, four-factor differentials (z-scored),
  RPI/SOS, `stddevMargin` (consistency), rolling-10 window alongside rolling-5,
  Massey residual over last 5 (actual − predicted margin = hot/cold vs. rating).
- Use the z-scored variants where possible — they are already opponent-pool-normalized
  per date.

Caveat: box-score coverage must be verified per season
(`SELECT s.year, count(*) FROM team_game_stats … GROUP BY year`) before pooling training
seasons; a coverage report should be part of trainer output.

### 3.2 Training methodology

- **Walk-forward validation** instead of a single held-out season: train on seasons
  1..k, validate on k+1, roll forward; report mean ± spread. One test season is a single
  noisy sample.
- **Benchmarks in every training report:** Massey-only baseline and the book closing line
  (spread MAE vs `betting_odds.spread`, Brier vs moneyline-implied probability). "Beat
  Massey" is the bar for shipping; "distance to the closing line" is the honest ceiling.
- **Early stopping** on a validation fold instead of fixed `n_estimators=300`;
  basic hyperparameter sweep (depth, lr, min_child_weight) — currently the spec's
  "reasonable starting values" have never been tuned.
- **Monotonic constraints** (XGBoost `monotone_constraints`) on `massey_beta_diff`,
  `bt_logodds` — guarantees sane behavior (better rating diff ⇒ better prediction) and
  helps small-data generalization.
- Keep sigmoid calibration; add a calibration-curve artifact to the report (deciles are
  already printed; persist them).
- Optional: quantile-regression heads for spread intervals ("Duke -3.2 ± 9").

## 4. Training without a shell (admin-triggered)

Options considered:

| Option | Verdict |
|---|---|
| A. Trainer as small always-on HTTP service on the backend network | **Recommended** |
| B. App shells out to `docker compose run trainer` (docker socket mount) | Rejected — root-equivalent socket in app container |
| C. Port training to Java (XGBoost4J/Tribuo) | Rejected — large rewrite, loses sklearn/skl2onnx ecosystem |
| D. Host cron/systemd timer | Cheap fallback; scheduled but not on-demand, no UI feedback |

**Recommendation (A):** convert `Dockerfile.trainer` into a long-running FastAPI (or
stdlib http.server) service exposing `POST /train` + `GET /status/{run_id}` on the
internal `backend` network only (never via nginx). It already has DB credentials and the
`model_data` volume. The Spring app gets:

- `POST /admin/ml/train` → calls trainer, records a row in a new `ml_training_runs`
  table (mirror the `ScrapeBatch` pattern: status RUNNING/COMPLETED/FAILED, started/finished,
  seasons, metrics JSON, log tail).
- HTMX-polled training card on `/admin` (same pattern as scrape history), showing live
  status and, on completion, the metrics + a **Reload** or auto-reload step.
- Optional auto-trigger: `ScrapeOrchestrator.runCalculations()` ends at
  `statCalcGateService.recordRun(...)` (`ScrapeOrchestrator.java:164`) — the one place
  that knows fresh snapshots were just written. A config-gated weekly auto-retrain hook
  there (skip when gate says SKIP) removes the human from the loop entirely.
- `retrain.sh` remains as a fallback; idle Python service cost is ~50 MB RAM.

## 5. Multiple models

Goal (per `docs/punchlist.md`: "Allow multiple ML models", "Model X / Model Y"):
several **distinct, concurrently-served models** — different feature sets, different
algorithms, different training windows — each independently trainable, evaluated, and
visible to users, with one designated the site default. Not just versioning of one model.

### 5.1 What "a model" becomes

A model is a named bundle: `slug` + manifest + ONNX files. Examples that can coexist:

| Slug | Feature set | Notes |
|---|---|---|
| `baseline-v1` | current 27 features | today's model, kept as-is |
| `pace-v2` | 27 + pace/efficiency/four-factor features | needs box-score coverage |
| `market-aware` | pace-v2 + opening line features | only predicts games with odds |
| `logit-simple` | ratings only, plain logistic | interpretable reference model |

### 5.2 Two enabling changes

1. **Directory-per-model registry.** `/models/<slug>/` each containing
   `features.json` + the three ONNX files. `features.json` is already self-describing
   (feature list, version, metrics) — it becomes the model manifest (add `slug`,
   `displayName`, `description`). A DB table `ml_models` (slug, display name, trained_at,
   metrics JSON, status: `ACTIVE`/`CANDIDATE`/`RETIRED`, `is_default`) indexes them.
   Trainer takes `--model-name` (+ optional `--feature-set`); retraining a slug writes a
   new version subdir and flips a `current` symlink (keep last N for rollback).
2. **Feature-registry-driven vectors.** Replace the hardcoded `MlFeatureVector` record /
   `toFloatArray()` / `EXPECTED_FEATURES` with a registry of named feature suppliers
   (`Map<String, Function<PredictionContext, Double>>`), mirroring the
   `BoxScoreStatCalculator` registry pattern. At predict time, Java assembles each
   model's input vector **from that model's own `features.json` order** — so two models
   with different feature sets are served by the same code path. Unknown feature name in
   a manifest ⇒ model loads disabled with a clear status message. The Python trainer
   mirrors this: named feature functions, `--feature-set` selects a list.

This second change is what makes multi-model *cheap*: without it, every model shape
requires a new record + array builder + constant; with it, a new model is a trainer run
plus a manifest.

### 5.3 Serving and API shape

- `MlPredictionService` → holds `Map<slug, ModelBundle>` (bundle = 3 sessions + manifest
  + its ordered feature list); per-bundle read/write locks; `reload(slug)` and
  `reloadAll()`.
- `PredictionResult.ml` keeps the **default** model's output (backward compatible for
  existing UI/JS); add `mlModels: Map<slug, MlPrediction>` carrying every `ACTIVE` model.
  `CANDIDATE` models are scored too but only persisted to `prediction_evaluations`
  (shadow mode), not returned publicly.
- `GET /api/ml/models` — list models with status + latest metrics.
- Feature-availability differences are per-model: e.g. `market-aware` returns `null` for
  a game with no odds while `pace-v2` still predicts. The per-model `MlPrediction` being
  nullable already models this.

### 5.4 UI

- **Predictions page:** default model drives the main numbers (unchanged layout); a model
  selector (HTMX swap, persisted per-user via `UserPreference`) lets a user switch which
  model's spread/total/win-prob is displayed.
- **Game detail:** the §6.2 prediction table grows one row per active model — this is
  where "Model X vs Model Y" disagreement becomes visible and interesting.
- **Performance page (§6):** per-model columns side by side — the whole point of running
  multiple models is comparing them on the same evaluation table.
- **Admin:** ML card becomes a table (one row per model: status, version, metrics,
  reload/train/promote/retire buttons). "Promote to default" flips `is_default`.

### 5.5 Lifecycle (champion/challenger)

1. Train a new slug (`CANDIDATE`) from the admin UI (§4) or `retrain.sh --model-name`.
2. It immediately shadow-scores: the evaluation job (§6) backtests it over past FINAL
   games and keeps scoring new ones — because inputs are snapshot time series, a
   brand-new candidate gets a full-history backtest on day one.
3. Admin compares candidates vs champion on the performance page; promotes when a
   candidate wins on the metrics that matter; retires losers (files kept, unloaded).

## 6. Accuracy visible to users

**Key insight:** every rating input is a daily snapshot time series, and evaluation always
uses `snapshot_date < game_date` — so every model (including newly trained candidates) can
be backtested over **all past FINAL games retroactively, without having stored predictions
at game time and without leakage.** The evaluation infrastructure is therefore cheap:

1. **`prediction_evaluations` table** — one row per (game, model): predicted spread /
   total / home-win-prob, book closing spread/OU at evaluation time, and derived errors.
   Populated by an evaluation pass hooked into the stats-calc block (after
   `runCalculations`) or the nightly maintenance job; incremental via the same
   watermark idea (evaluate FINAL games not yet evaluated). Backfill once for history.
2. **Public model-performance page** (`/models` or `/predictions/performance`), per model
   and per season with a last-30-days toggle:
   - Spread: MAE, RMSE, straight-up winner accuracy, error-distribution histogram
   - Total: MAE, RMSE, over/under bias
   - Win prob: Brier score, log-loss, **calibration chart** (predicted decile vs actual)
   - **Benchmarks in the same table:** Massey baseline, book closing line, "home team
     always wins" — honesty about where the model stands
   - ATS record vs closing line (with the existing "not betting advice" disclaimer)
3. **Game detail page:** implement spec §6.2 — for FINAL games show each model's
   prediction next to the actual with the signed error.
4. **Predictions page:** small "model accuracy this season: MAE X pts, winners Y%" strip
   linking to the performance page.
5. **Admin quick win (independent of all the above):** surface the existing
   `features.json` metrics block on the ML status card.

## 7. Suggested roadmap

| Phase | Contents | Effort |
|---|---|---|
| **0 — Fix** | §2.1 model-type bug + stale-row cleanup, §2.2 BT_W skew, admin card metrics, ONNX smoke test, trainer skip-rate guard | Small |
| **1 — Measure** | `prediction_evaluations` + evaluation job + public performance page + game-detail error rows | Medium |
| **2 — Operate** | Trainer HTTP service, `ml_training_runs`, admin train button + HTMX status, optional post-scrape auto-retrain | Medium |
| **3 — Improve/scale** | Feature-supplier registry (Java + Python), box-score/pace/RPI features, walk-forward CV + tuning + benchmarks, model registry + champion/challenger | Large |

Phase 1 before Phase 3 deliberately: the performance page is both the user-facing feature
and the yardstick that makes every subsequent model change verifiable.

**Implementation status:** all four phases shipped 2026-07-10. Phase 0 (`9b37aec`…`5fa4034`),
Phase 1 (`59fed04`), Phase 2 (`7235174`), Phase 3: feature-supplier registries on both
sides (`MlFeatureRegistry` ↔ `FEATURE_REGISTRY`), pace-v2 feature set (41 features incl.
box-score/RPI), walk-forward + early stopping + monotonic constraints in the trainer,
`ml_models` registry (V27) with ACTIVE/CANDIDATE/RETIRED + default, per-slug `ML:<slug>`
evaluation rows with shadow candidates, and the admin model-lifecycle table.
Remaining follow-ups: per-user model selector on the predictions page (UserPreference),
scheduled auto-retraining.
