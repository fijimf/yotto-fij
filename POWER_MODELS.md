# Power Rating Models

This document describes four regression-based power rating models for college basketball teams. Unlike raw statistics, these models produce a single ordinal strength rating per team per season by simultaneously fitting all game results to a common scale.

All models are fit only on FINAL games (homeScore and awayScore non-null). Neutral-site games are included but the home court advantage term is suppressed for them (where applicable). All four are computed as **time series**: the model is re-fit for every game date in the season using all games up to and including that date, producing a snapshot of ratings per team per date that shows how rankings evolve over the course of the season.

## References

- **Massey Linear Rating** is named after Kenneth Massey, who described the least-squares margin-regression approach in his 1997 undergraduate honors thesis at Bluefield College:
  > Kenneth Massey. *Statistical Models Applied to the Rating of Sports Teams.* Bluefield College, Spring 1997.
  > https://masseyratings.com/theory/massey97.pdf

- **Bradley-Terry Logistic Rating** is named after Ralph A. Bradley and Milton E. Terry, who introduced the paired-comparison probability model in:
  > R. A. Bradley and M. E. Terry. "Rank Analysis of Incomplete Block Designs: I. The Method of Paired Comparisons." *Biometrika*, Vol. 39, No. 3/4 (Dec. 1952), pp. 324–345.
  > https://academic.oup.com/biomet/article-abstract/39/3-4/324/326091

  The model's application to sports ratings (estimating team strengths from win/loss outcomes as pairwise comparisons) is a natural and well-established use of the original paired-comparison framework.

---

## 1. Massey Linear Rating

### Model

Each game is an observation. Each team has a scalar rating parameter β_i. For a game between home team h and away team a, the model predicts:

```
margin_predicted = β_h − β_a + α
```

where α is a shared home court advantage intercept (suppressed for neutral-site games).

The dependent variable y_g is the **home team margin of victory**:

```
y_g = homeScore_g − awayScore_g
```

The design matrix **X** is N × (T + 1), where N is the number of completed games and T is the number of teams with at least one game in the season:

- Column j (0 ≤ j < T): X[g, j] = +1 if team j is the home team in game g, −1 if team j is the away team, 0 otherwise.
- Column T (HCA column): X[g, T] = 1 if the game is not at a neutral site, 0 if neutral site.

We minimize the sum of squared residuals:

```
min_{β,α}  ||X [β; α] − y||²
```

### Identifiability

The team-rating columns of X sum to zero for every row (each game has exactly one +1 and one −1), so the normal equations matrix **XᵀX** is singular: we can shift every β_i by the same constant without changing the predictions. A constraint is required to pin the solution.

**Chosen approach: L2 regularization on team ratings only.**

We add Tikhonov (ridge) regularization with a small penalty λ to the team rating columns:

```
(XᵀX + λ · D) [β; α] = Xᵀy
```

where D is a diagonal matrix that is 1 on the T team-rating diagonal entries and 0 on the HCA entry. This serves two purposes:

1. **Uniqueness**: Makes the system full-rank; the solution is the minimum-norm solution, which satisfies Σ β_i ≈ 0 when all teams play similar schedules.
2. **Regularization**: Shrinks ratings for teams with few games toward zero, reducing noise early in the season.

Recommended λ = 1.0, configurable.

### Interpretation

- β_i > 0: team i scores more points against an average opponent from an average location.
- β_h − β_a ≈ expected margin at a neutral site.
- β_h − β_a + α ≈ expected margin with team h at home.
- α ≈ average home court advantage in points (historically ~3–4 points in D-I).
- Rank teams by β_i descending.

---

## 2. Bradley-Terry Logistic Rating

### Model

Each team has a log-odds strength parameter θ_i. For a game between home team h and away team a, the probability the home team wins is:

```
P(home wins | h, a) = σ(θ_h − θ_a + α · home_indicator_g)
```

