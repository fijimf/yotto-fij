# Admin Manual — ML Models, Training & Evaluation

This manual walks through operating the ML prediction system from the admin
dashboard: what to click, in what order, and **exactly what you should see** at
each step. It covers the system shipped in the Phase 0–3 ML overhaul (commits
`5fa4034`, `59fed04`, `7235174`, `8e5adf5`; design rationale in
[docs/ML_SYSTEM_ANALYSIS.md](docs/ML_SYSTEM_ANALYSIS.md)).

---

## 0. Prerequisite: you must be running the new build

None of the UI described below exists in a build older than commit `59fed04`.
If you don't see an **ML Models** section on `/admin` with a models table and a
Train form, you are running an old build. Check both places:

**Local dev instance.** A long-running `java` process started from
`target/classes` days ago will happily keep serving the old code. Restart it:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN     # find the stale PID
kill <pid>
./mvnw clean package -DskipTests     # or just spring-boot:run, which recompiles
./scripts/start-postgres.sh          # if Postgres isn't already up
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway will apply migrations **V24–V27** on first startup of the new build
(watch the log for `Migrating schema ... to version "27"`). That is your
confirmation the new code is live.

**Server.** Deploy normally:

```bash
export DEPLOY_HOST=your-server
./scripts/deploy.sh
```

`deploy.sh` now also copies `Dockerfile.trainer`, `train_models.py`, and
`trainer_service.py`, builds the **trainer** container, and restarts the stack.
Afterwards verify on the server:

```bash
docker compose ps            # expect: db, app, trainer, nginx, goaccess, netdata, logrotate — all Up
docker compose logs trainer  # expect: "Uvicorn running on http://0.0.0.0:8000"
```

The trainer is on the internal Docker network only — it is **never** proxied
through nginx and has no published port. The app reaches it at
`http://trainer:8000` (config `prediction.ml.trainer-url` / env `ML_TRAINER_URL`).

**Note on local dev limitations.** Locally there is usually no `/models`
directory and no trainer container, so on `localhost:8080` expect the ML card
to say *"No models loaded"* and the Train button to fail with *"trainer service
unreachable"*. That is normal — training and model serving are exercised on the
server. Everything else (evaluation of Massey/Bradley–Terry/BOOK, the
performance page) works locally.

---

## 1. Mental model — the five moving parts

1. **Model bundles on disk** (`/models/<slug>/`, shared `model_data` volume):
   three ONNX files + `features.json` (the manifest: feature list, version,
   training metrics). The trainer writes them; the app loads them. Files are
   the *artifacts*.
2. **The `ml_models` registry** (database): the *serving state* — per slug:
   `ACTIVE` (shown to the public), `CANDIDATE` (shadow: evaluated and shown on
   the performance page, never in public predictions), or `RETIRED` (unloaded),
   plus exactly one **default** (★) that fills the headline ML prediction.
3. **The trainer service** (container): FastAPI app that runs
   `train_models.py` as a subprocess when the app POSTs to it. One run at a
   time.
4. **Evaluations** (`prediction_evaluations` table): one row per FINAL game per
   model — the model's *pre-game* prediction (rebuilt from rating snapshots
   dated strictly before tip-off, so it's leakage-free) and its error vs. the
   actual result. Models covered: Massey, Massey Totals, Bradley–Terry,
   Bradley–Terry (weighted), every ACTIVE/CANDIDATE ML model (`ML:<slug>`), and
   `BOOK` (the closing line, as a benchmark).
5. **The public performance page** (`/predictions/performance`): aggregates the
   evaluation rows into MAE/RMSE/winner-% tables and a calibration chart.

Because predictions are *recomputed retroactively* from snapshots, a model you
train today immediately gets a full-history backtest — no waiting for new games.

---

## 2. First-time rollout — exact sequence

Do these in order after the first deploy of the new build.

### Step 1 — Open `/admin`

**Expect:** a new **ML Models** section containing:
- a card with a *"No models loaded"* grey pill (nothing has been trained under
  the new bundle layout yet — unless your legacy flat `/models` files are
  present, in which case they appear as one model with slug **baseline**,
  auto-registered as **Active ★**);
- a *"No models registered yet — train one below"* message (or the baseline row);
- a **Train** form (model-name text box prefilled `baseline`, feature-set
  dropdown, Train button), an **Evaluate Predictions** button, and a **Rebuild
  Evaluations** button;
