# Game Prediction Service

This document specifies a prediction service that produces three outputs for any game:

1. **Predicted spread** — expected home margin of victory (negative = away team favored)
2. **Predicted over/under** — expected total combined score
3. **Win probability** — P(home team wins)

The baseline predictions come from the Massey Linear and Bradley-Terry models already in the system. A second-layer ML model (gradient-boosted trees) can be layered on top as a future enhancement.

---

## 1. Baseline: What the Existing Models Already Give Us

### 1.1 Massey → Spread

The Massey model is already solving for:

```
margin_predicted = β_h − β_a + α
```

For any game between home team h and away team a, the **predicted spread** is:

```
spread = β_h − β_a + α     (α = 0 for neutral site)
```

This is immediately available from the stored `TeamPowerRatingSnapshot` ratings and the `PowerModelParamSnapshot` HCA parameter `"hca"`, using the most recent snapshot dated strictly before the game date.

### 1.2 Massey Extended → Over/Under

This is the natural extension. Instead of fitting score *differences*, we fit a second independent linear system using score *sums*:

```
total_predicted = γ_h + γ_a + δ
```

where γ_i is a per-team scoring-strength parameter and δ is a home-game scoring intercept (home courts tend to produce slightly more total points). The dependent variable is:

```
z_g = homeScore_g + awayScore_g
```

The design matrix differs from the spread system only in the signs: both teams contribute `+1` to the row (both teams score in the game, so both γ pull the total up):

- Column j: `X[g, j] = +1` if team j played in game g (home **or** away), 0 otherwise
- Column T (HCA column): `X[g, T] = 1` if non-neutral site, 0 if neutral

The predicted O/U for a future game is:

```
total = γ_h + γ_a + δ     (δ = 0 for neutral site)
```

The same Cholesky/LU solve used for Massey spread applies here. The normal-equations accumulator is maintained the same way — one outer-product update per game — so the two systems can share the same date-loop pass over game data.

Per-team γ ratings are stored as `TeamPowerRatingSnapshot` rows with `modelType = "MASSEY_TOTAL"`. The δ intercept is stored as a `PowerModelParamSnapshot` row with `modelType = "MASSEY_TOTAL"`, `paramName = "hca_total"`.

The string `"MASSEY_TOTAL"` is 12 characters and fits within the `VARCHAR(20)` column in both snapshot tables.

### 1.3 Bradley-Terry → Win Probability

The Bradley-Terry model already computes exactly this. For a game between home team h and away team a:

```
P(home wins) = σ(θ_h − θ_a + α)     where σ(x) = 1 / (1 + e^−x)
```

Available from stored Bradley-Terry `TeamPowerRatingSnapshot` ratings and HCA param `"hca"`, using the most recent snapshot before the game date.

The implied fair American moneyline (no vig) is derived from p = P(home wins):

```
if p ≥ 0.5:  moneyline = −round(p / (1 − p) × 100)     // favorite, e.g. −158
else:         moneyline = +round((1 − p) / p × 100)     // underdog, e.g. +133
```

Note this is the no-vig fair line. Actual sportsbook prices include a vig of ~4–6%.

---

## 2. Can ML Methods Improve on These?

**Short answer: yes, meaningfully — but with important caveats.**

Massey and Bradley-Terry are purely aggregate rating systems. They treat all past games equally and capture no temporal structure (recent form, hot/cold streaks), no game-context features (rest, travel, back-to-back), and no within-game variance (close wins vs. blowouts carry the same weight beyond margin).

A gradient-boosted tree ensemble can capture all of these, provided enough training data exists.

### 2.1 Feature Engineering

Use Massey/BT ratings **as features**, not replacements. Additional features:

**Team-level (per team, from the snapshot before game date):**
- Massey β rating (spread model)
- Massey γ rating (total model)
- Bradley-Terry θ rating
- Win % last 5 / last 10 games
- Average margin last 5 / last 10 games
- Average total last 5 / last 10 games
- Days since last game (rest)
- Home/road record this season
- Conference vs. non-conference record

**Game-level:**
- `β_h − β_a` (Massey spread prediction)
- `γ_h + γ_a` (Massey total prediction)
- `θ_h − θ_a` (BT log-odds)
- HCA flag (neutral site?)
- Conference matchup (same conference?)
- Season week / games played (early-season models are noisier)

**Derived features:**
- Strength of schedule to date (average opponent BT rating)
- Massey residuals: actual recent margins vs. model predicted (captures hot/cold)
- Rolling standard deviation of margins (consistency)

### 2.2 Recommended Model Architecture

#### Spread Prediction (regression)

```
target: actual home margin
features: the feature set above
model: gradient-boosted regressor (XGBoost or LightGBM style)
loss: MSE or MAE
```

The Massey baseline is hard to beat significantly on spread because it is already an optimal linear estimator for margin data. ML adds value mainly through non-linear interactions (e.g., a paper-strong team that is fatigued) and recent form features.

Expected improvement: ~0.5–1.5 points RMSE reduction vs. Massey alone.

#### Over/Under Prediction (regression)

```
target: actual total score
features: same feature set, with Massey-total (γ) as primary linear predictor
model: gradient-boosted regressor
loss: MSE or MAE
```

Pace features (avg points per game rolling) will be the highest-signal inputs. Total scoring has more structural variation across matchup types (fast-paced vs. slow-paced) that the additive γ model misses.

Expected improvement: ~2–4 points RMSE reduction vs. Massey-total alone.

#### Win Probability (classification → calibrated probabilities)

```
target: binary home win
features: same feature set, with BT log-odds as primary feature
model: gradient-boosted classifier
calibration: Platt scaling or isotonic regression post-hoc
```

Use Brier score as the primary metric. A model that says 70% must be right 70% of the time.

Expected improvement: ~0.01–0.02 Brier score improvement vs. BT alone.

### 2.3 Why Gradient Boosting over Random Forest?

Both work, but gradient boosting (XGBoost/LightGBM) is preferred because:
- It optimizes a differentiable loss directly (MSE / log-loss), producing better-calibrated outputs
- It is more data-efficient on small CBB datasets (~5,000–15,000 games per season)
- Feature collinearity between Massey/BT/rolling averages is handled better

If interpretability is a priority, Random Forest is easier to explain.

### 2.4 Data Requirements and Cold Start

**Minimum viable:** A single full season (~5,000 games D-I CBB) is barely enough. Cross-season training (3–5 seasons pooled) is strongly recommended.

**Cold start:** At season start, rolling features are sparse. Fall back to Massey/BT baseline only until each team has ≥ 5 games. Prior-season end ratings can serve as priors.

---

## 3. Implementation Plan

### Phase 1 — Baseline predictions from existing models

Phase 1 delivers the prediction API using only data already computed by the Massey and Bradley-Terry models, plus the new Massey-Total model. No new entities, no new Flyway migration — the existing `team_power_rating_snapshots` and `power_model_param_snapshots` tables are reused under a new `model_type` value.

