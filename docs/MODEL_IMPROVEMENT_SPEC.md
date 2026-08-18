# Model Improvement Spec

Testable specification for the Tier 1–2 work in [IMPROVE_MODELLING.md](IMPROVE_MODELLING.md).
Companion implementation plan: [MODEL_IMPROVEMENT_PLAN.md](MODEL_IMPROVEMENT_PLAN.md).

## Goals

1. Score every probability-producing model with **log loss**, next to the closing line, on
   `/predictions/performance`.
2. Give every **margin-first** model a win probability via the normal-margin conversion
   Φ(μ/σ), so it participates in probability scoring.
3. Promote the adjusted-efficiency ratings to a **first-class prediction model** (`ADJ_EFF`)
   with spread, total, and win probability, evaluated like every other model.
4. Build a **walk-forward λ tuning harness** for the adjusted-efficiency ridge penalty and
   make λ configurable.

## Non-goals (this round)

- Hierarchical Bayesian ratings, LRMC (Tier 3 — future spec).
- Any change to ML feature sets or `scripts/train_models.py` feature registries
  (`MlFeatureRegistryTest`'s golden list must not change).
- Automatic λ promotion — the harness produces a report; an operator changes the config.

## Shared definitions

These are normative for every workstream below.

**D1 — Log loss.** For a prediction p of home-team win probability and outcome
y ∈ {0, 1} (1 = home won):

```
logloss = −[ y·ln(p̃) + (1−y)·ln(1−p̃) ]        where p̃ = clamp(p, ε, 1−ε), ε = 10⁻⁶
```

Natural log. The clamp guarantees finiteness when a model emits exactly 0.0 or 1.0.
Aggregate log loss is the plain mean over qualifying rows (rows with a non-null
`predicted_home_win_prob`), never a weighted or trimmed mean.

**D2 — Margin→probability conversion.** For an expected home margin μ (points):

```
P(home win) = Φ(μ / σ)
```

where Φ is the standard normal CDF and σ is the configured margin standard deviation.

**D3 — σ configuration.** New property `app.prediction.margin-sigma`, double,
**default 11.0**. All Φ conversions (Massey, ADJ_EFF, BOOK fallback, tuning harness) read
this single value. One new pure utility (`WinProbability.fromMargin(margin, sigma)`)
implements D2 via Apache commons-math (already a dependency); no other class computes Φ.

**D4 — Leakage rule.** Every prediction evaluated for a game on date d may use only
snapshots and parameters dated **strictly before d** (the existing
`findLatestBefore(..., cutoff)` convention). This applies to the tuning harness too: the
solution used to predict date d's games must be the one fit through date d−1.

---

## W1 — Log loss on the performance page

| ID | Requirement |
|----|-------------|
| W1-1 | `PredictionEvaluationRepository.ProbMetrics` gains `getLogLoss()`, computed per D1 in SQL (`ln`, clamp via `greatest`/`least`), under the same filters as the existing Brier expression. |
| W1-2 | `MonthlyMetrics` gains `getLogLoss()` with the same expression, so the month-by-month chart can plot it. |
| W1-3 | The win-probability table on `/predictions/performance` shows a **Log Loss** column between N and Brier, formatted to 4 decimal places, sorted rows unchanged. |
| W1-4 | The monthly Chart.js block offers log loss as a plottable series alongside Brier/MAE (same data payload, no new endpoint). |
| W1-5 | Rows where `predicted_home_win_prob` is null are excluded from N, log loss, and Brier alike (already true for Brier; must remain true). |

**Acceptance tests**

- `PredictionEvaluationRepositoryTest` (new, integration): insert evaluation rows with known
  probs/outcomes; assert log loss equals the hand-computed mean within 1e-9. Include a row
  with p = 1.0 and a losing home team; assert the aggregate is finite and equals
  −ln(10⁻⁶) contribution for that row.
- MockMvc: `/predictions/performance` renders the Log Loss column when prob rows exist.

**Sanity gates** (manual, post-deploy, recorded in the punchlist): BOOK log loss lands in
0.54–0.60 on a full past season; any model below 0.54 triggers a leakage investigation, not
a celebration.

---

## W2 — Win probabilities for margin models

| ID | Requirement |
|----|-------------|
| W2-1 | `PredictionResult.MasseyPrediction` gains `homeWinProbability` = Φ(spread/σ) per D2/D3, populated whenever the spread is. (Additive API change; JSON consumers unaffected.) |
| W2-2 | `PredictionEvaluationService` writes that probability into the MASSEY row's `predicted_home_win_prob`. |
| W2-3 | BOOK fallback: when the moneyline pair is missing but a closing spread exists, the BOOK row's probability is Φ(−spread/σ) (handicap orientation per the existing sign convention). De-vigged moneylines remain preferred when present. |
| W2-4 | The MASSEY model consequently appears in the probability table and calibration chart. Display names/ordering unchanged otherwise. |
| W2-5 | Backfill is by operator rebuild (`POST /admin/ml/evaluate/rebuild`); incremental evaluation must not silently leave old MASSEY rows probability-less forever — document the rebuild step in ADMIN_MANUAL.md. |

**Acceptance tests**

- Unit: `WinProbability.fromMargin(0, σ) = 0.5`; `fromMargin(11, 11) ≈ 0.8413` (±1e-4);
  strictly increasing in margin; symmetric (`fromMargin(−μ)` = 1 − `fromMargin(μ)`).
- Unit: BOOK row uses moneyline prob when both lines present; falls back to Φ(−spread/σ)
  when either moneyline is null and spread is present; prob null when neither source exists.
- Integration: after evaluation, the MASSEY row for a game with Massey snapshots has a
  non-null prob equal to Φ(spread/σ).

---

## W3 — ADJ_EFF as a first-class prediction model

New evaluated model type **`ADJ_EFF`** (display name "Adjusted Efficiency"), built from the
existing `ADJ_OFF`/`ADJ_DEF` snapshots plus a new adjusted-tempo fit.

### Tempo model

| ID | Requirement |
|----|-------------|
| W3-1 | `AdjustedEfficiencyRatingService` additionally fits per-team tempo: for each fit game, `possessions ≈ τ_home + τ_away + ν` with team params τ ridge-penalized (same λ as the efficiency fit) and unpenalized intercept ν. Snapshots persist as model type `ADJ_TEMPO` (ratings) and param `tempo_intercept` (on `ADJ_TEMPO`), same per-date incremental structure and watermark semantics as `ADJ_OFF`/`ADJ_DEF`. |
| W3-2 | Games skipped for missing box scores are skipped by the tempo fit too (same fit-game set). |

### Prediction

| ID | Requirement |
|----|-------------|
| W3-3 | For a game on date d, using latest-before-d snapshots (D4): expected possessions `poss = ν + τ_h + τ_a`; per-100 expected scores `eh = μ + off_h − def_a + η·ind`, `ea = μ + off_a − def_h − η·ind` (ind = 0 on neutral floors, 1 otherwise — matching the fit's convention); then **spread** = (eh − ea)·poss/100, **total** = (eh + ea)·poss/100, **win prob** = Φ(spread/σ). |
| W3-4 | The prediction exists only when all of: both teams' `ADJ_OFF` and `ADJ_DEF`, both `ADJ_TEMPO`, and the `eff_intercept`/`eff_hca`/`tempo_intercept` params have latest-before-d values. No imputation. |
| W3-5 | `PredictionResult` gains an `AdjEfficiencyPrediction` block (spread, total, homeWinProbability, gamesPlayed per side, snapshot date), populated by `PredictionService`. |
| W3-6 | `PredictionEvaluationService` writes an `ADJ_EFF` row (spread + total + prob) per evaluable game. `ModelPerformanceController` display names/order include it (after the Bradley-Terry entries, before BOOK). |
| W3-7 | `ADJ_EFF` joins the by-conference model dropdown (`spreadModelOptions`). Game-page/prediction-UI display of the new block is **optional** in this round; the performance page is required. |

**Acceptance tests**

- Tempo fit unit/integration: synthetic season where every game has exactly P possessions →
  all τ shrink toward 0 and ν ≈ P (within ridge tolerance); a fast-pace team's τ is positive.
- Predictor: construct snapshots/params by hand → assert spread/total/prob match the W3-3
  formulas exactly. Neutral-site game drops the 2η term.
- Missing-data: absent `ADJ_TEMPO` for one team → no `ADJ_EFF` row for that game, other
  models unaffected.
- Evaluation integration: a season with box scores produces `ADJ_EFF` rows; a season with no
  box scores produces none and no errors.
- Performance page renders "Adjusted Efficiency" rows in spread, total, and prob tables.

---

## W4 — Walk-forward λ tuning harness

| ID | Requirement |
|----|-------------|
| W4-1 | `AdjustedEfficiencyRatingService.LAMBDA` becomes config `app.ratings.adj-efficiency.lambda` (double, default 1.0). The efficiency and tempo fits both read it. |
| W4-2 | New `AdjEfficiencyTuningService.runSweep(grid)`: for each λ in the grid and each season with box scores, replay the season date-by-date **in memory** (no snapshot writes): predict each date's fit-games with the solution through the prior date (D4), then absorb the date and re-solve. Per game it records predicted spread (W3-3 formulas, tempo fit at the same λ) vs. actual margin. |
| W4-3 | Per (λ, season) the report contains: n games predicted, spread MAE, log loss (per D1, prob per D2), and the empirical residual σ (stddev of actual − predicted margin). Plus a per-λ all-season aggregate. Games before both teams have a prior-date rating are skipped, not imputed. |
| W4-4 | Default grid `0.25, 0.5, 1, 2, 4, 8, 16, 32` (config `app.ratings.adj-efficiency.lambda-grid`, comma-separated doubles). |
| W4-5 | Trigger: `POST /admin/ml/adj-lambda-tune` (ADMIN), async, one run at a time (reject a second concurrent run). Runs persist to new table `rating_tuning_runs` (migration **V32**): id, model_type, status (RUNNING/COMPLETED/FAILED), started_at, finished_at, params (jsonb: grid, sigma), results (jsonb: the W4-3 report), error. |
| W4-6 | Admin dashboard shows run history (HTMX fragment, same pattern as training-status): per run, per-λ aggregate log loss + MAE, best λ highlighted (lowest aggregate log loss). |
| W4-7 | Promotion is manual: operator sets `app.ratings.adj-efficiency.lambda` (documented in ADMIN_MANUAL.md). The sweep never mutates ratings, snapshots, or config. |

**Acceptance tests**

- Determinism: same fixture data + grid → identical results jsonb (no wall-clock or RNG
  dependence in the report body).
- Leakage guard: a game on the first fit date (no prior solution) is excluded; a fixture
  where a team's only games are on one date yields no predictions for that date's games.
- Harness math: tiny fixture (4 teams, hand-checkable) → predicted spreads for date 2 match
  hand-computed values from the date-1 solve at the given λ.
- Concurrency: second POST while RUNNING → 409 (or redirect+flash per admin convention),
  one row only.
- End-to-end smoke (integration): sweep over a small synthetic season completes, run row
  COMPLETED, per-λ metrics present for every grid point.

**Explicit non-requirement:** performance optimization (warm starts, rank-1 updates). The
sweep is O(dates × grid × solve); at D1 scale (~730 params, ~150 dates, 8 λ) this is minutes
per season on the async executor, which is acceptable for an operator-triggered job.

---

## W5 — Winprob-mode shootout (process, not code)

With W1 in place: train two candidate bundles from the same feature set differing only in
`winprobMode` (`classifier` vs `derived`), let both shadow-evaluate as CANDIDATEs, and
compare log loss on out-of-sample seasons on the performance page. Adopt the winner as the
default going forward. Deliverable: a short procedure note in ADMIN_MANUAL.md; no code.

---

## Configuration summary

| Property | Default | Used by |
|----------|---------|---------|
| `app.prediction.margin-sigma` | `11.0` | W2 Φ conversions, W3 predictor, W4 harness |
| `app.ratings.adj-efficiency.lambda` | `1.0` | W3 fits (efficiency + tempo) |
| `app.ratings.adj-efficiency.lambda-grid` | `0.25,0.5,1,2,4,8,16,32` | W4 sweep |

## Operational rollout

1. Deploy W1+W2 → run `POST /admin/ml/evaluate/rebuild` (backfills MASSEY probs, BOOK
   fallback probs, log-loss-ready rows). Record the BOOK log-loss sanity gate.
2. Deploy W3 → next scrape's stats block (or a manual full recalc) writes `ADJ_TEMPO`
   snapshots for all seasons; then rebuild evaluations again to backfill `ADJ_EFF` rows.
3. Run the W4 sweep; if the best λ beats λ=1.0 by a stable margin across seasons, set the
   property, full-recalc adjusted efficiencies, rebuild evaluations, and re-check.
4. W5 shootout whenever the next training run is scheduled.