- below the card, a **Training Runs** heading with *"No training runs yet"*.
  This table auto-refreshes every 5 seconds.

### Step 2 — Click **Evaluate Predictions**

This backfills evaluations for the non-ML models (Massey, Massey Totals, both
Bradley–Terrys, BOOK) across every season, and for any already-loaded ML model.
It runs in the background; expect it to take a few minutes for all seasons.

**Expect:** a green flash message that evaluation was kicked off. When it
finishes, visit `/predictions/performance` (linked from the predictions page):
you should see, per season, three cards — **Spread** (MAE / RMSE / Winner %),
**Total** (adds Bias), and **Win Probability** (Brier + a calibration chart) —
with a greyed **Book Closing Line** row as the benchmark. Row counts ("Games")
should roughly match the number of FINAL games with ratings that season.

### Step 3 — Train the baseline model

In the Train form leave the name as `baseline`, leave feature set on **auto**
(auto picks the feature set registered under the model's name, falling back to
`baseline` = 27 features), and click **Train**.

**Expect:**
- Flash message: training started, with a run id.
- The Training Runs table shows a row: *Running* (amber), model `baseline`,
  train seasons and test season filled in, duration ticking up. Click **log**
  to watch the live log tail (feature build progress, skip percentages,
  XGBoost early-stopping output, walk-forward report).
- Training takes on the order of minutes (it loads every game with ratings,
  builds features, trains three models with early stopping, and runs a
  walk-forward backtest).
- On success the row flips to *Completed* (green) with
  `Spread <RMSE> RMSE · Brier <score> ✓ live`. The **✓ live** marker means the
  chain completed automatically: bundles hot-reloaded → registry reconciled →
  evaluation re-run over all seasons. You do **not** need to click anything
  else.
- The models table now shows **baseline** as **Active ★** with a fresh version
  timestamp and its test-set Spread RMSE / Brier. (The first model ever
  registered is auto-promoted to Active + default.)
- A few minutes later, `/predictions/performance` grows a **Baseline (ML)**
  row in each table, listed first — backfilled over *all* seasons.

If training **fails** (red *Failed* chip), expand **log** — the most common
cause is the data-quality guard: the trainer aborts if more than 30% of games
are skipped for missing ratings, which points at a season needing a re-scrape
or stats recalculation.

### Step 4 — Train the richer model as a shadow candidate

Enter model name `pace-v2`, feature set **pace-v2 (41 features)** (or leave on
auto — the name matches a registered feature set), click **Train**.

Prerequisite: pace-v2 uses box-score-derived stats (pace, efficiencies, eFG%,
turnover rate) and RPI, so **game stats must have been scraped** and the stats
pipeline run for the seasons you train on. Games without box-score snapshots
are skipped in training and get *no* pace-v2 prediction (a null-marker
evaluation row) — no silent imputation.

**Expect:** same run lifecycle as step 3, but when it completes the models
table shows **pace-v2** with a blue **Candidate** chip. Candidates are shadow
models: they appear on the performance page and get full evaluation rows, but
are **not** shown in any public prediction. Baseline remains the ★ default.

### Step 5 — Compare, then promote

On `/predictions/performance`, compare `Pace V2 (ML)` vs `Baseline (ML)` vs
`Book Closing Line` on spread MAE/RMSE, winner %, Brier, and the calibration
chart — across a full season and the *Last 30 days* window. They are scored on
the identical set of games, so the comparison is apples-to-apples.

When the candidate wins, in the models table click:
- **Activate** — makes it public alongside baseline (both appear on game pages);
- **Make default** — moves the ★, so it fills the headline ML prediction
  site-wide;
- **Retire** (on the loser) — removes it from serving and from future
  evaluation (its historical rows remain).

**Expect:** each click redirects back with the table updated; status chips and
the ★ move accordingly. **Reinstate** brings a retired model back as a
candidate.

---

## 3. The ML Models card — every control