#### 3.1 Massey-Total Rating Computation

MASSEY_TOTAL is computed inside `MasseyRatingService` alongside the existing MASSEY solve. Both systems share the same single pass over game data (one DB query, one date-loop), with a second accumulator pair `(A_total, b_total)` added alongside the existing `(A, b)`.

- `MODEL_TYPE = "MASSEY_TOTAL"`
- At the start of `calculateAndStoreForSeason()`, deletes existing rows for both `"MASSEY"` and `"MASSEY_TOTAL"` from both snapshot tables
- Iterates final games in date order, updating both accumulators on each game:
  - `(A, b)` for spread — existing logic, unchanged
  - `(A_total, b_total)` for totals — same matrix structure, different right-hand side and sign convention (both teams `+1`, not `+1/−1`)
- On each date, calls `solve(A_total, b_total, T, size)` and stores:
  - Per-team γ as `TeamPowerRatingSnapshot` rows with `modelType = "MASSEY_TOTAL"`
  - The HCA intercept δ as a `PowerModelParamSnapshot` row with `modelType = "MASSEY_TOTAL"`, `paramName = "hca_total"`
- The `rank` field in MASSEY_TOTAL snapshots reflects ordering by γ (highest scoring-strength first)

The accumulator update for each game in the total system:

```
A_total[hi][hi] += 1
A_total[ai][ai] += 1
A_total[hi][ai] += 1      ← positive (both contribute)
A_total[ai][hi] += 1
if non-neutral:
    A_total[hi][T] += 1;  A_total[T][hi] += 1
    A_total[ai][T] += 1;  A_total[T][ai] += 1
    A_total[T][T]  += 1
b_total[hi] += total
b_total[ai] += total
b_total[T]  += hca * total
```

where `total = homeScore + awayScore`.

#### 3.2 New Repository Queries

The existing repositories query for snapshots at an exact date. Predictions require the **most recent snapshot strictly before the game date** (i.e., `snapshotDate < gameDate`). Two new queries are needed, expressed as native SQL (PostgreSQL) to support `LIMIT 1` cleanly.

**In `TeamPowerRatingSnapshotRepository`:**

```sql
-- Most recent snapshot for a given team, season, and model before a cutoff date
SELECT * FROM team_power_rating_snapshots
WHERE team_id = :teamId
  AND season_id = :seasonId
  AND model_type = :modelType
  AND snapshot_date < :beforeDate
ORDER BY snapshot_date DESC
LIMIT 1
```

**In `PowerModelParamSnapshotRepository`:**

```sql
-- Most recent HCA param for a given season and model before a cutoff date
SELECT * FROM power_model_param_snapshots
WHERE season_id = :seasonId
  AND model_type = :modelType
  AND param_name = :paramName
  AND snapshot_date < :beforeDate
ORDER BY snapshot_date DESC
LIMIT 1
```

Both are annotated `@Query(nativeQuery = true)` and return `Optional<T>`.

#### 3.3 PredictionService

`PredictionService` is a new `@Service` that takes a `gameId`, fetches the game, assembles predictions from existing snapshots, and returns a `PredictionResult` record.

**Inputs:**
- `gameId: Long`

**Dependencies (injected):**
- `GameRepository`
- `TeamPowerRatingSnapshotRepository`
- `PowerModelParamSnapshotRepository`

**Logic:**

The service method is `@Transactional(readOnly = true)`, which keeps all lazy loads within a single session and avoids `LazyInitializationException` when traversing `game.getSeason()`, `game.getHomeTeam()`, and `game.getAwayTeam()`.

1. Load the `Game` entity. If not found, throw `EntityNotFoundException`.
2. If `game.getStatus()` is `POSTPONED` or `CANCELLED`, return a result with all prediction sub-blocks set to `null`.
3. Derive the cutoff date: `LocalDate cutoff = game.getGameDate().toLocalDate()`. Snapshots with `snapshotDate < cutoff` are valid pre-game information.
4. Determine `seasonId = game.getSeason().getId()` and `neutralSite = Boolean.TRUE.equals(game.getNeutralSite())`.
5. For each of the three model types (`MASSEY`, `MASSEY_TOTAL`, `BRADLEY_TERRY`):
   - Fetch the most recent pre-cutoff snapshot for `homeTeam`
   - Fetch the most recent pre-cutoff snapshot for `awayTeam`
   - Fetch the most recent pre-cutoff HCA param (`"hca"` for MASSEY and BT, `"hca_total"` for MASSEY_TOTAL)
6. If either team's snapshot is missing for a model, that model's prediction block is `null` (not an error).
7. When both snapshots are present, `modelDate` is set to the **earlier** of the two snapshot dates (the more conservative bound on information freshness).
8. Compute predictions from available snapshots:
   - `masseySpread = β_h − β_a + (neutralSite ? 0 : α_massey)`
   - `masseyTotal = γ_h + γ_a + (neutralSite ? 0 : δ_total)`
   - `btProbHome = sigmoid(θ_h − θ_a + (neutralSite ? 0 : α_bt))`
   - `btProbAway = 1 − btProbHome`
   - `btMoneylineHome` and `btMoneylineAway` via the implied-moneyline formula
9. If `game.getStatus() == FINAL`, populate the actual-result fields (`actualHomeScore`, `actualAwayScore`, `actualMargin`, `actualTotal`) from the game entity.
10. Return a `PredictionResult` containing all of the above, plus game metadata.

**Minimum games threshold:** If a team's most recent snapshot has `gamesPlayed < N`, the prediction is technically valid but unreliable. The service includes the `gamesPlayed` figure for each team in the response so the consumer (UI or caller) can apply its own threshold.

#### 3.4 PredictionResult DTO

```
PredictionResult {
    gameId:         Long
    gameDate:       LocalDate
    gameStatus:     GameStatus
    neutralSite:    Boolean
    homeTeam:       TeamSummary { id, name, abbreviation, logoUrl }
    awayTeam:       TeamSummary { id, name, abbreviation, logoUrl }

    // Populated only when gameStatus == FINAL
    actualHomeScore:  Integer | null
    actualAwayScore:  Integer | null
    actualMargin:     Integer | null   // homeScore − awayScore
    actualTotal:      Integer | null   // homeScore + awayScore

    massey: MasseyPrediction | null
        spread:               Double    // β_h − β_a + α; negative = away favored
        homeGamesPlayed:      int
        awayGamesPlayed:      int
        modelDate:            LocalDate // earlier of the two teams' snapshot dates

    masseyTotal: MasseyTotalPrediction | null
        total:                Double    // γ_h + γ_a + δ
        homeGamesPlayed:      int
        awayGamesPlayed:      int
        modelDate:            LocalDate

    bradleyTerry: BradleyTerryPrediction | null
        homeWinProbability:   Double    // p_home = σ(θ_h − θ_a + α)
        awayWinProbability:   Double    // 1 − p_home
        homeImpliedMoneyline: Integer   // no-vig American odds
        awayImpliedMoneyline: Integer
        homeGamesPlayed:      int
        awayGamesPlayed:      int
        modelDate:            LocalDate
}
```

