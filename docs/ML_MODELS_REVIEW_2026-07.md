# Advanced ML Models — Deep Dive and Improvement Analysis

*Written 2026-07-26. Covers the deployed model bundles (`baseline`, `baseline-plus`),
the training pipeline (`scripts/train_models.py`), the serving path
(`MlPredictionService` / `MlFeatureRegistry`), and the evaluation numbers pulled from the
production `prediction_evaluations` table on fijimf.com. Companion docs:
[POWER_MODELS.md](../POWER_MODELS.md) (the rating inputs),
[ML_SYSTEM_ANALYSIS.md](ML_SYSTEM_ANALYSIS.md) (the 2026-07-09 overhaul proposal, since shipped).*

---

## 1. Executive summary

We run gradient-boosted-tree models (XGBoost) over a foundation of four classical power
rating systems (Massey margin, Massey totals, Bradley–Terry, weighted Bradley–Terry),
plus rolling form and schedule-context features. Each named model bundle has three heads
— point spread, game total, and home win probability — trained in Python, exported to
ONNX, and scored in-JVM. The architecture is genuinely good: leakage-free by
construction, train/serve feature parity enforced by mirrored registries, retroactive
backtesting for free, champion/challenger lifecycle.

**The honest performance picture is less rosy than the dashboard suggests.** Both
deployed bundles were trained on 2021–2025 with 2026 held out. That means the
2021–2025 rows in `prediction_evaluations` are **in-sample** — the model saw those games
during training — and only 2026 is a true test. In-sample, we look on par with or better
than the closing line. Out-of-sample (2026), we are behind it on every metric:

| Metric (2026, same 5,749 games) | ML:baseline-plus | Book closing line |
|---|---|---|
| Spread MAE | 9.29 | **8.92** |
| Spread RMSE | 11.79 | **11.23** |
| Total MAE | 13.89 | **13.37** |
| Winner accuracy | 70.5% | **72.2%** |
| Brier score | 0.1881 | **0.1779** |
| ATS vs closing line | **47.5%** (need 52.4% to break even) | — |

So the real gap is roughly **0.35–0.4 points of spread MAE, ~1.7pp of winner accuracy,
and ~0.010 Brier**. Nothing to be ashamed of — the closing line aggregates sharp money,
injury news, and priors we don't have — but "broadly on par" is largely an in-sample
artifact, and we should fix the optics before we fix the model (§5).

The good news: the gap is small, the biggest known weaknesses are identifiable and
fixable, and `baseline-plus` (pace-v2 features) beats `baseline` consistently by
~0.1–0.2 MAE in every season — proof that feature work moves the needle here. (Aside:
`baseline` is still the site default despite being the worse model. Flip `is_default`.)

My ranked view of where improvement actually lives, detailed in §6:

1. **Preseason priors** — the early-season window is where books lap us, and we start
   every season from zero information.
2. **A proper offense/defense efficiency rating** (per-possession, two params per team)
   to replace the margin-only Massey as the core input — this is the single biggest
   modeling upgrade available.
3. **Residual learning** (`base_margin` on the Massey/efficiency prediction) so the trees
   learn corrections instead of re-deriving the rating from 41 features.
4. **Finish the four factors and add the unused stats pipeline features** — we currently
   use 2 of the 4 factors and 6 of the 25 available box-derived stats.
5. **Data hygiene**: season-scoped rolling windows, overtime distortion, non-D-I games,
   time-decay in the ratings.
6. **Hyperparameter tuning** — the XGBoost params are untouched spec defaults.
7. **Reframe the target**: beating the *closing* line at scale is near-impossible with
   public box-score data; beating *opening* lines and soft spots (totals, low-liquidity
   games, early week lines) is a realistic goal, and we already store opening lines.

---

## 2. Architecture: how a prediction is made

```
ESPN scrapes ──▶ games, box scores
                    │
                    ▼
   Daily snapshot time series (leakage-free point-in-time store)
   ├─ team_power_rating_snapshots   (MASSEY, MASSEY_TOTALS, BRADLEY_TERRY, BRADLEY_TERRY_W)
   ├─ power_model_param_snapshots   (per-date HCA α, totals intercept γ)
   ├─ team_stat_snapshots           (25 box-derived stats: pace, efficiencies, four factors…)
   └─ team_season_stat_snapshots    (RPI, margins, z-scores…)
                    │
        ┌───────────┴────────────┐
        ▼                        ▼
  Python trainer            Java serving
  (trainer service,         (MlPredictionService + ONNX Runtime)
   train_models.py)          │
   XGBoost × 3 heads         │ MlFeatureRegistry.buildVector(manifest order)
   → ONNX + features.json ───┘
```

