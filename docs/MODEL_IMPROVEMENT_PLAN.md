# Model Improvement — Implementation Plan

Execution plan for [MODEL_IMPROVEMENT_SPEC.md](MODEL_IMPROVEMENT_SPEC.md). Four phases,
each independently deployable and verifiable before the next starts. Requirement IDs
(W1-1 …) refer to the spec.

## Phase 1 — Measurement: log loss + margin-model win probabilities (W1, W2)

Smallest phase, unlocks judging everything else.

1. **`WinProbability` utility** (`service/` or `util/`): `fromMargin(double margin, double sigma)`
   via commons-math `NormalDistribution` (or `Erf`). Pure, static, unit-tested first (W2 tests).
2. **Config**: `app.prediction.margin-sigma` (default 11.0) — a small `@ConfigurationProperties`
   or `@Value` on the consumers; `.env.example` untouched (not a secret).
3. **`PredictionService`**: `MasseyPrediction` gains `homeWinProbability`; `toMassey(...)`
   fills it via the utility (W2-1). Check templates that render MasseyPrediction still bind
   (record component addition is source-compatible; recompile catches usage by position).
4. **`PredictionEvaluationService`**: MASSEY row carries the prob (W2-2); `bookRow` falls back
   to `Φ(−spread/σ)` when `impliedHomeWinProb` returns null and spread is present (W2-3).
5. **`PredictionEvaluationRepository`**: log-loss expression added to `probMetrics` and
   `monthlyMetrics` (W1-1, W1-2). SQL sketch:
   `avg(-ln(CASE WHEN pe.home_won THEN x ELSE 1 - x END))` with
   `x = greatest(1e-6, least(1 - 1e-6, pe.predicted_home_win_prob))`.
6. **`ModelPerformanceController` + `pages/model-performance.html`**: Log Loss column in the
   prob table; log loss added to `MonthlyPoint` and the Chart.js payload (W1-3, W1-4).
7. **ADMIN_MANUAL.md**: rebuild step + BOOK log-loss sanity gate (W2-5, W1 gates).

**Tests** (Phase-1 exit): `WinProbabilityTest` (unit); new
`PredictionEvaluationRepositoryTest` log-loss integration test incl. clamp row;
`PredictionEvaluationServiceTest` additions for MASSEY prob + BOOK fallback;
`ModelPerformanceControllerTest` (or MockMvc test in the existing suite) for the column.
Full `./mvnw test` green.

**Deploy + operate**: `./scripts/deploy.sh`, then `POST /admin/ml/evaluate/rebuild`.
Record BOOK and per-model log loss for the two most recent complete seasons in
`docs/punchlist.md` — these are the baseline numbers every later phase is judged against.

**Risks**: none structural. Watch `DailyRecord` SU semantics — MASSEY rows now have probs,
and its prob-first branch takes over from the spread branch; the two agree by monotonicity
of Φ (prob ≥ 0.5 ⇔ spread ≥ 0), so no behavior change, but the test suite should pin that.

## Phase 2 — ADJ_EFF first-class model (W3)

1. **Tempo fit** in `AdjustedEfficiencyRatingService` (W3-1, W3-2): second accumulator set
   (T team columns + intercept) filled in the same game loop; per-date solve alongside the
   efficiency solve; snapshots `ADJ_TEMPO` + param `tempo_intercept`. Reuses the existing
   delete-by-model-type watermark pattern — add the new model type to both delete branches.
   No schema change (snapshot tables are model-type-keyed).
2. **Sigma/λ plumbing**: `LAMBDA` → `app.ratings.adj-efficiency.lambda` (W4-1 lands here
   since this class is being touched anyway).
3. **Predictor** (W3-3..5): `PredictionService.fetchGameRatings` extended (or a parallel
   fetch) for the six ADJ snapshots + three params; `toAdjEfficiency(...)` builder;
   `PredictionResult.AdjEfficiencyPrediction` record added to the result (additive).