where σ(x) = 1 / (1 + e^(−x)) and α is a shared home court advantage in log-odds (suppressed for neutral-site games). The dependent variable y_g = 1 if the home team won, 0 if the away team won.

### Log-Likelihood

```
L(θ, α) = Σ_g [ y_g · log p_g + (1 − y_g) · log(1 − p_g) ]
```

### Identifiability and Regularization

Adding a constant to every θ_i does not change any p_g. We resolve this with L2 regularization:

```
L_reg(θ, α) = L(θ, α) − (λ/2) Σ_i θ_i²
```

The HCA parameter α is not penalized. Recommended λ = 0.1, configurable.

### Optimization

Gradient (with regularization):

```
∇_θ_i L_reg = Σ_{g: i is home} (y_g − p_g) − Σ_{g: i is away} (y_g − p_g) − λ θ_i
∇_α L_reg   = Σ_{g: non-neutral} (y_g − p_g)
```

Hessian (let w_g = p_g(1 − p_g)):

```
H[i, i]  = −Σ_{g: i plays} w_g − λ          (team diagonal)
H[i, j]  = +Σ_{g: i home, j away or vice versa} w_g   (off-diagonal, i ≠ j)
H[T, T]  = −Σ_{g: non-neutral} w_g           (HCA diagonal)
H[i, T]  = H[T, i] = −Σ_{g: i home, non-neutral} w_g + Σ_{g: i away, non-neutral} w_g
```

Newton-Raphson update (5–15 iterations from cold start, 1–3 when warm-started):

```
δ = H^{−1} · ∇L_reg
δ_j = clamp(δ_j, −2.0, +2.0)   (per-component step-size cap)
[θ; α]^(t+1) = [θ; α]^t − δ
```

The per-component step-size cap of ±2.0 log-odds units prevents Newton overshoot when the sigmoid saturates for extreme ratings. Without the cap, a team with a very large θ_i has w_g = p(1−p) ≈ 0 for all its games, making H[i,i] ≈ −λ and grad[i] ≈ −λ·θ_i. The uncapped Newton step is then δ_i = θ_i, collapsing the rating to zero in one iteration regardless of λ, followed by data pushing it back up — an oscillation that produces artificially round-looking values. The cap prevents this collapse while still allowing convergence to the true MLE.

A small stability nudge `H[T][T] -= 1e-6` is applied to the HCA diagonal to ensure the system remains invertible in degenerate cases where all observed games are neutral-site.

Convergence criterion: ||∇L_reg||₂ < 1e−6 or 500 iterations maximum.

### Interpretation

- θ_i > 0: team i is stronger than average.
- P(h beats a at neutral site) = σ(θ_h − θ_a).
- P(h beats a at home) = σ(θ_h − θ_a + α).
- α ≈ log-odds home advantage; exp(α) is the home court odds multiplier (~1.3–1.5 historically).
- Rank teams by θ_i descending.

---

## 3. Massey Total Points Rating

### Model

This is a variation on the standard Massey model (Section 1). Instead of predicting the **margin** between two teams, this model predicts the **total points scored** in a game. Both the home and away team receive a +1 in the design matrix (rather than +1 and −1), reflecting that both teams contribute positively to total scoring.

Each team has a scalar rating parameter β_i. For a game between home team h and away team a:

```
total_predicted = β_h + β_a + γ + δ · home_indicator_g
```

where:
- γ is an unpenalized intercept capturing the baseline scoring pace (average total points per game across all teams). Unlike the margin model, an explicit intercept is necessary here: regularization pulls all β_i toward zero, so without γ the model would predict total scores near zero rather than near the true mean (~140–150 points for D-I basketball).
- δ is an unpenalized HCA term for total scoring, suppressed for neutral-site games. This captures whether home games are systematically higher- or lower-scoring than neutral/away games.

The dependent variable y_g is the **total points scored** in the game:

```
y_g = homeScore_g + awayScore_g
```

The design matrix **X** is N × (T + 2):

- Column j (0 ≤ j < T): X[g, j] = +1 if team j plays in game g (home **or** away), 0 otherwise.
- Column T (intercept): X[g, T] = 1 for all games.
- Column T+1 (HCA column): X[g, T+1] = 1 if the game is not at a neutral site, 0 if neutral site.

We minimize the sum of squared residuals:

```
min_{β,γ,δ}  ||X [β; γ; δ] − y||²
```

### Identifiability

Every game row has exactly two +1s in the team columns, one +1 in the intercept, and 0 or 1 in the HCA column. Adding ε to all β_i and subtracting 2ε from γ leaves all predictions unchanged, creating a one-dimensional null space. L2 regularization on team ratings only resolves this:

```
(XᵀX + λ · D) [β; γ; δ] = Xᵀy
```

where D is diagonal: 1 on the T team entries, 0 on the intercept and HCA entries (both are unpenalized). Recommended λ same as standard Massey (configurable).

### Interpretation

- β_i > 0: team i is involved in higher-scoring games than average (they score more, allow more, or both).
- β_i < 0: team i is involved in lower-scoring games (slower pace, stronger defense, or both).
- γ ≈ average total points per game at a neutral site; β_h + β_a + γ ≈ predicted total for a neutral-site matchup.
- δ ≈ additional points associated with a non-neutral venue; expected to be small and positive if home games are faster-paced.
- **Rank teams by β_i descending** — higher β means involvement in higher-scoring games.

**Important caveat**: a high β_i does not necessarily indicate a strong team. A weak team with a poor defense may generate high-scoring games by allowing opponents to score freely. Conversely, an elite defensive team that controls pace may rank near the bottom of this model. This metric is best interpreted as a **scoring-pace index**, not a quality ranking. The UI labels it accordingly and displays it as a companion to — not a substitute for — the margin-based ratings.

### Incremental Accumulation

Same incremental structure as standard Massey, but the system size is T+2 and the feature vector per game is:

```
x_g[h]   = +1
x_g[a]   = +1           (both teams get +1, not +1/−1)
x_g[T]   = 1            (intercept, always 1)
x_g[T+1] = home_indicator_g    (1 if non-neutral, 0 if neutral)
```

Let `hca = neutralSite ? 0 : 1` and `total = homeScore + awayScore`. Accumulator updates per game g:

```
A[hi][hi]     += 1;      A[ai][ai]     += 1;
A[hi][ai]     += 1;      A[ai][hi]     += 1;      // +1, not −1
A[hi][T]      += 1;      A[T][hi]      += 1;
A[ai][T]      += 1;      A[T][ai]      += 1;
A[T][T]       += 1;
A[hi][T+1]   += hca;    A[T+1][hi]   += hca;
A[ai][T+1]   += hca;    A[T+1][ai]   += hca;
A[T][T+1]    += hca;    A[T+1][T]    += hca;      // intercept × HCA cross-term
A[T+1][T+1]  += hca;                               // hca² = hca since hca ∈ {0,1}
b[hi]   += total;   b[ai]   += total;
b[T]    += total;   b[T+1]  += hca * total;
```

Solve (A + λD) · x = b at each snapshot date. Extract ratings x[0..T-1], intercept γ = x[T], and HCA δ = x[T+1]. Both γ and δ are stored in `power_model_param_snapshots` as params `"intercept"` and `"hca_total"`.

---

## 4. Weighted Bradley-Terry Rating

### Model

This model is identical to the standard Bradley-Terry model (Section 2) in structure and optimization, except that each game observation is assigned a weight proportional to the **margin of victory**:

```
w_g = 1 + ln(|homeScore_g − awayScore_g|)
```

The log is natural (base-e). Since college basketball has no ties, |margin| ≥ 1, so w_g ≥ 1 + ln(1) = 1. Representative weights:

| Margin | Weight |
|--------|--------|
| 1      | 1.00   |
| 3      | 2.10   |
| 10     | 3.30   |
| 20     | 4.00   |
| 40     | 4.69   |

The weight is sub-linear in margin: a 40-point blowout is approximately 4.7× as informative as a 1-point game — not 40× as informative. This captures the intuition that large margins carry more signal about relative team strength than narrow margins, while preventing dominant blowouts from overwhelming the estimate. The natural-log scale provides sufficient diminishing returns without a weight cap.

### Weighted Log-Likelihood

```
L_w(θ, α) = Σ_g w_g · [ y_g · log p_g + (1 − y_g) · log(1 − p_g) ]
```

where p_g = σ(θ_h − θ_a + α · home_indicator_g) as before.

### Identifiability and Regularization

Same L2 regularization on team ratings (not α) as the standard model:

```
L_w_reg(θ, α) = L_w(θ, α) − (λ/2) Σ_i θ_i²
```

### Optimization

The gradient and Hessian are modified by per-game weights. Define:

```
v_g = w_g · p_g · (1 − p_g)    (weighted curvature)
r_g = w_g · (y_g − p_g)        (weighted residual)
```

**Gradient:**

```
∇_θ_i L_w_reg = Σ_{g: i home} r_g − Σ_{g: i away} r_g − λ θ_i
∇_α L_w_reg   = Σ_{g: non-neutral} r_g
```

**Hessian:**

```
H[i, i]  = −Σ_{g: i plays} v_g − λ
H[i, j]  = +Σ_{g: i home, j away or vice versa} v_g
H[T, T]  = −Σ_{g: non-neutral} v_g
H[i, T]  = H[T, i] = −Σ_{g: i home, non-neutral} v_g + Σ_{g: i away, non-neutral} v_g
```

All other aspects of the Newton-Raphson procedure are identical to the standard Bradley-Terry model: per-component step-size cap of ±2.0, HCA stability nudge, convergence criterion ||∇L_w_reg||₂ < 1e−6, 500-iteration maximum, warm-start from previous date's parameters.

The `GameEntry` struct used to accumulate seen games must additionally store the **integer margin** (|homeScore − awayScore|) so that w_g can be computed at each Newton iteration.

### Interpretation

- θ_i > 0: team i is stronger than average.
- Blowout wins count more than narrow wins in the likelihood.
- All else equal, a team that wins consistently by large margins will earn a higher θ_i in this model than in the unweighted BT — but the effect is moderated by the natural-log scale.
- Rank teams by θ_i descending.
- P(h beats a at neutral site) = σ(θ_h − θ_a), exactly as in standard BT.

---

## Comparison

| Property | Massey (Margin) | Bradley-Terry | Massey (Totals) | BT (Weighted) |
|---|---|---|---|---|
| Dependent variable | Score margin (continuous) | Win/loss (binary) | Total points (continuous) | Win/loss (binary) |
| Uses score information | Yes — full margin | No — outcome only | Yes — total only | Yes — margin as weight |
| Sensitive to blowouts | Yes | No | Yes (in totals, not margin) | Yes (sub-linearly via ln) |
| Predicted output | Expected point spread | Win probability | Expected total score | Win probability |
| Measures quality | Yes | Yes | Scoring pace (not quality) | Yes |
| HCA term | Yes (points) | Yes (log-odds) | Yes (points on total) | Yes (log-odds) |
| Per-date cost | Cholesky solve | 1–3 Newton steps | Cholesky solve | 1–3 Newton steps |

---

## Time-Series Design

All four models are computed on every game date in the season, using only games up to and including that date. This produces a table of (team, season, date, model, rating, rank) snapshots.

### Snapshot Granularity

Snapshots are generated for each distinct game date — dates on which at least one FINAL game exists. This matches the existing `team_season_stat_snapshots` pattern. With ~120–130 distinct game dates per D-I season, the total rows across all four models are approximately:

```
360 teams × 130 dates × 4 models ≈ 187,200 rows per season
```

### Minimum Game Threshold

Early in the season (first 1–2 weeks) ratings are dominated by regularization rather than data. Snapshots are still stored, but the snapshot includes a `gamesPlayed` field for the team so the UI can suppress or grey out ratings below a minimum (e.g., fewer than 3 games played by that team). No snapshot is written for a team on a date where they have played 0 games.

### Incremental Matrix Accumulation (Massey variants)

Rather than rebuilding **A = XᵀX** and **b = Xᵀy** from scratch at each date, both are maintained as cumulative accumulators. The update structure differs between the two Massey modes:

- **Massey Margin** (Section 1): size T+1; feature vector +1 for home, −1 for away, neutral-site HCA flag.
- **Massey Totals** (Section 3): size T+2; feature vector +1 for both teams, constant intercept, neutral-site HCA flag.

Each mode maintains its own A and b accumulators. At each snapshot date, solve (A + λD) · x = b. No rebuilding required.

### Warm-Start Newton-Raphson (Bradley-Terry variants)

The parameter vector [θ; α] from date d is used as the initial point at date d+1 for both BT variants. The weighted variant (Section 4) recomputes w_g = 1 + ln(|margin_g|) at the start of each iteration (fixed per game, not per Newton step) — negligible overhead.

### Team Index Stability

All teams that appear in any FINAL game in the season are discovered upfront and assigned a fixed index 0..T-1 before the date loop begins. This applies to all four models.

---

## Implementation Plan

### 1. Maven Dependency

Apache Commons Math 3 is not bundled with Spring Boot. Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-math3</artifactId>
    <version>3.6.1</version>
</dependency>
```

Provides: `Array2DRowRealMatrix`, `ArrayRealVector`, `CholeskyDecomposition`, `LUDecomposition`.

### 2. Database Migration: `V9__power_rating_snapshots.sql`

```sql
CREATE TABLE team_power_rating_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES teams(id),
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    model_type    VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    rating        DOUBLE PRECISION NOT NULL,
    rank          INTEGER,
    games_played  INTEGER NOT NULL,
    calculated_at TIMESTAMP NOT NULL,
    UNIQUE (team_id, season_id, model_type, snapshot_date)
);

CREATE INDEX idx_power_snapshots_season_model_date
    ON team_power_rating_snapshots (season_id, model_type, snapshot_date);

CREATE INDEX idx_power_snapshots_team_season_model
    ON team_power_rating_snapshots (team_id, season_id, model_type);

-- Model-level scalar parameters per date (e.g., home court advantage α, intercept γ)
CREATE TABLE power_model_param_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    season_id     BIGINT NOT NULL REFERENCES seasons(id),
    model_type    VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    param_name    VARCHAR(50) NOT NULL,
    param_value   DOUBLE PRECISION NOT NULL,
    calculated_at TIMESTAMP NOT NULL,
    UNIQUE (season_id, model_type, snapshot_date, param_name)
);
```

**`model_type` values** (all fit within VARCHAR(20)):

| UI Name | model_type string | Param snapshots |
|---------|-------------------|-----------------|
| Massey (Margin) | `MASSEY` | `hca` |
| Bradley-Terry | `BRADLEY_TERRY` | `hca` |
| Massey (Totals) | `MASSEY_TOTALS` | `intercept`, `hca_total` |
| Bradley-Terry (Weighted) | `BRADLEY_TERRY_W` | `hca` |

The "current" / final rating for a team is simply the row with the latest `snapshot_date` for that team in the season.

### 3. Entities

**`TeamPowerRatingSnapshot`** (`com.yotto.basketball.entity`):

Fields: `id`, `team` (ManyToOne lazy), `season` (ManyToOne lazy), `modelType` (String), `snapshotDate` (LocalDate), `rating` (Double), `rank` (Integer), `gamesPlayed` (int), `calculatedAt` (LocalDateTime).

Table: `team_power_rating_snapshots`. Unique constraint on `(team_id, season_id, model_type, snapshot_date)`.

**`PowerModelParamSnapshot`** (`com.yotto.basketball.entity`):

Fields: `id`, `season` (ManyToOne lazy), `modelType` (String), `snapshotDate` (LocalDate), `paramName` (String), `paramValue` (Double), `calculatedAt` (LocalDateTime).

Table: `power_model_param_snapshots`.

### 4. Repositories

**`TeamPowerRatingSnapshotRepository`**:

```java
List<TeamPowerRatingSnapshot> findBySeasonIdAndModelTypeAndSnapshotDate(
    Long seasonId, String modelType, LocalDate date);  // full leaderboard for one date

