# Power Rating Models

This document describes two regression-based power rating models for college basketball teams. Unlike raw statistics, these models produce a single ordinal strength rating per team per season by simultaneously fitting all game results to a common scale.

Both models are fit only on FINAL games (homeScore and awayScore non-null). Neutral-site games are included but the home court advantage term is suppressed for them. Both are computed as **time series**: the model is re-fit for every game date in the season using all games up to and including that date, producing a snapshot of ratings per team per date that shows how rankings evolve over the course of the season.

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

The HCA parameter α is not penalized. Recommended λ = 0.01, configurable.

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
[θ; α]^(t+1) = [θ; α]^t − H^{−1} · ∇L_reg
```

Convergence criterion: ||∇L_reg||₂ < 1e−6 or 50 iterations maximum.

### Interpretation

- θ_i > 0: team i is stronger than average.
- P(h beats a at neutral site) = σ(θ_h − θ_a).
- P(h beats a at home) = σ(θ_h − θ_a + α).
- α ≈ log-odds home advantage; exp(α) is the home court odds multiplier (~1.3–1.5 historically).
- Rank teams by θ_i descending.

---

## Comparison

| Property | Massey (Linear) | Bradley-Terry (Logistic) |
|---|---|---|
| Dependent variable | Score margin (continuous) | Win/loss (binary) |
| Uses score information | Yes — full margin | No — outcome only |
| Sensitive to blowouts | Yes | No |
| Predicted output | Expected point spread | Win probability |
| Per-date cost | Matrix update + Cholesky | Hessian update + 1–3 Newton steps |

---

## Time-Series Design

Both models are computed on every game date in the season, using only games up to and including that date. This produces a table of (team, season, date, model, rating, rank) snapshots that show how each team's power rating evolves across the season.

### Snapshot Granularity

Snapshots are generated for each distinct game date — dates on which at least one FINAL game exists. This matches the existing `team_season_stat_snapshots` pattern. With ~120–130 distinct game dates per D-I season, the total rows are approximately:

```
360 teams × 130 dates × 2 models ≈ 93,600 rows per season
```

### Minimum Game Threshold

Early in the season (first 1–2 weeks) ratings are dominated by regularization rather than data. Snapshots are still stored, but the snapshot includes a `gamesPlayed` field for the team so the UI can suppress or grey out ratings below a minimum (e.g., fewer than 3 games played by that team). No snapshot is written for a team on a date where they have played 0 games.

### Incremental Matrix Accumulation (Massey)

Rather than rebuilding **A = XᵀX** and **b = Xᵀy** from scratch at each date, both are maintained as cumulative accumulators. Each game g adds a rank-2 outer-product update:

```
Let x_g ∈ R^{T+1}: x_g[h_g] = +1, x_g[a_g] = −1, x_g[T] = home_indicator_g

A += x_g · x_gᵀ      (T+1 symmetric update, only 4 entries change: (h,h), (a,a), (h,a), (a,h),
                       plus up to 4 HCA cross-entries when home_indicator_g = 1)