Key design properties, all of which I'd keep:

- **Point-in-time snapshots everywhere.** Every feature lookup is "latest snapshot
  *strictly before* game date" (`bisect` in Python, equivalent queries in Java). No
  future information can leak into a training row or a backtest. This is why a freshly
  trained candidate gets a full-history backtest on day one.
- **The feature-name parity contract.** `FEATURE_REGISTRY` (Python) and
  `MlFeatureRegistry` (Java) are mirrored registries of named feature functions. The
  bundle's `features.json` carries the ordered feature list; both sides assemble vectors
  from that order. Adding a feature is one entry on each side; a manifest naming an
  unknown feature disables the model loudly rather than mis-scoring silently.
- **No imputation of ratings.** If any rating snapshot or rolling stat is missing, the
  game is skipped in training and the model declines to predict in serving (`hasAll()`
  requires all four rating systems; a null from any supplier aborts the vector). Only
  `days_rest → −1` is imputed, identically on both sides. Train/serve skew on weighted
  BT (flagged in the 2026-07-09 analysis) is fixed.
- **Model registry + lifecycle.** `/models/<slug>/` bundles, `ml_models` table with
  ACTIVE/CANDIDATE/RETIRED and a default flag; candidates shadow-score into
  `prediction_evaluations` (`ML:<slug>` rows) without being shown publicly.

## 3. The algorithms

### 3.1 The foundation: four classical rating systems as features

The ML models do not learn team strength from raw results — they consume
already-opponent-adjusted latent ratings computed by the Java services
(full math in [POWER_MODELS.md](../POWER_MODELS.md)):

| System | Fit | Output | Role in the feature vector |
|---|---|---|---|
| **Massey (margin)** | Ridge-regularized least squares on score margin, shared HCA intercept, λ=1.0 | β per team, in points | `beta_home/away/diff` — the backbone of the spread head |
| **Massey (totals)** | Same design, both teams +1, unpenalized intercept γ + HCA δ | β per team = scoring-pace index | `gamma_home/away/sum` — backbone of the total head |
| **Bradley–Terry** | Regularized logistic MLE on win/loss, Newton–Raphson with ±2.0 step cap, warm-started | θ per team, log-odds | `theta_home/away`, `bt_logodds` (θ_h − θ_a + α) |
| **Weighted BT** | Same, each game weighted 1 + ln\|margin\| | θ per team | weighted variants of the above |

This is a sound "stacking" design: the linear-algebra models solve the
schedule-adjustment problem globally (something trees are terrible at), and the GBM
learns nonlinear residual structure — form, rest, venue, calendar effects — on top.
The four systems are deliberately diverse (margin vs. win/loss information, linear
vs. logistic loss), giving the booster several noisy views of the same latent strength.

All four are computed as **daily time series** re-fit on every game date (incremental
normal-equation accumulators for Massey, warm-started Newton for BT), which is what
makes leakage-free training rows and retroactive backtests possible.

### 3.2 The three heads (per bundle)

All heads are trained per model bundle on the identical feature matrix, on FINAL games
from the train seasons:

- **Spread**: `XGBRegressor`, target = home score − away score.
- **Total**: `XGBRegressor`, target = home + away score.
- **Win probability**: `XGBClassifier` wrapped in `CalibratedClassifierCV(method="sigmoid", cv=5)`,
  target = home win. Platt calibration is the right call at this data size; the trainer
  prints a decile calibration table.

Shared hyperparameters: `max_depth=4`, `learning_rate=0.05`, `subsample=0.8`,
`colsample_bytree=0.8`. Regressors use early stopping (ceiling 2,000 trees, patience 30,
chronological 85/15 validation split, then refit on all rows at the best iteration —
correct use of the data). The classifier is fixed at 300 trees.

Two nice touches worth calling out:

- **Monotone constraints**: the spread and win-prob heads are constrained non-decreasing
  in `massey_beta_diff`, `bt_logodds`, `bt_logodds_weighted`; the total head in
  `massey_gamma_sum`. This guarantees "better rating ⇒ better prediction" sanity and is
  a meaningful regularizer at ~28k training rows.
