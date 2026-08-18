# Improving the Prediction Models

This document is a prioritized plan for making our game predictions better — and, just as
importantly, for making sure we can *tell* they're better. It came out of a working session on
modeling strategy and has been annotated with where the codebase already stands on each item.

The thesis, in one line: **fix the measurement first, then improve the model.** Tier 1 is about
scoring ourselves honestly. Tier 2 is where the actual predictive gains live. Tier 3 is real but
optional polish.

## Notation, once

A few symbols recur throughout:

- **p** — the probability our model assigns to the home team winning a particular game.
- **q** — the *true* (unknowable) probability the home team wins.
- **μ** — an expected home margin of victory, in points (negative = home team expected to lose).
- **Φ** — the standard normal CDF, i.e. "the probability a bell-curve draw lands below this
  value." `Φ(μ/σ)` converts an expected margin into a win probability.
- **σ** — the standard deviation of actual margins around the expected margin. For college
  basketball this is empirically about **10.5–11 points**. Intuition: a 7-point favorite is
  roughly a `Φ(7/11) ≈ 74%` favorite to win.

---

## Tier 1 — Do these first (days of work, largest payoff)

None of these make predictions better. They make it impossible to fool ourselves about whether
anything in Tier 2 or Tier 3 actually works. That's why they come first.

### 1. Score predictions with log loss, not accuracy

**The problem with accuracy.** If we grade a model on "did it pick the winner," a game we called
at 51% and a game we called at 98% count exactly the same. Accuracy is a step function at
p = 0.5 — it throws away everything the model said about *how confident* it was. Worse, it
rewards a model for shoving every prediction toward 0 or 1.

**What log loss is.** When the home team wins, the model scores **−log(p)**; when it loses,
**−log(1−p)**. Confidently right costs almost nothing; confidently wrong is punished brutally
(predicting 98% on a loss costs −log(0.02) ≈ 3.9, about eleven times the price of a coin-flip
call). Lower is better.

**Why it can't be gamed.** Log loss is a *strictly proper scoring rule*: if the true win
probability is q, the expected penalty −[q·log p + (1−q)·log(1−p)] is minimized only at p = q.
The best strategy under log loss is to report your honest belief — there is no way to profit by
shading forecasts up or down. Accuracy has no such property.

**Practical consequence.** A leaky or overfit model that "predicts" 98% on ordinary games will
post an absurd log loss the moment it's evaluated honestly out of sample. This metric is the
smoke detector for everything that follows.

**Where we stand.** `prediction_evaluations` already stores `predicted_home_win_prob` and the
outcome per game per model, so we have everything needed. The performance page currently reports
**Brier score** (mean squared probability error) — also a proper scoring rule, so we're not
flying blind — but log loss punishes overconfidence much more sharply, and it's the lingua
franca for comparing against published numbers (KenPom, the market, academic papers).
**Work: add a log-loss aggregate alongside Brier in `PredictionEvaluationRepository` and surface
it on `/predictions/performance`.** Small.

### 2. Benchmark against the closing line

The Vegas closing line is the strongest publicly available forecast — it's the aggregated opinion
of everyone willing to bet money. Every model we build should be scored on the same games, with
the same metric, next to the market. That number is the bar.

Two ways to turn the market's numbers into a win probability:

- **From the moneyline (preferred):** the two moneylines imply probabilities directly; remove
  the bookmaker's margin (the "vig") by normalizing them to sum to 1.
- **From the spread (fallback):** a spread of s points for the home team implies a win
  probability of `Φ(−s/σ)` with σ ≈ 10.5–11. (Minus sign because spreads are quoted in handicap
  orientation: negative = home favored.)

**Calibration targets** (per-game log loss on D1 games, lower is better):

| Log loss | Meaning |
|---|---|
| ~0.55–0.58 | The closing line. Nobody public consistently beats this. |
| ~0.60–0.62 | A good rating system. This is the realistic goal. |
| ≥0.65 | Work to do. |
| ≤0.54 | You have a bug (leakage). Investigate, don't celebrate. |