Each prediction sub-block is `null` when no valid snapshot exists for either team, or when the game status is `POSTPONED` or `CANCELLED`. The actual result fields are populated only for `FINAL` games, enabling the caller to compute prediction error (e.g., `actualMargin − massey.spread`) without a second request.

#### 3.5 PredictionController

A new `@RestController` at `/api/predictions`:

```
GET /api/predictions/game/{gameId}
```
Returns a single `PredictionResult`. 404 if the game does not exist.

```
GET /api/predictions/upcoming?days=7
```
Returns a list of `PredictionResult` for all `SCHEDULED` games with `gameDate` in the next `days` calendar days from now. `IN_PROGRESS` games are excluded (tip-off has already occurred and the result is no longer a prediction). The `days` parameter defaults to 7 and is capped at 30.

This requires a new `GameRepository` query — the existing `findByGameDateBetween` does not filter by status:

```java
@Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season " +
       "WHERE g.status = 'SCHEDULED' AND g.gameDate BETWEEN :start AND :end ORDER BY g.gameDate")
List<Game> findScheduledBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
```

The `JOIN FETCH g.season` resolves the lazy season in the same query, eliminating the N+1 risk. Because all games returned by a forward-looking date-range query will share the same active season, there is no per-game season ambiguity — the season is effectively a constant across the result set.

#### 3.6 Orchestration

Because MASSEY_TOTAL is computed inside `MasseyRatingService`, the existing `PowerRatingService` pipeline is unchanged:

```
PowerRatingService.calculateAndStoreForSeason(year)
  → masseyRatingService.calculateAndStoreForSeason(year)        // now produces both MASSEY and MASSEY_TOTAL
  → bradleyTerryRatingService.calculateAndStoreForSeason(year)  // unchanged
```

The existing admin endpoint `POST /admin/power-ratings/{year}` triggers this chain asynchronously via `AsyncScrapeService.calculatePowerRatingsAsync()` → `ScrapeOrchestrator.calculatePowerRatings()` → `PowerRatingService.calculateAndStoreForSeason()`. No new admin endpoint is needed for Phase 1.

No new Flyway migration is required. The `team_power_rating_snapshots` and `power_model_param_snapshots` tables already accommodate any `model_type` string.

#### 3.7 Summary of New Files and Changes

| File                                | Change                                                                                              |
|-------------------------------------|-----------------------------------------------------------------------------------------------------|
| `MasseyRatingService`               | Add second accumulator `(A_total, b_total)`; produce `MASSEY_TOTAL` snapshots in the same date-loop |
| `TeamPowerRatingSnapshotRepository` | Add `findLatestBefore(teamId, seasonId, modelType, beforeDate)` (native SQL)                        |
| `PowerModelParamSnapshotRepository` | Add `findLatestParamBefore(seasonId, modelType, paramName, beforeDate)` (native SQL)                |
| `GameRepository`                    | Add `findScheduledBetween(start, end)` with join-fetch on homeTeam, awayTeam, season                |
| `PredictionService`                 | New `@Service`; `@Transactional(readOnly = true)`; assembles predictions from snapshots             |
| `PredictionController`              | New `@RestController` at `/api/predictions`                                                         |

---

### Phase 2 — ML enhancement layer (ONNX)

Phase 2 adds gradient-boosted models on top of the Phase 1 baseline. The approach is ONNX: models are trained offline in Python and exported to the Open Neural Network Exchange format. The Java application loads the ONNX files at startup and scores them at prediction request time with no Python dependency at runtime.

This cleanly separates the training environment (Python, scikit-learn, XGBoost) from the serving environment (Java, Spring Boot), while avoiding the operational overhead of a Python microservice.

Phase 2 covers the training code and the Java scoring infrastructure. Because the application runs entirely inside Docker Compose, wiring the trainer into the deployment — including the Docker service definition, the shared volume, the admin reload mechanism, and the retraining workflow — is specified separately in **Phase 2a**.

#### 4.1 Three Separate Models

Phase 2 trains three independent ONNX models:

| File                  | Type                  | Target                                | Primary metric |
|-----------------------|-----------------------|---------------------------------------|----------------|
| `spread_model.onnx`   | Regressor             | Home margin (`homeScore − awayScore`) | RMSE (points)  |
| `total_model.onnx`    | Regressor             | Total score (`homeScore + awayScore`) | RMSE (points)  |
| `winprob_model.onnx`  | Calibrated classifier | Binary home win (1/0)                 | Brier score    |

Each model receives the same feature vector (described in 4.2) but optimises a different loss. Keeping them separate allows independent retraining and calibration.

#### 4.2 Feature Vector

The feature vector is fixed-width and fully numeric. Categorical flags are encoded as 0/1. The canonical ordered list (used by both Python training and Java inference):

```
# Rating features (from Phase 1 snapshots, pre-game)
massey_beta_home          # Massey β for home team
massey_beta_away          # Massey β for away team
massey_beta_diff          # β_home − β_away (the Massey spread prediction, linear feature)
massey_gamma_home         # Massey γ for home team
massey_gamma_away         # Massey γ for away team
massey_gamma_sum          # γ_home + γ_away (the Massey total prediction)
bt_theta_home             # BT θ for home team
bt_theta_away             # BT θ for away team
bt_logodds                # θ_home − θ_away + α (the BT log-odds, linear feature)

# Rolling form — home team (last 5 completed games before game date)
home_win_pct_l5           # wins / games, last 5
home_avg_margin_l5        # mean (homeScore − oppScore), last 5 (from home team perspective)
home_avg_total_l5         # mean (homeScore + oppScore), last 5
home_margin_stddev_l5     # std dev of margin, last 5 (consistency)

# Rolling form — away team (last 5 completed games before game date)
away_win_pct_l5
away_avg_margin_l5
away_avg_total_l5
away_margin_stddev_l5

# Season-to-date context
home_games_played         # from the Massey snapshot (gamesPlayed field)
away_games_played
home_days_rest            # calendar days since last game (−1 if unknown)
away_days_rest
season_week               # floor((gameDate − seasonStartDate) / 7) + 1

# Game context
is_neutral_site           # 1 if neutral, 0 otherwise
is_conference_game        # 1 if conferenceGame == true, 0 otherwise
```

Total: **24 features**. The Python training script exports this list to `features.json` alongside the ONNX files. The Java runtime reads `features.json` on startup to validate model input dimensions and to enforce feature ordering.

#### 4.3 Training Side (Python)

The training script lives at `scripts/train_models.py`. It reads directly from PostgreSQL using SQLAlchemy + psycopg2. In Docker it runs as the `trainer` container on the same internal network as the `db` container (see Phase 2a), connecting to `db:5432` using credentials from the shared `.env` file. For local development outside Docker, the same script accepts a `--db-url` argument pointing to a local PostgreSQL instance.