- **Walk-forward report**: expanding-window per-season evaluation is computed at train
  time and persisted into `features.json` — the honest methodology exists, it just isn't
  the headline number anywhere (§5).

### 3.3 Export and serving

Models are exported to ONNX (skl2onnx + onnxmltools XGBoost converters, opset 17),
written atomically via a `.pending` directory, and hot-reloaded by the app
(`POST /admin/ml/reload`, auto after training). Serving is a single ONNX Runtime session
per head with per-bundle read/write locks. Training runs on the always-on trainer
service (FastAPI) reachable only on the internal Docker network; completion chains
reload → re-evaluation automatically.

## 4. The features and why they were chosen

### 4.1 `baseline` — 27 features

Chosen at spec time (March 2026) as "everything available from the ratings + games
tables," which is exactly what they are:

| Group | Features | Rationale |
|---|---|---|
| Massey margin (3) | `massey_beta_home`, `massey_beta_away`, `massey_beta_diff` | Point-scale strength; the diff is the natural spread predictor. Home/away included separately so trees can learn quality-level effects (e.g. blowout dynamics between mismatched teams). |
| Massey totals (3) | `massey_gamma_home`, `massey_gamma_away`, `massey_gamma_sum` | Scoring-pace index; the sum is the natural total predictor. |
| Bradley–Terry (6) | `bt_theta_home/away`, `bt_logodds`, + weighted variants | Win-probability-scale strength, margin-blind (pure W/L) and margin-weighted views. `bt_logodds` already folds in the fitted per-date HCA α (zeroed for neutral sites). |
| Rolling last-5 (8) | win%, avg margin, avg total, margin stddev × home/away | Recent form and volatility beyond what slow-moving season ratings capture. |
| Season context (5) | games played ×2, days rest ×2 (−1 = season opener), `season_week` | Sample-size confidence in the ratings, fatigue/rust, calendar phase (early-season noise, conference play, March). |
| Game context (2) | `is_neutral_site`, `is_conference_game` | Venue and familiarity/intensity effects. |

### 4.2 `pace-v2` extras — 14 more (deployed as `baseline-plus`, 41 features)

Added in the 2026-07-10 overhaul, once the box-score scraping (May 2026) and derived
stats pipeline existed. Selection rationale (per ML_SYSTEM_ANALYSIS §3.1): the original
spec predicted the largest gain would come from pace/efficiency features for the totals
head, and they were simply unavailable at design time.

| Group | Features | Rationale |
|---|---|---|
| Pace (2) | `home_pace`, `away_pace` | Possessions/40 — direct driver of totals; margin variance scales with pace. |
| Efficiency (4) | `home/away_off_eff`, `home/away_def_eff` | Points per possession for/against — pace-independent quality, richer than margin alone. |
| Shooting factor (4) | `home/away_efg_pct`, `home/away_opp_efg_pct` | eFG% own and allowed — the dominant four-factor. |
| Turnover factor (2) | `home/away_tov_rate` | Possession-loss rate. |
| Schedule strength (2) | `home_rpi`, `away_rpi` | SOS-adjusted résumé measure, complementing the regression-based ratings. |

It works: `baseline-plus` beats `baseline` in **every** season, in-sample and out
(2026: spread MAE 9.29 vs 9.37, Brier 0.1881 vs 0.1882, total MAE 13.89 vs 14.14).

### 4.3 What's conspicuously *not* in the vector

- **Half the four factors.** We use eFG% and TOV rate but not **ORB%/DRB% or FT rate**
  (own or opponent) — both exist in `team_stat_snapshots` today. Dean Oliver's factors
  are a package; rebounding and free-throw pressure are exactly the "extra possessions /
  cheap points" signals margin-based ratings smear over.
- **19 of the 25 box-derived stats** (`ts_pct`, `fg3_rate`, `ast_to_ratio`, `stl_rate`,
  `blk_pct`, …) and all the **z-scored variants** (already opponent-pool-normalized per
  date — ideal for cross-season pooling).
- **`team_season_stat_snapshots` riches**: `rpiOwp`/`rpiOowp` (pure SOS), `meanMargin`/
  `stddevMargin` (consistency), rolling wins/points.