| Control | What it does | What to expect |
|---|---|---|
| **Reload Models** | Rescans the model directory, reloads all bundles, reconciles the registry. Use after copying bundle files manually. | Pill updates to "N models loaded"; flash lists per-bundle load results. |
| **Model table** | One row per registered model: display name, `(slug · feature-set)`, ★ on the default, status chip, bundle version (UTC timestamp), test-set Spread RMSE and Brier from training. | A red *"not loaded"* note next to the status means the registry row exists but the bundle files are missing on disk. |
| **Train form** | POSTs to the trainer service. Name: 1–40 chars, lowercase/digits/hyphens. Training under an **existing** name retrains/overwrites that model (new version); a **new** name creates a new Candidate. | One run at a time — starting a second returns "already in progress". |
| **Evaluate Predictions** | Incremental evaluation, all seasons, background. Scores games that are newly FINAL or whose ML rows were written by an older model version. Safe to click any time; a no-op if nothing is stale. | Flash message; rows appear/refresh on the performance page minutes later. |
| **Rebuild Evaluations** | Deletes **all** evaluation rows and recomputes every season from scratch (confirm dialog). Only needed after fixing bad historical data (ratings rebuilt, odds corrected). | Longer background run; performance page briefly shows partial counts while it fills back in. |
| **Make default / Activate / Retire / Reinstate** | Lifecycle mutations on the registry (section 2, step 5). | Immediate redirect; table reflects the change. There is always exactly one default. |

## 4. The Training Runs table

Auto-polls every 5 s (this poll is also what detects run completion — keep the
dashboard open during a run, or completion is picked up on your next visit).

- **Running** (amber) — subprocess alive in the trainer; duration ticks;
  **log** shows the live tail (last ~80 lines).
- **Completed** (green) — shows test metrics; **✓ live** confirms
  reload + re-evaluation fired.
- **Failed** (red) — error message inline, full cause in **log**. A run whose
  trainer became unreachable is kept as Running for up to 2 hours, then marked
  Failed.

## 5. What happens automatically (no clicks needed)

- **After every scrape** (scheduled 12-hourly cycle or manual): the stats-calc
  block ends with an incremental evaluation pass for that season, so games that
  just went FINAL get scored. This is the steady state during the season.
- **After every completed training run**: bundle reload → registry reconcile →
  full incremental re-evaluation (the new version makes that model's rows stale
  everywhere, so it gets a fresh full-history backtest).
- **At app startup**: bundles on disk are loaded and reconciled with the
  registry (new slug on disk → auto-registered; first ever → Active ★ default).

Manual **Evaluate Predictions** is only needed when you can't wait for the next
scrape, or after rebuilding historical ratings.

## 6. Where results appear publicly

- **`/predictions/performance`** — season + window (full season / last 30 days)
  selectors; Spread, Total, and Win Probability cards; ML models listed first,
  book line last in grey; calibration chart (predicted vs. actual home-win rate
  by decile). Candidates appear here — this page is the shadow-comparison tool.
- **Game detail page** — a *Model Predictions* card for upcoming/final games:
  one row per **Active** model (★ on the default) with predicted spread/total/
  win probability, and for FINAL games the actual result and whether the
  favored side covered. Reminder on signs: `betting_odds.spread` is the
  handicap (negative = home favored); model spreads are predicted home margins.
- **Predictions list** — the headline prediction comes from the ★ default
  model; edge vs. the book line where odds exist.

## 7. Troubleshooting

| Symptom | Cause & fix |
|---|---|
| No ML Models section on `/admin` at all | Old build running — see section 0. |
| "trainer service unreachable" on Train | Trainer container not up: `docker compose up -d trainer`, check `docker compose logs trainer`. Locally: expected (no trainer). |
| "training run … already in progress" | One-at-a-time lock (app DB row or trainer 409). Wait, or check Training Runs for a stuck Running row (auto-fails after 2 h if the trainer forgot it). |
| Run Failed: "aborting: N% of games skipped" | Missing power-rating snapshots for a training season — re-scrape / recalc stats for it, then retrain. |
| Model row says *not loaded* | Registry row exists but no bundle on disk (volume wiped or bad copy) — retrain under that slug, or Retire it. |
| Model missing from performance page | Retired models aren't evaluated; Candidates/Actives appear only after evaluation ran post-training — check for the ✓ live marker or click Evaluate Predictions. |
| pace-v2 shows far fewer Games than baseline | Expected where box-score snapshots are missing (no imputation). Backfill game stats for those seasons if you want coverage. |
| Rebuilt historical ratings but metrics unchanged | Evaluations don't watch rating tables — click **Evaluate Predictions** (or **Rebuild** to re-score the already-evaluated games too). |

## 8. Shell fallbacks (server)

```bash
# One-shot CLI training (bypasses the service; same script, same output layout)
./scripts/retrain.sh

# Poke the trainer directly from inside the network
docker compose exec app wget -qO- http://trainer:8000/health

# Inspect bundles on the shared volume
docker compose exec trainer ls -R /models
```