**Python dependencies** (`scripts/requirements.txt`):

```
xgboost>=2.0
scikit-learn>=1.4
skl2onnx>=1.16
onnxmltools>=1.12
onnx>=1.15
psycopg2-binary
pandas
numpy
```

**Training data construction:**

For every FINAL game in the training seasons:
1. Fetch the most recent pre-game Massey, MASSEY_TOTAL, and BT snapshot for each team (same `snapshot_date < game_date` logic as the Java service).
2. Compute rolling features by querying the last 5 FINAL games for each team before the game date (SQL window function or Pandas groupby-apply on the full game history loaded into memory).
3. Compute `home_days_rest` and `away_days_rest` as `game_date − previous_game_date` for each team.
4. Assemble the 27-feature row. If any snapshot is missing (team has < 5 games played), skip the row or impute — see 4.5.

**Train/test split:** Time-based. Train on all seasons except the most recent; test on the most recent complete season. Do not use random splits — future games must never appear in the training set.

**Model training:**

All three models use an XGBoost estimator wrapped in a scikit-learn `Pipeline` (no feature scaling needed for tree models; the pipeline is used to ensure the ONNX export includes any preprocessing steps):

```python
# Spread and total: regression
from xgboost import XGBRegressor
spread_model = Pipeline([
    ("model", XGBRegressor(n_estimators=300, max_depth=4, learning_rate=0.05,
                           subsample=0.8, colsample_bytree=0.8,
                           objective="reg:squarederror", random_state=42))
])

# Win probability: classification with post-hoc calibration
from xgboost import XGBClassifier
from sklearn.calibration import CalibratedClassifierCV
base_clf = XGBClassifier(n_estimators=300, max_depth=4, learning_rate=0.05,
                         subsample=0.8, colsample_bytree=0.8,
                         objective="binary:logistic", random_state=42,
                         eval_metric="logloss")
# method="sigmoid" (Platt scaling) exports to ONNX reliably; isotonic can fail with skl2onnx
winprob_model = CalibratedClassifierCV(base_clf, method="sigmoid", cv=5)
```

Hyperparameters are reasonable starting values. Cross-validation on the training set can refine them.

**Calibration:** Wrapping the classifier in `CalibratedClassifierCV` fits isotonic regression on cross-validation folds to correct probability miscalibration. The calibrated wrapper exports cleanly to ONNX via skl2onnx.

**ONNX export:**

```python
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

initial_type = [("float_input", FloatTensorType([None, 24]))]

spread_onnx  = convert_sklearn(spread_model,  initial_types=initial_type)
total_onnx   = convert_sklearn(total_model,   initial_types=initial_type)
winprob_onnx = convert_sklearn(winprob_model, initial_types=initial_type)
```

For the XGBoost models, `onnxmltools.convert_xgboost()` may be needed if `skl2onnx` does not support the XGBoost backend directly. The `CalibratedClassifierCV` wrapping the XGBoost classifier exports as a single ONNX graph that includes both the boosted trees and the isotonic calibration.

**Feature schema file** (`models/features.json`):

```json
{
  "version": "2025-12-15",
  "features": [
    "massey_beta_home", "massey_beta_away", "massey_beta_diff",
    "massey_gamma_home", "massey_gamma_away", "massey_gamma_sum",
    "bt_theta_home", "bt_theta_away", "bt_logodds",
    "home_win_pct_l5", "home_avg_margin_l5", "home_avg_total_l5", "home_margin_stddev_l5",
    "away_win_pct_l5", "away_avg_margin_l5", "away_avg_total_l5", "away_margin_stddev_l5",
    "home_games_played", "away_games_played",
    "home_days_rest", "away_days_rest", "season_week",
    "is_neutral_site", "is_conference_game"
  ],
  "spread_model": "spread_model.onnx",
  "total_model": "total_model.onnx",
  "winprob_model": "winprob_model.onnx"
}
```

The script prints test-set RMSE (spread, total) and Brier score (win prob) before exiting. Exit code is non-zero on training failure, which allows the host-level `retrain.sh` script to detect failures and skip the reload step.

**Output files:** In Docker the script writes to `/models` (the `model_data` named volume). For local development, `--output-dir` points to a local filesystem path. The ONNX files and `features.json` are **not checked into git** — they are runtime artifacts produced by the training container. Add `models/*.onnx` and `models/features.json` to `.gitignore`.

#### 4.4 Runtime Side (Java)

**Maven dependency** (add to `pom.xml`):

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.17.3</version>
</dependency>
```

The CPU-only artifact is sufficient; ONNX inference on 27-feature vectors is microseconds.

**Configuration** (`application.properties` / `.env`):

```properties
prediction.ml.model-dir=${ML_MODEL_DIR:/models}
prediction.ml.enabled=${ML_ENABLED:false}
```

`ML_MODEL_DIR` is always a **filesystem path**, not a classpath location — `OrtEnvironment.createSession()` takes a file path or byte array, not a classpath resource. In Docker Compose (Phase 2a) the `model_data` named volume is mounted at `/models` in the `app` container, so the default of `/models` is correct. For local development outside Docker, override to an absolute local path. `ML_ENABLED` defaults to `false` so that a first deploy without any trained models starts cleanly in Phase 1-only mode.

When `ML_ENABLED=false`, or when the model directory does not contain all three ONNX files plus `features.json`, the `MlPredictionService` initialises in a disabled state and returns `null` for all ML predictions without error. Phase 1 predictions continue to work in all cases.

**`MlPredictionService`:**

A new `@Service` with the following structure:

- `@PostConstruct init()`: reads `features.json`, validates the feature count, loads the three `OrtSession` objects from the ONNX files using `OrtEnvironment.getEnvironment().createSession(path)`. If any file is missing or malformed, logs a warning and sets `enabled = false`.
- `predict(MlFeatureVector v)`: constructs a `float[1][24]` array in the exact order from `features.json`, wraps it in an `OnnxTensor`, runs all three sessions, extracts outputs:
  - Spread session: `output[0]` is a float (predicted margin)
  - Total session: `output[0]` is a float (predicted total)
  - Win prob session: `output[1]` (probability array from `predict_proba`) is a `float[1][2]`; take column index 1 for P(home wins)
- Returns `MlPrediction` or `null` if disabled or if the feature vector is incomplete (see 4.5).
- `reload()`: closes all open `OrtSession` objects, then re-executes the `init()` logic from the current `ML_MODEL_DIR`. Called by the admin reload endpoint (Phase 2a). Logs the outcome at INFO level.
- `getStatus()`: returns `MlModelStatus { boolean enabled, String version, Instant trainedAt, int featureCount }` where `trainedAt` is the `lastModified` timestamp of `features.json`. Used by the admin dashboard (Phase 2a).
- `@PreDestroy close()`: releases all `OrtSession` and `OrtEnvironment` resources.

**`MlFeatureVector` record:**

A plain Java record holding all 24 named fields. Rating and game-context features use primitives (always available from snapshots). Rolling features use boxed `Double` / `Integer` so that a missing value can be represented as `null` (e.g., `homeDaysRest = null` when no prior game exists this season). `MlPredictionService.predict()` checks for any null field and returns `null` if the vector is incomplete.

```java
record MlFeatureVector(
    double masseyBetaHome, double masseyBetaAway, double masseyBetaDiff,
    double masseyGammaHome, double masseyGammaAway, double masseyGammaSum,
    double btThetaHome, double btThetaAway, double btLogodds,
    Double homeWinPctL5, Double homeAvgMarginL5, Double homeAvgTotalL5, Double homeMarginStddevL5,
    Double awayWinPctL5, Double awayAvgMarginL5, Double awayAvgTotalL5, Double awayMarginStddevL5,
    int homeGamesPlayed, int awayGamesPlayed,
    Integer homeDaysRest, Integer awayDaysRest, int seasonWeek,
    boolean isNeutralSite, boolean isConferenceGame
) {}
```

**`MlPrediction` sub-record** (added to `PredictionResult`):

```
MlPrediction {
    spread:               Double    // ML-predicted home margin
    total:                Double    // ML-predicted total score
    homeWinProbability:   Double    // calibrated P(home wins)
    awayWinProbability:   Double    // 1 − homeWinProbability
    homeImpliedMoneyline: Integer
    awayImpliedMoneyline: Integer
    modelVersion:         String    // "version" field from features.json
    featuresComplete:     boolean   // false if any rolling feature was imputed
}
```

The `PredictionResult` DTO (section 3.4) gains an `ml: MlPrediction | null` field. It is `null` when ML is disabled or when the cold-start threshold is not met.

#### 4.5 Rolling Feature Computation

Rolling features (`win_pct_l5`, `avg_margin_l5`, etc.) require querying recent game history, which is not stored in the power rating snapshots. A new `GameRepository` query is needed:

```java
@Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
       "WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) " +
       "  AND g.status = 'FINAL' " +
       "  AND g.gameDate < :beforeDate " +
       "ORDER BY g.gameDate DESC")