4. **Evaluation** (W3-6): `ADJ_EFF` row in `PredictionEvaluationService.buildRows`.
5. **Display** (W3-6, W3-7): `ModelPerformanceController` DISPLAY_NAMES/DISPLAY_ORDER +
   `spreadModelOptions`; no template changes needed beyond what the model map drives.
6. **CLAUDE.md**: add ADJ_EFF to the PredictionEvaluation model list.

**Tests** (exit): tempo-fit recovery test on synthetic games; predictor formula test from
hand-built snapshots (incl. neutral-site); missing-tempo → no row; evaluation integration
test; performance-page render test. Full suite green.

**Deploy + operate**: deploy, run a full stats recalc (or wait for the next scrape with a
forced full run) so `ADJ_TEMPO` exists for all seasons, then evaluate-rebuild. Compare
ADJ_EFF vs MASSEY vs BOOK log loss/MAE — this is the "what does the ML layer actually add"
baseline the roadmap wants.

**Risks**: snapshot volume (+1 model type ≈ +50% of the ADJ write load) — fine for
`SnapshotJdbcWriter`, but confirm the stats-block runtime doesn't regress materially.
`hasAll()` for ML feature vectors must NOT start requiring ADJ_TEMPO — eff-v4's feature
contract is untouched (spec non-goal).

## Phase 3 — λ tuning harness (W4)

1. **Refactor for reuse**: extract the per-date accumulate/solve loop of
   `AdjustedEfficiencyRatingService` so the tuning service can drive it with (a) an
   arbitrary λ and (b) an in-memory sink instead of snapshot writes. Keep the public
   behavior of `calculateAndStoreForSeason` bit-identical (existing tests are the guard).
2. **Migration V32** `rating_tuning_runs` (W4-5).
3. **`AdjEfficiencyTuningService`**: the walk-forward replay (predict-with-prior-date
   solution, then absorb the date), per-(λ, season) metrics, jsonb report, run lifecycle.
   Async via the existing async-executor pattern; single-flight guard like the scrape/train
   services.
4. **Admin**: `POST /admin/ml/adj-lambda-tune` + HTMX history fragment on the dashboard
   (clone the training-status pattern).
5. **ADMIN_MANUAL.md**: sweep → read report → set property → full recalc → rebuild →
   re-check procedure (W4-7).

**Tests** (exit): determinism, leakage-guard, hand-checkable 4-team math, concurrency
rejection, end-to-end smoke — all per the spec's W4 test list. Plus a regression assertion
that the refactored `calculateAndStoreForSeason` still produces identical snapshots on a
fixture (before/after refactor).

**Operate**: run the sweep on the server; decision rule from the spec — adopt a new λ only
if it beats 1.0 on aggregate log loss consistently across seasons, then recalc + rebuild
and confirm the performance page moved the right way.

**Risks**: runtime (minutes per season × 8 λ) — acceptable async, but log progress per
(λ, season) so the admin fragment shows life; memory is trivial (accumulators are ~730²
doubles). Don't parallelize the grid initially; correctness first.

## Phase 4 — Winprob-mode shootout (W5)

No code. Next scheduled training window: train `<slug>-clf` and `<slug>-drv` from the same
feature set with the two `winprobMode` values, leave both CANDIDATE for shadow evaluation
across a few weeks (or rebuild over past seasons for instant out-of-sample reads), compare
log loss + calibration, promote the winner, retire the loser. Write the procedure into
ADMIN_MANUAL.md during Phase 1's doc touch.

## Sequencing and dependencies

```
Phase 1 (W1+W2)  ──►  Phase 2 (W3)  ──►  Phase 3 (W4)      Phase 4 (W5)
 log loss + Φ          ADJ_EFF model      λ sweep            needs only Phase 1
```

Each phase ends with: full test suite green, deploy, evaluate-rebuild, and baseline numbers
recorded in `docs/punchlist.md`. Phase 3 depends on Phase 2's config/refactor touches;
Phase 4 only on Phase 1.

## Explicitly out of scope

Tier 3 (hierarchical Bayes, LRMC), any ML feature-set change, automatic λ promotion, and
any UI beyond the performance page + admin dashboard fragments.