**Where we stand.** Already built, and better than the suggestion: `PredictionEvaluationService`
writes a `BOOK` row per game using the **de-vigged moneyline pair** for win probability and the
sign-corrected spread for margin. **Work: nothing structural — once log loss exists (item 1),
the market benchmark comes for free.** Optionally add the `Φ(−s/σ)` fallback for games that have
a spread but no moneylines.

### 3. Walk-forward validation only

To evaluate a model on past seasons, train on everything through date t, predict the games of
date t+1, advance one day, repeat. **Never random k-fold splits** — shuffling games across time
lets February information leak into November predictions, and college basketball teams change
enormously within a season. Walk-forward is how the model will actually be used live, so it's
the only honest simulation of it.

This isn't a modeling improvement. It's the instrument that tells us whether anything else in
this document is real.

**Where we stand.** Largely done. `PredictionEvaluationService` builds evaluations retroactively
from the daily *snapshot* time series — each prediction uses only ratings as of the day before
the game — and `scripts/train_models.py` already produces a walk-forward report for ML models.
**Work: none, beyond vigilance.** Any new model must join this pipeline rather than inventing
its own evaluation.

---

## Tier 2 — The actual modeling wins

### 4. Pace adjustment: think per-possession, not per-game

Points per game conflates two different things: "this team is efficient" and "this team plays
fast." Virginia under Bennett scoring 60 in a 58-possession rockfight is a *better* offense than
a run-and-gun mid-major scoring 80 in 78 possessions — but per-game stats say the opposite. The
pace spread in college basketball is enormous, so this one change removes a large confound for
free.

Possessions aren't in the box score, but a standard estimate is:

```
possessions ≈ FGA − ORB + TO + 0.475·FTA
```

(Each possession ends in a shot, a turnover, or trip to the line; offensive rebounds extend a
possession rather than starting a new one; the 0.475 accounts for and-ones and multi-shot
fouls.) Then express everything **per 100 possessions**.

**Where we stand.** Done. `BoxScoreStatCalculator` uses exactly this estimator, per-100
efficiencies flow into the snapshot pipeline, and the `pace-v2`+ ML feature sets consume them.

### 5. Opponent-adjusted efficiency via ridge regression — the big one

Raw per-possession efficiency still doesn't account for *who you played*. Scoring 110 per 100
against Houston's defense is heroic; against a bottom-50 defense it's a Tuesday. The fix is to
estimate every team's offensive and defensive strength *simultaneously*, letting the schedule
sort out who inflated their numbers on cupcakes.

**The model.** Each game with box scores produces two observations — each team's points per 100
possessions:

```
efficiency ≈ μ + oᵢ − dⱼ + h·(home indicator)
```

where μ is league-average efficiency, oᵢ is team i's offensive rating, dⱼ is opponent j's
defensive rating (higher = stronger defense, it subtracts from your output), and h is home-court
advantage. Stack all ~10,000 game-observations of a season into a matrix and solve the penalized
least-squares problem:

```
β̂ = (XᵀX + λI)⁻¹ Xᵀy
```

That's it — one linear solve gives every D1 team an offensive and defensive rating on a common
scale, with strength of schedule automatically netted out. This is the same family of idea
KenPom's adjusted efficiencies live in.