List<Game> findRecentFinalGamesForTeam(
    @Param("teamId") Long teamId,
    @Param("beforeDate") LocalDateTime beforeDate,
    Pageable pageable   // PageRequest.of(0, 5)
);
```

`PredictionService` calls this for both teams (two queries) and computes rolling stats in-memory from the returned list (at most 5 rows each):

- `win_pct_l5`: for each game, determine if the team won; take mean
- `avg_margin_l5`: from the team's perspective (positive = won by N)
- `avg_total_l5`: `homeScore + awayScore`
- `margin_stddev_l5`: sample standard deviation (returns 0.0 if only 1 game)
- `days_rest`: `ChronoUnit.DAYS.between(lastGame.getGameDate().toLocalDate(), game.getGameDate().toLocalDate())`

The HCA-adjusted margin (from the team's viewpoint, not the home team's) is:
```
team_margin = team is home ? (homeScore − awayScore) : (awayScore − homeScore)
```

#### 4.6 Cold Start Handling

If either team has fewer than 5 FINAL games before the game date, the rolling window is incomplete. The ML model was trained on complete 5-game windows and its behavior on shorter windows is undefined.

Policy: if either team has `gamesPlayed < 5` (available from the Massey snapshot), `MlPredictionService.predict()` returns `null`, and `PredictionResult.ml` is `null`. Phase 1 predictions are still returned. `featuresComplete = false` is set in `MlPrediction` if any rolling feature was computed over fewer than 5 games (a softer version: still predict, but flag it).

The threshold of 5 games is intentionally conservative. It can be lowered once empirical testing confirms stable outputs with shorter windows.

#### 4.7 Retraining Workflow

The retraining workflow is Docker-based and described fully in Phase 2a (section 5.6). The short version: a `trainer` Docker Compose service runs `train_models.py`, writes output to the `model_data` named volume, and then the admin triggers a model reload via `POST /admin/ml/reload` without restarting the application.

#### 4.8 Summary of New Files and Changes

**Java / application changes:**
- `MlPredictionService` — new `@Service`; ONNX session lifecycle; `predict()`, `reload()`, `getStatus()`
- `MlFeatureVector` — new record; 27 named feature fields
- `PredictionService` — extended to compute rolling features and call `MlPredictionService`
- `PredictionResult` — add `ml: MlPrediction` field
- `GameRepository` — add `findRecentFinalGamesForTeam(teamId, beforeDate, pageable)`
- `application.properties` — add `prediction.ml.model-dir` and `prediction.ml.enabled`
- `pom.xml` — add `com.microsoft.onnxruntime:onnxruntime` dependency

**Python training script:**
- `scripts/train_models.py` — new Python training script
- `scripts/requirements.txt` — Python dependencies for training
- `.gitignore` — add `models/*.onnx` and `models/features.json`

Docker Compose service, shared volume, admin UI, and host-level retraining scripts are specified in Phase 2a.

---

### Phase 2a — Docker deployment and administration

Phase 2a wires the Phase 2 training and scoring code into the Docker Compose deployment. It covers: the `trainer` Docker service, the shared `model_data` volume, atomic model updates, the admin reload endpoint, the admin UI card, and the host-level retraining script.

#### 5.1 Docker Compose Changes

Three changes to `docker-compose.yml`:

**1. New `trainer` service** (does not start with `docker compose up`; run explicitly):

```yaml
  trainer:
    build:
      context: ./scripts
      dockerfile: Dockerfile.trainer
    profiles:
      - training
    env_file: .env
    environment:
      DB_HOST: db
      DB_PORT: "5432"
    volumes:
      - model_data:/models
    networks:
      - backend
    depends_on:
      - db
```

The `profiles: [training]` flag means `docker compose up` never starts this service. It is invoked explicitly via `docker compose --profile training run --rm trainer ...`. The container shares the same `backend` network as `db`, so it connects to PostgreSQL at `db:5432`. DB credentials (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`) are inherited from `.env` via `env_file`.

**2. New `model_data` named volume** (persists across restarts and redeployments):

```yaml
volumes:
  model_data:
```

**3. Additions to the existing `app` service:**

```yaml
  app:
    environment:
      # ... existing env vars ...
      ML_MODEL_DIR: /models
      ML_ENABLED: ${ML_ENABLED:-false}
    volumes:
      # ... existing volumes ...
      - model_data:/models:ro
```

The `:ro` flag mounts the volume read-only in the `app` container — the app only reads models, it never writes them. This also prevents accidental overwrite. The trainer mounts the same volume read-write.

`ML_ENABLED` defaults to `false` in `.env` until the first successful training run, then is set to `true`. Alternatively, keep `ML_ENABLED=true` and rely on the graceful-disable logic in `MlPredictionService` (if model files are absent, it silently disables itself). Either works; the explicit flag is clearer.

#### 5.2 Trainer Dockerfile

`scripts/Dockerfile.trainer`:

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY train_models.py .

ENTRYPOINT ["python", "train_models.py"]
```

The image installs dependencies at build time (Docker layer caching means `pip install` is only re-run when `requirements.txt` changes). The script is the entrypoint; arguments are appended at `docker compose run` time:

```bash
docker compose --profile training run --rm trainer \
  --train-seasons 2023,2024,2025 \
  --test-season 2025
```

`DB_HOST`, `DB_PORT`, and the database credentials are already in the container's environment via `env_file: .env`. The script does not need a `--db-url` argument — it constructs the connection string from those environment variables.

#### 5.3 Model File Lifecycle

**First deploy (no models yet):** The `model_data` volume is empty. `MlPredictionService` initialises with `enabled = false`. All requests return Phase 1 predictions only. No error, no crash.

**After first training run:** The trainer writes `spread_model.onnx`, `total_model.onnx`, `winprob_model.onnx`, and `features.json` to `/models` on the volume. The admin then triggers a reload (5.4). `MlPredictionService` reads the files, enables itself, and subsequent predictions include the `ml` block.

**Subsequent training runs:** The trainer writes new model files to a temporary subdirectory `/models/.pending/`, then renames each file atomically into `/models/` (using filesystem rename, which is atomic within the same volume mount). `features.json` is written last; its update is the signal that all model files are consistent. The admin reload is triggered after the rename completes, so the app never loads a partial or in-progress set.

**Volume persistence across redeployments:** Named volumes are not removed by `docker compose up`, `down`, or `docker compose up --build`. A `docker compose down -v` (explicit volume removal) would destroy the models — this should never be run in production without first backing up or re-running training. Document this in the server runbook.

#### 5.4 Model Reload Mechanism

Because models are on a shared volume (not bundled in the JAR), the app can load new models without restarting — it just needs to close and reopen the ONNX sessions. The `reload()` method on `MlPredictionService` handles this.

**Admin endpoint** (added to `AdminController`):

```
POST /admin/ml/reload
```

Calls `mlPredictionService.reload()` synchronously (the operation takes under a second — it's file I/O and native session initialisation). Returns an HTMX fragment with the updated model status card, or a redirect to `/admin` for non-HTMX callers. Requires admin authentication (already enforced by Spring Security for all `/admin/**` routes).

**HTTP Basic auth requirement for `retrain.sh`:** The `retrain.sh` script (§5.6) calls this endpoint via `curl --user`. Spring Security's current config uses form-based login only. To allow HTTP Basic for script access, add `.httpBasic(Customizer.withDefaults())` to the `SecurityFilterChain` configuration. This enables both form login (browser) and Basic auth (scripts) for `/admin/**` simultaneously. Alternatively, hit the endpoint via a browser session cookie — but that is not scriptable.

**`MlPredictionService.reload()` logic:**

1. Acquire a write lock (to prevent concurrent predictions during reload)
2. Call `close()` to release existing `OrtSession` objects
3. Call `init()` to load from the current `ML_MODEL_DIR`
4. Release lock
5. Log: `"ML models reloaded — version {}, trained at {}"` at INFO level

The lock ensures that any in-flight `predict()` calls finish before the sessions are closed. A `ReentrantReadWriteLock` is appropriate: `predict()` holds a read lock, `reload()` holds a write lock.

#### 5.5 Admin UI

The existing admin dashboard (`/admin`) gets a new **ML Models** card alongside the existing season management and scrape controls. It is a Thymeleaf fragment, refreshable via HTMX.

**Card contents:**

- Status badge: **Enabled** (green) / **Disabled** (grey)
- Model version: the `version` string from `features.json` (e.g. `2025-12-15`)
- Trained at: `features.json` last-modified timestamp
- Feature count: number of features in the schema (should always be 27; mismatch indicates a stale model)
- **Reload Models** button — `hx-post="/admin/ml/reload"`, `hx-target="#ml-status-card"`, `hx-swap="outerHTML"`. Disabled if ML is already disabled (no point reloading absent files).
- Static note: *"To retrain models, run `./scripts/retrain.sh` on the server after a full season scrape."*

**New admin controller methods:**

```
GET  /admin/ml/status   → HTMX fragment: the ML status card
POST /admin/ml/reload   → calls mlPredictionService.reload(); returns updated ML status card fragment
```

`/admin/ml/status` is used on initial page load and can be polled with HTMX if a long-running retrain is eventually triggered from the UI (Phase 3 enhancement, not in scope here).

#### 5.6 Retraining Workflow

The end-to-end retraining process is encapsulated in `scripts/retrain.sh`, a host-level Bash script run on the server (not inside Docker):

```bash
#!/usr/bin/env bash
set -euo pipefail

TRAIN_SEASONS="${1:-2023,2024,2025}"
TEST_SEASON="${2:-2025}"

echo "[retrain] Starting ML model training..."
docker compose --profile training run --rm trainer \
  --train-seasons "$TRAIN_SEASONS" \
  --test-season "$TEST_SEASON"

echo "[retrain] Training complete. Triggering model reload..."
source .env
# Spring Security uses form-based login; HTTP Basic must also be enabled for /admin/**
# (see §5.4 — add httpBasic() to the security config). Once enabled, -u passes credentials.
curl -sf \
  --user "${ADMIN_USERNAME}:${ADMIN_PASSWORD}" \
  -X POST "http://localhost/admin/ml/reload" \
  && echo "[retrain] Models reloaded successfully." \
  || echo "[retrain] WARNING: reload request failed. Restart the app container manually."
```

Usage after a full-season scrape:

```bash
./scripts/retrain.sh 2023,2024,2025 2025
```

The script:
1. Runs the `trainer` container (which connects to `db:5432` on the internal Docker network)
2. Training output (RMSE, Brier score) is printed to stdout
3. On success (exit code 0), hits the admin reload endpoint via localhost (through nginx)
4. On training failure (non-zero exit), `set -e` aborts before the reload step — the old models remain in place

`ADMIN_USERNAME` and `ADMIN_PASSWORD` must be in `.env` on the host. The curl goes through nginx on port 80 so no port-forwarding is needed.

**When to retrain:** After every completed full-season scrape (`POST /admin/scrape/full/{year}`). A reasonable schedule is once per week during the active season. There is no automated trigger in Phase 2a — retraining is a deliberate manual action because it changes the live prediction model and deserves review (check the printed metrics before the reload).

#### 5.7 deploy.sh Changes

The existing `deploy.sh` builds the Spring Boot image, transfers files, and restarts Docker Compose. Two additions are needed:

**Build the trainer image on the server** (after `docker compose up --build -d`):

```bash
docker compose build trainer
```

This can be included in the same `docker compose` invocation:

```bash
docker compose build trainer && docker compose up --build -d
```

`docker compose build trainer` is a no-op if `scripts/Dockerfile.trainer` and `scripts/requirements.txt` have not changed (Docker layer cache). It only runs pip installs when dependencies change.

**Copy the `scripts/` directory** to the server as part of the file transfer step, since it now contains `Dockerfile.trainer`, `requirements.txt`, and `retrain.sh` that are needed on the server.

#### 5.8 `.env.example` Additions

```bash
# ML model prediction (Phase 2)
ML_ENABLED=false          # set to true after first successful training run
ML_MODEL_DIR=/models      # path inside the app container; matches docker-compose volume mount
```

`ADMIN_USERNAME` and `ADMIN_PASSWORD` should already be in `.env.example` for the existing admin login. Confirm they are — `retrain.sh` reads them.

#### 5.9 Summary of New Files and Changes

**Docker and deployment:**
- `docker-compose.yml` — add `trainer` service, `model_data` named volume; add volume mount and `ML_MODEL_DIR`/`ML_ENABLED` env vars to `app`
- `scripts/Dockerfile.trainer` — new Python training image
- `scripts/retrain.sh` — new host-level retraining script
- `deploy.sh` — add `docker compose build trainer`; ensure `scripts/` is copied to server
- `.env.example` — add `ML_ENABLED` and `ML_MODEL_DIR`

**Admin backend:**
- `AdminController` — add `GET /admin/ml/status` and `POST /admin/ml/reload` endpoints
- `MlPredictionService` — add `reload()`, `getStatus()`, and `ReentrantReadWriteLock` around `predict()`

**Admin UI (Thymeleaf):**
- `templates/admin/dashboard.html` (or equivalent) — add ML Models status card with reload button
- `templates/admin/fragments/ml-status.html` — new HTMX fragment for the ML status card

---

### Phase 3 — UI integration

Phase 3 surfaces predictions in the existing Thymeleaf/HTMX application. It delivers three things: a new `/predictions` page showing upcoming game forecasts across the whole league; a new `/games/{id}` game detail page that shows the full prediction breakdown alongside the actual result for completed games; and a lightweight enhancement to the team detail schedule table that adds prediction data for upcoming games without cluttering the layout.

All new pages follow the existing conventions: `layout:decorate="~{layout/default}"`, CSS variables, HTMX for dynamic swaps, Chart.js (already loaded in the layout) for any visual elements, and `th:inline="javascript"` blocks for JS that needs server-rendered data.

#### 6.1 New `/predictions` Page — Upcoming Game Forecasts

This is the primary deliverable. A top-level page reachable from the nav showing all SCHEDULED games in the next 7 days (adjustable) with predictions.

**URL:** `GET /predictions?days=7`

**Controller:** `PredictionsPageController` — calls `PredictionService.getUpcoming(days)` (which wraps the existing `GET /api/predictions/upcoming?days=` logic) and groups results by date. Passes a `Map<LocalDate, List<PredictionResult>>` ordered by date to the template.

**Layout of each game row:**

```
┌─────────────────────────────────────────────────────────┐
│  [Logo] Duke       62%  ░░░░░░░░░░░░░░░████       [Logo] │
│  [Logo] UNC        38%  ████░░░░░░░░░░░░░░░             │
│                                                          │
│  Pred spread  Duke -3.2    Pred total  148.4            │
│  Book spread  Duke -2.5 ▲  Book O/U   147.5             │
│  7:00 PM ET · ACC · Smith Center                        │
└─────────────────────────────────────────────────────────┘
```

- **Win probability bar**: a horizontal bar component (new CSS class `prob-bar`) split proportionally between home (left, team primary color) and away (right, team primary color). Percentages displayed at each end.
- **Predicted spread**: expressed from the home team's perspective — `Duke -3.2` means Duke favored by 3.2. If |spread| < 0.5, display `Pick 'em`.
- **Predicted total**: displayed as a plain number, e.g. `148.4`.
- **Book lines**: shown below the model predictions when `BettingOdds` are available (see 6.5). A small indicator arrow (▲/▼) flags when the model spread differs from the book spread by more than 2 points.
- **Confidence dim**: if either team has `gamesPlayed < 5`, the prediction row is rendered at reduced opacity with a tooltip `"Early season — limited data"`.
- **ML badge**: if the `ml` block in `PredictionResult` is non-null, a small `ML` badge appears on the spread and total values indicating the enhanced model was used (vs. the baseline Massey/BT).

**Date range control:**

```html
<select hx-get="/predictions/list"
        hx-target="#predictions-list"
        hx-swap="innerHTML"
        name="days">
  <option value="3">Next 3 days</option>
  <option value="7" selected>Next 7 days</option>
  <option value="14">Next 14 days</option>
</select>
```

`GET /predictions/list?days=N` returns only the `#predictions-list` fragment via HTMX swap, avoiding a full page reload.

**Empty state:** If no games are scheduled in the selected window, display a card with "No games scheduled in the next N days."

**No-prediction state:** If predictions are unavailable for a game (both teams haven't played enough games, or ratings haven't been calculated yet), render the game row with the team names and date but with `—` in place of all prediction values.

#### 6.2 New `/games/{id}` Game Detail Page

A dedicated page for a single game. Linked from the team schedule table (see 6.4) and from game rows on the `/predictions` page.

**URL:** `GET /games/{id}`

**Controller:** `GameDetailController` — loads the `Game` entity and calls `PredictionService.predict(gameId)`. Passes both to the template.

**Page structure:**

**Header section** — always shown:
- Home team logo, name, record (from `SeasonStatistics` if available)
- Away team logo, name, record
- Game date/time, venue, neutral-site flag, conference game marker
- Game status badge: `SCHEDULED` / `IN PROGRESS` / `FINAL` / `POSTPONED` / `CANCELLED`

**Result section** — shown for FINAL games:
- Large score display: `Duke 78 – UNC 71`
- Actual margin and total for quick reference
- Overtime label if applicable (from `game.periods > 2`)

**Prediction section** — shown when `PredictionResult` has at least one non-null sub-block:

```
Predictions  ─────────────────────────────────────────────
  Win probability     [===== Duke 62% | UNC 38% =====]

  Spread              Total
  Massey   Duke -3.2  Massey   148.4
  BT       —          BT       —
  ML       Duke -3.8  ML       149.1      [ML badge]

  Spread accuracy (FINAL only):
  Actual margin  Duke +7   vs  Massey -3.2  →  error +10.2
```

For FINAL games, show prediction error alongside each model:
- Spread error = `actualMargin − predictedSpread` (positive = home team outperformed model)
- Total error = `actualTotal − predictedTotal`

**BettingOdds section** — shown when `game.bettingOdds` is non-null:
- Opening spread, closing spread, over/under
- Moneylines
- If FINAL: whether each bet would have won (outcome vs. line)

**No-prediction state:** If `PredictionResult` has all null sub-blocks (ratings not yet calculated, team hasn't played, or game is POSTPONED/CANCELLED), show a muted card: `"Predictions not available for this game."`

#### 6.3 Team Schedule Table Enhancement

The existing `team-season.html` schedule table already shows betting odds spread and O/U in the two rightmost desktop columns. Phase 3 adds model predictions for SCHEDULED games.

**Approach:** client-side JS, consistent with the existing analytics pattern. The schedule is server-rendered; JS then enriches upcoming game rows with prediction data fetched from `GET /api/predictions/game/{gameId}`.

Each game row in the table already has a class based on result (`schedule-row-win` / `schedule-row-loss`). SCHEDULED rows have no result class. The schedule DTO needs one addition: expose `game.id` so the JS can build the API URL.

**JS behaviour:**
1. On page load, find all rows where the result cell shows a time (not a score) — these are SCHEDULED games.
2. For each, read a `data-game-id` attribute from the row.
3. Fetch `GET /api/predictions/game/{id}` in parallel (one `Promise.all` across all upcoming rows).
4. For each response, populate two cells already present in the table: the existing Spread cell (currently showing the book spread) and the O/U cell — add the model prediction as a second line:

```
-2.5        ← book spread (existing)
~ -3.2      ← model spread (new, muted)
```

Similarly for O/U:
```
147.5       ← book O/U (existing)
~ 148.4     ← model total (new, muted)
```

Also add a `data-game-id` attribute and a link on the Date cell to `/games/{id}` for all games (past and upcoming), enabling navigation to the game detail page.

This keeps the schedule table compact — model predictions appear as a secondary line under the book line, clearly distinguished by the `~` prefix and muted colour. The full prediction breakdown is one click away via the game detail page.

#### 6.4 BettingOdds Comparison — the "Edge"

When both a book line (`BettingOdds`) and a model prediction exist for the same game, compute an **edge** — the difference between the model spread and the book spread. A large edge suggests the model sees the game differently from the market.

**Spread edge** = `massey.spread − bettingOdds.spread`
- Positive: model thinks home team will win by more than the book implies → value on home team
- Negative: model thinks away team will cover → value on away team

**Total edge** = `masseyTotal.total − bettingOdds.overUnder`
- Positive: model expects higher total → value on the Over
- Negative: model expects lower total → value on the Under

**Display rules:**
- |edge| < 1.0 pt: not shown (noise)
- 1.0 ≤ |edge| < 2.5 pts: shown as muted indicator `▲` or `▼`
- |edge| ≥ 2.5 pts: shown with highlighted class (e.g. green/amber background chip)

These thresholds are intentionally conservative. The model is not a professional handicapping tool; the visual treatment should communicate disagreement with the market, not imply a reliable betting signal. A small disclaimer is appropriate on the `/predictions` page: *"Model predictions are for analytical purposes only and do not constitute betting advice."*

The edge is computed in the Thymeleaf template (or in a helper method on the view model), not in the backend DTO — it's purely presentational logic. The `PredictionResult` already contains both model values and (via the game entity link) the betting odds.

#### 6.5 Controller Specifications

**`PredictionsPageController`** (new `@Controller`, not `@RestController`):
- `GET /predictions` → `predictions/upcoming` template; model includes `predictionsByDate`, `days`, `today`
- `GET /predictions/list` → `predictions/upcoming-list` fragment (HTMX); same model subset; `hx-swap="innerHTML"` target

**`GameDetailController`** (new `@Controller`):
- `GET /games/{id}` → `pages/game-detail` template; model includes `game`, `prediction` (may be null)
- 404 if game not found

Both controllers call `PredictionService` directly (not via HTTP). Both are `@Transactional(readOnly = true)` or delegate to the service which already is.

**Existing `TeamSeasonController`** (or equivalent — whichever controller serves the `team-season` fragment):
- Add `game.id` to the game DTO used in the schedule table so the template can emit `data-game-id` attributes

#### 6.6 Template Specifications

**New files:**
- `templates/pages/predictions.html` — the `/predictions` page; uses `layout:decorate`, groups games by date, includes the prob-bar component inline
- `templates/fragments/predictions-list.html` — `th:fragment="predictions-list"` returned by the HTMX swap endpoint for date-range changes
- `templates/pages/game-detail.html` — the `/games/{id}` page; server-rendered, no HTMX needed

**Modified files:**
- `templates/fragments/team-season.html` — add `data-game-id` to each `<tr>`, add `~` model lines to Spread and O/U cells (rendered client-side), add link on Date cell
- `templates/fragments/nav.html` — add Predictions nav link (see 6.7)

**New CSS components** (in `main.css`):

```css
/* Win probability bar */
.prob-bar { display: flex; border-radius: 4px; overflow: hidden; height: 24px; }
.prob-bar__home { background: var(--team-color-home, var(--color-primary));
                  display: flex; align-items: center; justify-content: flex-start;
                  padding: 0 6px; font-size: 0.75rem; color: #fff; font-weight: 600; }
.prob-bar__away { background: var(--team-color-away, var(--color-secondary));
                  display: flex; align-items: center; justify-content: flex-end;
                  padding: 0 6px; font-size: 0.75rem; color: #fff; font-weight: 600; }

/* Edge indicator chips */
.edge-chip { display: inline-block; padding: 1px 5px; border-radius: 3px;
             font-size: 0.7rem; font-weight: 600; }
.edge-chip--high { background: #d1fae5; color: #065f46; }  /* ≥ 2.5 pt edge */
.edge-chip--low  { color: var(--color-text-muted); }        /* 1–2.5 pt edge */

/* ML badge */
.ml-badge { display: inline-block; padding: 1px 4px; border-radius: 3px;
            background: #ede9fe; color: #5b21b6;
            font-size: 0.65rem; font-weight: 700; vertical-align: middle; }

/* Confidence dim */
.prediction-row--low-confidence { opacity: 0.55; }
```

Team primary colors for the prob-bar are passed as inline CSS variables via Thymeleaf: `th:style="'--team-color-home: #' + ${prediction.homeTeam.color}"`.

#### 6.7 Navigation Update

Add a `Predictions` link to `fragments/nav.html`, positioned after `Games`:

```html
<li><a class="nav__link" th:href="@{/predictions}"
       th:classappend="${#strings.equals(currentPage, 'predictions')} ? 'nav__link--active'">
    Predictions
</a></li>
```

The `currentPage` variable is set by each controller in the model (existing convention).

#### 6.8 Summary of New Files and Changes

**New controllers:**
- `PredictionsPageController` — serves `/predictions` page and HTMX list fragment
- `GameDetailController` — serves `/games/{id}` page

**New templates:**
- `templates/pages/predictions.html`
- `templates/fragments/predictions-list.html`
- `templates/pages/game-detail.html`

**Modified templates:**
- `templates/fragments/team-season.html` — add `data-game-id`, model prediction secondary lines, date-cell links
- `templates/fragments/nav.html` — add Predictions nav link

**Modified backend:**
- Team schedule game DTO — expose `gameId` field so the JS can build prediction API URLs
- `main.css` — add `prob-bar`, `edge-chip`, `ml-badge`, `prediction-row--low-confidence` components

**No new REST endpoints** — Phase 3 uses the `GET /api/predictions/game/{id}` and `GET /api/predictions/upcoming?days=N` endpoints already specified in Phase 1.