- **Any prior information**: previous-season rating, returning production, preseason
  polls. Every November the model is regularization soup while the books open with
  informed priors.
- **Anything about players**: injuries, transfers, roster continuity. This is the
  fundamental asymmetry vs. the market and mostly out of reach of ESPN box scores.

## 5. The honest evaluation (read this before believing the dashboard)

Both deployed bundles: `--train-seasons 2021…2026 --test-season 2026`. The trainer
excludes the test season from training (`train_mask = seasons != test_season`), so the
final fitted models never saw 2026 — **but they were fit on 2021–2025**, and
`PredictionEvaluationService` backtests every model over *all* seasons. Result: five of
the six seasons on `/predictions/performance` are in-sample for the ML rows, while the
BOOK rows are real predictions in every season. We're comparing our training-set fit to
the book's live performance.

Production numbers (spread MAE / winner acc / Brier), `ML:baseline-plus` vs BOOK:

| Season | ML MAE | Book MAE | ML win% | Book win% | ML Brier | Book Brier | Sample status |
|---|---|---|---|---|---|---|---|
| 2021 | 9.11 | 9.05 | 73.1% | 71.2% | 0.1762 | 0.1875 | in-sample |
| 2022 | 8.67 | 8.63 | 74.8% | 73.2% | 0.1687 | 0.1776 | in-sample |
| 2023 | 8.85 | 8.80 | 74.7% | 72.0% | 0.1721 | 0.1823 | in-sample |
| 2024 | 8.91 | 8.85 | 74.6% | 72.3% | 0.1707 | 0.1818 | in-sample |
| 2025 | 8.87 | 8.79 | 75.0% | 72.9% | 0.1656 | 0.1768 | in-sample |
| **2026** | **9.29** | **8.92** | **70.5%** | **72.2%** | **0.1881** | **0.1779** | **out-of-sample** |

The in-sample seasons flatter us by ~0.3–0.4 MAE and ~3–4pp of winner accuracy. The
walk-forward table already in `features.json` tells the same story as 2026 (spread RMSE
11.6–11.8 across held-out seasons vs. the book's 11.0–11.5).

**The betting test.** Against the closing spread on 2026, taking the model's side
whenever it disagrees with the line: **47.5% ATS** overall (need ~52.4% at −110 to break
even). Bucketed by disagreement size, the best bucket (2–4 pt edges, n=1,489) hits
52.5% — statistically indistinguishable from breakeven — and the biggest "edges"
(>10 pts, n=128) hit 49%, i.e. when we disagree hugely with the market, the market is
right. Over/under picks: 47.3%. There is currently **no exploitable edge vs. the close.**

Context on ceilings: actual margins deviate from the true expected spread with a
standard deviation of ~10–11 points in college basketball. The book's 11.23 RMSE is
essentially *at* the noise floor; our 11.79 means our spread estimate itself carries
~3–4 points of RMS error vs. the market's ~1–2. Closing that to zero is not a realistic
target; closing half of it is.

## 6. How to get better — all of it, ranked

### Tier 0 — honesty and measurement (do first, it's cheap)

1. **Label in-sample seasons on the performance page.** Add trained-on seasons to the
   bundle manifest and badge those rows, or exclude them from headline aggregates.
   Right now the page structurally overstates every ML model.
2. **Adopt walk-forward as the promotion metric.** The trainer already computes it;
   persist per-season out-of-sample metrics to `ml_models.metrics_json` and make the
   champion/challenger comparison use *only* out-of-sample rows.
3. **Evaluate on identical game sets.** BOOK covers 5,749 games, classical models 5,531,
   ML 5,752 — small but systematic composition bias (the games a model skips are
   early-season, i.e. the hardest). Compute head-to-heads on the intersection.
4. **Add ATS/CLV columns to the performance page.** ATS vs. close, and closing-line
   value vs. opening (did the line move toward us?). CLV is the sharpest available
   signal of genuine edge at these sample sizes.
5. **Make `baseline-plus` the default.** It wins everywhere and is not the default model.

### Tier 1 — highest expected value on the model itself

6. **Preseason priors.** The largest identifiable gap vs. the book is November/December,
   where our ratings are regularization-dominated and the market opens with priors.
   Concretely: add `home/away_prev_final_beta` (and θ) — last season's final rating,
   0 for newcomers — plus `home/away_prev_rating_available` flags, and let the trees
   learn the blend-by-`games_played` themselves. Cheap (snapshots already exist),
   leakage-free, and directly targets the worst window. A fancier version regresses
   prior-season rating toward conference mean to account for roster churn; with the
   transfer-portal era, even a crude "returning minutes" scrape would be gold, but the
   plain prior is the 80/20.
7. **An offense/defense possession-based rating system.** Massey margin gives one number
   per team; the standard modern approach (KenPom-style adjusted efficiency) fits **two**
   — solve `points_for(g) ≈ pace(g) · (off_h − def_a + hca)` for per-100-possession
   offensive/defensive efficiencies via the same ridge machinery you already have. This
   single system supersedes both Massey variants: off−def diffs predict spreads,
   off+def sums predict totals, and matchup structure (great offense vs. great defense)
   becomes representable. It slots in as a fifth `model_type` time series + four new
   features per side. **This is the biggest pure-modeling upgrade available** and it
   reuses the incremental-accumulator pattern from POWER_MODELS.md.
8. **Residual learning via `base_margin`.** Today the trees must reconstruct "spread ≈
   β_diff + HCA" from raw features — burning capacity re-learning a linear identity.
   Pass the Massey (or new efficiency) prediction as XGBoost's `base_margin` so the
   booster fits only the *correction*. Equivalently: stack a linear head and a tree head.
   This typically buys accuracy at small data sizes and makes monotone constraints less
   load-bearing. (Serving note: ONNX export bakes `base_score`, but `base_margin` is
   per-row — implement by training on residuals `y − massey_pred` and adding the
   prediction back in `PredictionService`; that keeps ONNX untouched.)
9. **Finish the four factors + harvest the stats pipeline.** Add `orb_pct`, `drb_pct`,
   `ft_rate` (own + opponent) to close out Oliver's factors, plus `fg3_rate` (three-point
   dependence → variance, and 3P% allowed is notoriously luck-heavy — a team riding
   opponent 3P% luck is overrated by margin models and the market knows it),
   `stddevMargin`, `rpiOwp` (pure SOS), and a **rolling-10** window beside rolling-5.
   Prefer the **z-scored variants** for anything pooled across seasons — the scoring
   environment drifts year to year and z-scores are already per-date normalized.