**Why the ridge penalty λ is not a detail.** X is essentially an incidence matrix of the
schedule graph (teams are nodes, games are edges), which makes XᵀX close to that graph's
Laplacian. Here's the basketball reality that matters: mid-major conferences connect to the rest
of D1 through only a handful of November non-conference games. After January, the SWAC plays the
SWAC. The schedule graph is *nearly disconnected* into conference clusters, and near-disconnected
graphs have some tiny eigenvalues — directions in rating-space (roughly, "shift this whole
conference up or down together") that the data barely pins down. The plain least-squares inverse
*blows up* along exactly those directions: a couple of flukey November results can swing an
entire conference's ratings wildly. The λI term shrinks those poorly-determined directions
toward zero — toward "assume average until proven otherwise" — while leaving well-measured
directions almost untouched. **λ should be tuned by walk-forward log loss** (Tier 1 pays off
already), not guessed.

**Where we stand.** Mostly built, which was a pleasant surprise from the audit:
`AdjustedEfficiencyRatingService` implements this exact model — same equation, same possessions
estimator, per-day incremental fits, snapshots as `ADJ_OFF`/`ADJ_DEF`. Two gaps:

1. **λ is a hardcoded constant (1.0), never tuned.** The paragraph above says this is where a
   chunk of our gap to state-of-the-art lives. Work: a walk-forward sweep over λ, judged by log
   loss.
2. **The adjusted efficiencies feed the ML models as features (`eff-v4`) but don't make
   predictions on their own.** There's no `ADJ` row in `prediction_evaluations` the way MASSEY
   and BRADLEY_TERRY have rows. Work: derive a predicted margin from
   (adjusted efficiencies × expected pace) and register it as a first-class model in the
   evaluation pipeline, so we can see how much the ML layer adds over the ratings themselves.

### 6. Predict the margin first, then convert to win probability

Don't model "win or lose" directly — model the **expected point differential** μ, then convert:

```
win probability = Φ(μ/σ),  σ ≈ 10.5–11
```

Margins of victory are approximately normally distributed around their expectation. Estimating a
*location* (how many points better is team A tonight?) uses all the information in every final
score; classifying wins throws away the difference between a 2-point escape and a 30-point
blowout. Margin-first models calibrate better, and they let us fit with ordinary squared error
while still *reporting* log loss per Tier 1.

**Where we stand.** The power models are already margin-first, and the ML trainer supports both
a direct win classifier and a margin-derived probability (`winprobMode classifier|derived`).
**Work: once log loss is on the performance page, compare the two winprob modes head-to-head and
let the derived mode win if the math above is right.**

---

## Tier 3 — Real, but diminishing returns

Do these after Tier 2, not before. Both extract signal that mostly overlaps what the ridge model
already captures.

### 7. Hierarchical Bayesian team strengths

Ridge regression is secretly already a Bayesian method: it's the posterior mode under a Gaussian
prior that every team is average. Going *fully* Bayesian buys three concrete things:

- **Per-team uncertainty.** A mid-major with 4 meaningful non-conference games gets a wide
  posterior ("we genuinely don't know"); Duke, with 20 games against quality opponents, gets a
  narrow one. Useful for both prediction and honest UI.
- **Strength that drifts through the season** (a random walk), instead of one static rating —
  teams that gel in February get credit for it.
- **Smarter shrinkage targets:** shrink a team toward its *conference's* mean rather than the
  global mean. This is the classic James–Stein result — shrinking noisy estimates toward a group
  mean beats the raw estimates in expected error, essentially always.

**Where we stand.** Not started, and correctly so — this is sequenced after λ tuning proves out.

### 8. LRMC (Logistic Regression / Markov Chain rankings)

Build a Markov chain over teams: each game moves probability mass from loser toward winner, with
the amount determined by a logistic function of the margin. Rank teams by the chain's stationary
distribution — the long-run share of "belief" that random walks through the schedule graph
settle on each team. LRMC was designed at Georgia Tech for exactly the NCAA connectivity problem
described in item 5.

**Honest assessment:** solid method, fun to have on the site as another ranking column, but it
mostly re-extracts the signal the ridge model already has. Lowest priority.

---

## Suggested order of attack

| Step | Item | Size | Payoff |
|---|---|---|---|
| 1 | Log loss on the performance page (item 1) | Small | Unlocks everything below |
| 2 | Confirm BOOK benchmark under log loss (item 2) | Tiny | The bar to measure against |
| 3 | Walk-forward λ sweep for adjusted efficiencies (item 5.1) | Medium | Likely the biggest single accuracy gain |
| 4 | ADJ as a first-class prediction model (item 5.2) | Medium | Shows what the ML layer really adds |
| 5 | Classifier vs. margin-derived winprob shootout (item 6) | Small | Free calibration win |
| 6 | Hierarchical Bayes (item 7) | Large | Uncertainty + in-season drift |
| 7 | LRMC (item 8) | Medium | A fun extra ranking, little accuracy |