List<TeamPowerRatingSnapshot> findByTeamIdAndSeasonIdAndModelTypeOrderBySnapshotDateAsc(
    Long teamId, Long seasonId, String modelType);      // time series for one team

void deleteBySeasonIdAndModelType(Long seasonId, String modelType);

// Latest snapshot date per season+model (for "current" ratings):
@Query("SELECT MAX(s.snapshotDate) FROM TeamPowerRatingSnapshot s " +
       "WHERE s.season.id = :seasonId AND s.modelType = :modelType")
Optional<LocalDate> findLatestSnapshotDate(Long seasonId, String modelType);
```

**`PowerModelParamSnapshotRepository`**:

```java
List<PowerModelParamSnapshot> findBySeasonIdAndModelTypeOrderBySnapshotDateAsc(
    Long seasonId, String modelType);

void deleteBySeasonIdAndModelType(Long seasonId, String modelType);
```

### 5. Service: `MasseyRatingService` (parameterized)

```
com.yotto.basketball.service.MasseyRatingService
```

A single service handles both Massey variants via a `MasseyMode` enum:

```java
public enum MasseyMode { MARGIN, TOTALS }

@Transactional
public void calculateAndStoreForSeason(int seasonYear, MasseyMode mode)
```

The mode controls:
- **model_type string**: `"MASSEY"` (MARGIN) or `"MASSEY_TOTALS"` (TOTALS).
- **System size**: T+1 (MARGIN: T teams + 1 HCA column) or T+2 (TOTALS: T teams + intercept + HCA).
- **Feature vector construction** and **y_g**: margin vs. total (see Sections 1 and 3).
- **Param names** persisted to `power_model_param_snapshots`: `"hca"` (MARGIN) or `["intercept", "hca_total"]` (TOTALS).

**Algorithm** (both modes):

1. Load season; wipe existing snapshots for the model_type (`deleteBySeasonIdAndModelType`).
2. Load all FINAL games for the season. Sort by `gameDate.toLocalDate()` ascending.
3. Build a fixed team index: collect all distinct team IDs, sort for determinism, assign index 0..T-1.
4. Initialize accumulator `double[][] A = new double[size][size]`, `double[] b = new double[size]`, `Map<Long, Integer> gamesPlayedByTeam`.
5. Group games by date (via `LinkedHashMap`).
6. For each date d:
   - Append each game g to the accumulator using the update rules from Section 1 (MARGIN) or Section 3 (TOTALS).
   - Increment `gamesPlayedByTeam` for both teams.
   - Copy A to Areg; apply regularization `Areg[j][j] += lambda` for j=0..T-1.
   - Solve `Areg · x = b` via `CholeskyDecomposition` (fall back to `LUDecomposition` if needed).
   - Extract team ratings x[0..T-1] and scalar params from the remaining indices.
   - Compute ranks for teams with gamesPlayed > 0; persist `TeamPowerRatingSnapshot` and `PowerModelParamSnapshot` rows.
7. Batch-save all snapshot entities.

### 6. Service: `BradleyTerryRatingService` (parameterized)

```
com.yotto.basketball.service.BradleyTerryRatingService
```

A single service handles both BT variants via a `weighted` flag:

```java
@Transactional
public void calculateAndStoreForSeason(int seasonYear, boolean weighted)
```

The flag controls:
- **model_type string**: `"BRADLEY_TERRY"` (unweighted) or `"BRADLEY_TERRY_W"` (weighted).
- **`GameEntry` struct**: the weighted variant additionally stores `int margin = Math.abs(homeScore - awayScore)`.
- **Per-game weight**: unweighted uses `r = y_g - p_g` and `w = p_g*(1-p_g)` directly; weighted applies `wt = 1.0 + Math.log(entry.margin)` first, substituting `r_g = wt*(y_g - p_g)` and `v_g = wt*p_g*(1-p_g)` into the gradient and Hessian accumulation.

**Algorithm** (both modes):

1. Load season; wipe existing snapshots for the model_type.
2. Load, sort, and group FINAL games by date. Build fixed team index.
3. Initialize `double[] theta = new double[T]`, `double alpha = 0.0`, `Map<Long, Integer> gamesPlayedByTeam`, `List<GameEntry> seen`.
4. For each date d:
   - Append games from date d to `seen`; increment `gamesPlayedByTeam`.
   - Run Newton-Raphson over `seen`, warm-starting from current `[theta; alpha]`:
     - For each game g, compute p_g = sigmoid(theta[hi] - theta[ai] + alpha * nonNeutral_g).
     - Compute per-game weight wt (1.0 if unweighted; 1.0 + ln(margin) if weighted).
     - Accumulate gradient and Hessian using wt-scaled residuals and curvatures.
     - Apply regularization to grad and H diagonal for team entries.
     - Solve H · delta = grad via `LUDecomposition`; apply step-size cap ±2.0; update [theta; alpha].
     - Check convergence: ||grad||₂ < 1e−6 or 500 iterations.
   - Compute ranks; persist snapshots.
5. Batch-save all snapshots.

### 7. Orchestrator

`PowerRatingService` calls both parameterized services for all four models:

```java
@Transactional
public void calculateAndStoreForSeason(int seasonYear) {
    masseyRatingService.calculateAndStoreForSeason(seasonYear, MasseyMode.MARGIN);
    masseyRatingService.calculateAndStoreForSeason(seasonYear, MasseyMode.TOTALS);
    bradleyTerryRatingService.calculateAndStoreForSeason(seasonYear, false);
    bradleyTerryRatingService.calculateAndStoreForSeason(seasonYear, true);
}
```

Wire into:

- **Admin endpoint** `POST /admin/power-ratings/{year}` — triggers async recalculation via `@Async` wrapper (new `AsyncPowerRatingService` following the same pattern as `AsyncScrapeService`). Integrates with `ScrapeBatch` tracking so progress is visible in the admin scrape-history table.
- **Auto-trigger**: optionally invoke after each successful `scrapeCurrentSeason` call in `ScrapeOrchestrator`.

### 8. API Endpoints

```
GET /api/power-ratings/{year}/massey
    ?date=YYYY-MM-DD (optional; defaults to latest available date)
    → List<TeamPowerRatingSnapshotDto> ordered by rank