b += x_g · y_g
```

At each snapshot date, solve (A + λD) · x = b. No rebuilding required — only the T+1-dimensional linear solve is repeated per date. This makes per-date cost O(T³) for the Cholesky factor, which for T = 360 is ~47M floating point operations — well under 10ms per date.

Because A grows monotonically (positive semidefinite updates), the Cholesky factorization is always valid after the first few games have been added.

### Warm-Start Newton-Raphson (Bradley-Terry)

The parameter vector [θ; α] from date d is used as the initial point for the Newton-Raphson solve at date d+1. Because new games only marginally perturb the log-likelihood landscape, the warm-started solution typically converges in 1–3 Newton steps instead of 5–15. This makes per-date cost approximately:

```
O(T² · G_d)   — assemble the accumulated Hessian for the new games
O(T³)         — LU decomposition of the T+1 Hessian
× 1–3 iterations
```

The accumulated Hessian at date d is the Hessian of L_reg evaluated at the current parameters θ^(d). After new games arrive, the parameters are no longer exactly optimal, so the Hessian must be recomputed at the new parameters. In practice, recomputing the full Hessian over all games up to date d takes O(T² · N_d) — where N_d is total games so far. This is still fast (N_d ≤ 5000, T = 360) but is the dominant cost. If performance is a concern, the Hessian can also be maintained incrementally with warm-start parameter updates as an optimization, but this is not required for initial implementation.

### Team Index Stability

All teams that appear in any FINAL game in the season are discovered upfront and assigned a fixed index 0..T-1 before the date loop begins. This keeps A and b at a stable (T+1) × (T+1) size throughout. Teams that have not yet played on a given date will have a rating pulled to 0 by regularization; no snapshot row is written for them until they have at least 1 game.

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
    model_type    VARCHAR(20) NOT NULL,   -- 'MASSEY' or 'BRADLEY_TERRY'
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

-- Model-level scalar parameters per date (e.g., home court advantage α)
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

### 5. Service: `MasseyRatingService`

```
com.yotto.basketball.service.MasseyRatingService
```

```java
@Transactional
public void calculateAndStoreForSeason(int seasonYear)
```

**Algorithm:**

1. Load season; wipe existing MASSEY snapshots for the season (`deleteBySeasonIdAndModelType`).
2. Load all FINAL games (non-null scores) for the season. Sort by `gameDate.toLocalDate()` ascending.
3. Build a fixed team index: collect all distinct team IDs from all games in the season, sort for determinism, assign index 0..T-1. Let T = number of teams, size = T+1.
4. Initialize accumulator matrix `double[][] A = new double[size][size]` and `double[] b = new double[size]`. Initialize `Map<Long, Integer> gamesPlayedByTeam`.
5. Group games by date (preserving order via `LinkedHashMap`), exactly as in `StatisticsTimeSeriesService`.
6. For each date d:
   - For each game g on date d:
     - Increment `gamesPlayedByTeam` for both teams.
     - Let hi = index of home team, ai = index of away team, hca = neutralSite ? 0 : 1.
     - margin = homeScore − awayScore.
     - Update A (only the (hi,hi), (ai,ai), (hi,ai), (ai,hi) entries, plus the 4 HCA cross-entries if hca=1 and the HCA diagonal):
       ```
       A[hi][hi] += 1;  A[ai][ai] += 1;
       A[hi][ai] -= 1;  A[ai][hi] -= 1;
       A[hi][T]  += hca; A[T][hi]  += hca;
       A[ai][T]  -= hca; A[T][ai]  -= hca;
       A[T][T]   += hca * hca;    // 1 for non-neutral, 0 for neutral
       b[hi] += margin;  b[ai] -= margin;
       b[T]  += hca * margin;
       ```
   - Apply regularization: form `double[][] Areg = copy(A)` then `Areg[j][j] += lambda` for j=0..T-1.
   - Solve `Areg · x = b` using `CholeskyDecomposition`. If Cholesky fails (matrix not yet positive definite — unlikely with λ > 0 but possible with very few games), fall back to `LUDecomposition`.
   - Extract ratings `x[0..T-1]` and HCA α = `x[T]`.
   - Compute ranks: sort teams with gamesPlayed > 0 by rating descending, assign rank 1-based.
   - Persist `TeamPowerRatingSnapshot` for each team with gamesPlayed > 0 at date d.
   - Persist `PowerModelParamSnapshot` for param `hca` at date d.
7. Batch-save all snapshot entities.

**Copy vs. in-place:** A deep copy of A is needed before adding λ, so the accumulator A stays clean for the next date. Since A is (T+1)×(T+1) = 361×361, copying it takes ~130K double assignments — negligible.

### 6. Service: `BradleyTerryRatingService`

```
com.yotto.basketball.service.BradleyTerryRatingService
```

```java
@Transactional
public void calculateAndStoreForSeason(int seasonYear)
```

**Algorithm:**

1. Load season; wipe existing BRADLEY_TERRY snapshots.
2. Load, sort, and group FINAL games by date (same as Massey). Build fixed team index.
3. Initialize `double[] theta = new double[T]` (team parameters), `double alpha = 0.0` (HCA), `Map<Long, Integer> gamesPlayedByTeam`.
4. Maintain a list of all games seen so far: `List<GameEntry>` where `GameEntry` holds `(homeTeamIdx, awayTeamIdx, homeWon, isNonNeutral)`. This list is appended to on each date.
5. For each date d:
   - Append games from date d to the seen-games list.
   - Increment `gamesPlayedByTeam` for both teams in each game.
   - Run Newton-Raphson over the current seen-games list, warm-starting from current `[theta; alpha]`:
     - **Iteration**: for each game in seen list, compute p_g = sigmoid(theta[hi] - theta[ai] + alpha * nonNeutral_g).
     - Compute gradient `double[] grad = new double[T+1]` and Hessian `double[][] H = new double[T+1][T+1]`.
     - For each game g:
       ```
       r = y_g - p_g   (residual; y_g=1 if home won, 0 otherwise)
       w = p_g * (1 - p_g)
       grad[hi] += r;  grad[ai] -= r;
       grad[T]  += r * nonNeutral_g;
       H[hi][hi] -= w;  H[ai][ai] -= w;
       H[hi][ai] += w;  H[ai][hi] += w;
       H[T][T]   -= w * nonNeutral_g;
       H[hi][T]  -= w * nonNeutral_g;  H[T][hi] -= w * nonNeutral_g;
       H[ai][T]  += w * nonNeutral_g;  H[T][ai] += w * nonNeutral_g;
       ```
     - Apply regularization: `grad[j] -= lambda * theta[j]` for j=0..T-1; `H[j][j] -= lambda` for j=0..T-1.
     - Compute Newton step: solve `H · delta = grad` via `LUDecomposition`. (H is negative definite; negate if needed for Cholesky: solve `−H · delta = −grad`.)
     - Update `theta[j] += delta[j]`, `alpha += delta[T]`.
     - Check convergence: `||grad||₂ < 1e-6` or max 50 iterations.
   - Compute ranks; persist snapshots as in Massey step 6.
6. Batch-save all snapshots.

**Note on seen-games list growth:** By the end of the season, iterating over N ≈ 5000 games per Newton iteration, with 1–3 iterations per date and 130 dates, is approximately 5000 × 3 × 130 = 1.95M game-iteration operations — fast, well under 1 second total.

### 7. Orchestrator

Add `PowerRatingService` that calls both models:

```java
@Transactional
public void calculateAndStoreForSeason(int seasonYear) {
    masseyRatingService.calculateAndStoreForSeason(seasonYear);
    bradleyTerryRatingService.calculateAndStoreForSeason(seasonYear);
}
```

Wire into:

- **Admin endpoint** `POST /admin/power-ratings/{year}` — triggers async recalculation via `@Async` wrapper in `AsyncScrapeService` (or a new `AsyncPowerRatingService` following the same pattern). Integrates with `ScrapeBatch` tracking so progress is visible in the admin scrape-history table.
- **Auto-trigger**: optionally invoke after each successful `scrapeCurrentSeason` call in `ScrapeOrchestrator`.

### 8. API Endpoints

```
GET /api/power-ratings/{year}/massey
    ?date=YYYY-MM-DD (optional; defaults to latest available date)
    → List<TeamPowerRatingSnapshotDto> ordered by rank