10. **"Hot/cold vs. rating" feature.** Mean of (actual margin − Massey-predicted margin)
    over the last 5 games. Distinguishes "good team playing badly" from "bad team" —
    exactly what rolling raw margin conflates with schedule.

### Tier 2 — training methodology

11. **Tune the hyperparameters.** `max_depth=4, lr=0.05, subsample=0.8` are the spec's
    "reasonable starting values," never swept. Run Optuna (~100 trials) with
    walk-forward mean RMSE as the objective over depth, `min_child_weight`, `lr`,
    subsampling, and L1/L2. Expect a real but modest gain (~0.05–0.15 MAE). Do this
    *after* the feature work so you tune the final feature set.
12. **Give the win-prob head the same care as the regressors.** It's fixed at 300 trees
    with no early stopping. Better: derive win probability *from the spread head* —
    P(home) = P(margin > 0) under a t/normal margin distribution with fitted σ —
    which guarantees spread/win-prob consistency (no more "favored by 2 but 48% to
    win" rows) and effectively triples the training signal for one head. Keep the
    classifier as a challenger; my money is on the derived version.
13. **Season-weighting.** Five-year-old games shouldn't count like last week's. Either
    exponential decay on sample weights by season age, or (simpler) verify with an
    ablation that dropping 2021 doesn't hurt 2026 — pandemic-adjacent season, oldest
    data, and the worst book year in the table.
14. **Quantile heads for spreads/totals.** `reg:quantileerror` at τ = 0.1/0.5/0.9 gives
    honest intervals ("−3.2 ± 9") — great for the UI, and the interval width is itself
    a bet-sizing signal (only trust edges in low-variance games).
15. **Two-score decomposition.** Predict home and away scores as two targets; spread and
    total fall out consistently (they currently come from independent heads that can
    disagree). Also opens the door to a Poisson/Skellam framing for derivatives.

### Tier 3 — data hygiene (small individually, real in aggregate)

16. **Season-scope the rolling windows.** `findRecentFinalGamesForTeam` and the trainer's
    `team_game_index` both cross season boundaries — November "form" is last March's
    tournament games with a different roster. Trainer and server are at least consistent,
    but consistent-and-wrong. Scope to season (the games-played and prior features from
    #6 cover the cold start).
17. **Overtime distortion.** OT games inflate totals (~10 pts per OT) and clamp margins
    toward small values; the book prices regulation. At minimum, train totals on
    regulation-equivalent scores or drop OT games from the totals head; also consider
    capping training margins at ~±30 (garbage-time noise) — the weighted-BT ln-weighting
    already embodies this intuition; the regression targets don't.
18. **Non-D-I games.** Games vs. non-D-I opponents (teams with 1–2 appearances) produce
    junk ratings for the opponent and 40-point margins that feed rolling form. Filter
    them from training rows and from the rolling windows (they're identifiable via
    conference membership).
19. **Investigate the 2026 totals anomaly.** ML total MAE jumped to 13.9–14.1 in 2026
    (from ~12.8–13.0) while the book only moved to 13.4. Before concluding "model
    problem," check per-season `team_game_stats` coverage and pace snapshot availability
    for 2026 — a silent feature-coverage regression looks exactly like this.

### Tier 4 — the market-aware track (separate model, separate ambition)

20. **Train a `market-aware` bundle with the opening line as a feature** (opening
    spread/OU are already stored in `betting_odds`). Two uses: (a) as a *prior* — the
    open reflects information we lack, and a model that learns "open + our correction"
    is effectively doing what sharps do; (b) as a *target* — predict the close from the
    open + our features (closing-line-value modeling). Keep the market-free models as
    the public default (they can predict any game); the market-aware one is the betting
    research tool. The registry architecture makes this a one-manifest addition.
21. **Aim at the soft spots, not the close.** Realistic places a box-score model finds
    edge: totals (less sharp than sides), low-liquidity games (small-conference
    midweek), openers before the market converges, and derivative markets. The ATS
    bucket analysis (§5) should become a standing per-segment report — if any segment
    sustains >53% over a full season *out-of-sample*, that's signal; the current overall
    47.5% is not.
22. **Be statistically sober.** ~5,700 games/season, maybe 1,500 with a meaningful
    disagreement: distinguishing a true 53% ATS skill from 50% noise needs multiple
    seasons of out-of-sample data. This is why CLV (#4) matters — it converges much
    faster than win/loss ATS records.

### Not worth it (opinionated)

- **Deep learning / embeddings.** At ~28k rows/6 seasons with tabular features, GBMs are
  at or above neural nets' ceiling. Team embeddings would just re-learn worse power
  ratings. Revisit only with play-by-play or player-level data.
- **Porting training to Java.** The Python/ONNX split is working; don't touch it.
- **More rating variants of the same information** (Elo, Colley, etc.). They'd be >0.95
  correlated with what the vector already has. The efficiency split (#7) is different —
  it adds a genuinely new axis (off/def decomposition), not another view of the same one.
- **Player-level injury modeling from news.** The news module could eventually flag
  "star out" signals, but entity-resolution on injury reports is a project unto itself
  with a low hit rate from RSS snippets. Park it.

## 7. Suggested sequence

| Step | What | Why first |
|---|---|---|
| 1 | Tier 0 (#1–5): honest metrics, ATS/CLV columns, default flip | Every later claim depends on trustworthy measurement |
| 2 | #16–18 data hygiene + #19 totals investigation | Cheap, and cleans the training signal before refitting |
| 3 | #6 preseason priors + #9 remaining four factors → new feature set `prior-v3` | Biggest gap (early season) + easiest wins; ship as CANDIDATE, judge on walk-forward |
| 4 | #7 offense/defense efficiency ratings → feature set `eff-v4` | The big structural upgrade; new snapshot time series |
| 5 | #8 residual learning + #12 derived win-prob + #11 tuning | Squeeze the final drops from the final feature set |
| 6 | #20–21 market-aware bundle + soft-spot report | The actual "beat the book" experiment, measured by CLV |

Each step is a new CANDIDATE slug through the existing shadow-evaluation lifecycle —
the infrastructure for this exact iteration loop is already built and is the system's
best asset. The realistic outcome of steps 1–5 is closing half or more of the ~0.4 MAE
gap to the close and pulling even on Brier; the realistic outcome of step 6 is knowing,
with numbers, whether any genuine edge exists rather than believing it does.