GET /api/power-ratings/{year}/bradley-terry
GET /api/power-ratings/{year}/massey-totals
GET /api/power-ratings/{year}/bradley-terry-weighted
    (same query parameters as massey)

GET /api/power-ratings/{year}/{model}/team/{teamId}
    → List<TeamPowerRatingSnapshotDto> ordered by snapshotDate (time series)

GET /api/power-ratings/{year}/{model}/dates
    → List<LocalDate> of available snapshot dates

GET /api/power-ratings/{year}/params
    → { massey: { hca: [...] }, bradleyTerry: { hca: [...] },
        masseyTotals: { intercept: [...], hcaTotal: [...] },
        bradleyTerryWeighted: { hca: [...] } }
```

`TeamPowerRatingSnapshotDto`: `teamId`, `teamName`, `teamLogoUrl`, `conference`, `rating`, `rank`, `gamesPlayed`, `snapshotDate`, `modelType`.

### 9. UI

**Leaderboard page** `/power-ratings`:

- Season selector and date slider (or date picker) to scrub through the season.
- All four models shown as rank tables on the main page. Suggested layout: three quality-model tables (Massey Margin, Bradley-Terry, BT Weighted) plus one clearly distinguished Pace Index table (Massey Totals).
- Quality-model columns: Rank, Team logo + name, Conference, Rating (2 dp), Rank change from previous week.
- Massey Totals displayed with the header **"Scoring Pace Index"** and a tooltip: *"Measures involvement in high-scoring games. A higher rating means this team's games tend to have more total points. This is not a measure of team quality."*

**Team detail page** (extend existing team page at `/teams/{id}`):

- Add a tab or section "Power Rating History" with line charts of all four models' ratings and ranks over the season.
- Chart data fetched from `/api/power-ratings/{year}/{model}/team/{teamId}`.

**Parameter evolution panel** (on the `/power-ratings` page):

- Chart showing how α evolves over the season for Massey (Margin), BT, and BT Weighted.
- For Massey Totals, show γ (intercept) and δ (hca_total) evolution as indicators of in-season scoring pace and home-venue effects on scoring.

---

## Edge Cases and Considerations

**Ties**: Ties cannot occur in college basketball regulation or overtime. Skip any game with homeScore == awayScore as a safety measure (affects Bradley-Terry variants only, which require binary outcome). Massey Totals is unaffected since y_g = total is well-defined for equal scores.

**Zero-margin game in Weighted BT**: if |margin| = 0 (a tie, which should be filtered), `ln(0)` is undefined. The tie filter prevents this. Add a guard in the weight computation (`Math.max(1, margin)`) as a defensive measure.

**Neutral-site flag null**: treat as non-neutral (conservative — assigns home court advantage). Applies to all four models since all now include an HCA or HCA-total term.

**Rank deficiency before first few games**: with λ > 0, all regularized systems are always full-rank.

**Season with no FINAL games**: all services return immediately after loading an empty game list.

**Teams appearing in only one game**: rating is almost entirely determined by regularization. The `gamesPlayed` field allows the UI to flag low-confidence ratings.

**Conference tournament / postseason**: included by default. Add a configurable flag `includePostseason` (default true) to optionally fit only regular-season games.

**Regularization parameters**: expose as `@ConfigurationProperties` under a `yotto.models.*` namespace:

```yaml
yotto:
  models:
    massey:
      lambda: 1.0
    massey-totals:
      lambda: 1.0
    bradley-terry:
      lambda: 0.1
    bradley-terry-weighted:
      lambda: 0.1
    min-games-for-snapshot: 1  # minimum games played for a team snapshot to be written
```

---

## Design Decisions

All design questions have been resolved as follows:

| Decision | Choice |
|----------|--------|
| Weighted BT log base | Natural log (ln) |
| Weighted BT weight cap | None — sub-linear ln growth is sufficient |
| HCA term in Massey Totals | Yes — δ term stored as `hca_total` param |
| Massey Totals display | Option A — shown on main `/power-ratings` page as "Scoring Pace Index" |
| Service code organization | Parameterized: single `MasseyRatingService(MasseyMode)`, single `BradleyTerryRatingService(weighted)` |
| Snapshot storage for all 4 models | Full time-series for all models (~187,200 rows/season) |
| UI model names | "Massey (Margin)", "Bradley-Terry", "Massey (Totals)", "Bradley-Terry (Weighted)" |
| New model status | Production grade — shown publicly by default |