GET /api/power-ratings/{year}/bradley-terry
    ?date=YYYY-MM-DD (optional)
    → List<TeamPowerRatingSnapshotDto> ordered by rank

GET /api/power-ratings/{year}/massey/team/{teamId}
    → List<TeamPowerRatingSnapshotDto> ordered by snapshotDate (time series)

GET /api/power-ratings/{year}/bradley-terry/team/{teamId}
    → List<TeamPowerRatingSnapshotDto> ordered by snapshotDate (time series)

GET /api/power-ratings/{year}/massey/dates
    → List<LocalDate> of available snapshot dates

GET /api/power-ratings/{year}/params
    → { massey: { hca: [...] }, bradleyTerry: { hca: [...] } } time series of HCA
```

`TeamPowerRatingSnapshotDto`: `teamId`, `teamName`, `teamLogoUrl`, `conference`, `rating`, `rank`, `gamesPlayed`, `snapshotDate`, `modelType`.

### 9. UI

**Leaderboard page** `/power-ratings`:

- Season selector and date slider (or date picker) to scrub through the season.
- Two side-by-side rank tables (Massey | Bradley-Terry), updating via HTMX on date change.
- Columns: Rank, Team logo + name, Conference, Rating (2 dp), Rank change from previous week.

**Team detail page** (extend existing team page at `/teams/{id}`):

- Add a tab or section "Power Rating History" with a line chart of Massey and Bradley-Terry ratings over the season.
- Add a second line chart showing rank (inverted axis) over the season.
- Chart data fetched from `/api/power-ratings/{year}/{model}/team/{teamId}`.

**HCA evolution panel** (on the `/power-ratings` page):

- Small chart showing how α evolves over the season for both models — validates convergence and shows whether home court advantage shifts meaningfully in-season.

---

## Edge Cases and Considerations

**Ties**: Ties cannot occur in college basketball regulation or overtime. Skip any game with homeScore == awayScore as a safety measure (affects Bradley-Terry only, which requires binary outcome).

**Neutral-site flag null**: treat as non-neutral (conservative — assigns home court advantage). Only a small fraction of games have a null value.

**Rank deficiency before first few games**: with λ > 0, the regularized system is always full-rank. No special handling needed; early-season ratings are simply near-zero for all teams (little data, regularization dominates).

**Season with no FINAL games**: the service returns immediately after loading an empty game list.

**Teams appearing in only one game**: their rating is almost entirely determined by regularization (≈ 0) and the single opponent. The `gamesPlayed` field on the snapshot allows the UI to flag low-confidence ratings.

**Conference tournament / postseason**: included by default. Add a configurable flag `includePostseason` (default true) to optionally fit only regular-season games.

**Regularization parameters**: expose as `@ConfigurationProperties` under a `yotto.models.*` namespace:

```yaml
yotto:
  models:
    massey:
      lambda: 1.0
    bradley-terry:
      lambda: 0.01
    min-games-for-snapshot: 1  # minimum games played for a team snapshot to be written
```
