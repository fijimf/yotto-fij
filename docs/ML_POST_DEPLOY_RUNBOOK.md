# ML Overhaul — Post-Deploy Runbook

*Written 2026-07-27, for the Phase 0–4 changes of
[ML_IMPROVEMENT_PLAN.md](ML_IMPROVEMENT_PLAN.md) (commits `64078da`/`8bc687b` +
`a4c2a04`). Work through this top to bottom — the order matters. Record every
number you're asked to record in the plan doc's results log; that log is the
source of truth for whether each candidate earned its keep.*

**The scoreboard to beat** (2026 out-of-sample, pre-overhaul `ML:baseline-plus`
vs. book): spread MAE **9.286** vs 8.917 · total MAE **13.893** vs 13.372 ·
Brier **0.1881** vs 0.1779 · win acc **70.5%** vs 72.2%.

## Checklist

- [ ] 0. Deploy + smoke checks
- [ ] 1. Retrain `baseline` and `baseline-plus`; rebuild evaluations *(plan 1.5)*
- [ ] 2. Promote `baseline-plus` to default *(plan 0.5)*
- [ ] 3. Backfill ADJ efficiency ratings: Power Ratings 2021→2026 *(plan 3.4a)*
- [ ] 4. Train + judge `prior-v3` candidate *(plan 2.6)*
- [ ] 5. Train + judge `eff-v4` candidate *(plan 3.4b)*
- [ ] 6. A/B grid: residual / derived / decay / tuned; 2021 ablation *(plan 4.6)*
- [ ] 7. Champion selection + registry cleanup
- [ ] 8. Dev follow-ups (docs, backfills, deferred work)

Steps 1–2 are quick (≈15 min total including training time). Step 3 is a few
minutes per season. Steps 4–6 are spread over days because judgment improves as
shadow evaluations accumulate — but the retroactive backtest gives you a full
verdict on day one; live 2027 games only strengthen it.

---

## 0. Deploy + smoke checks

```bash
export DEPLOY_HOST=fijimf.com
./scripts/deploy.sh
```

Notes on what this deploy does beyond the usual:

- **Flyway V30** runs on app start (adds `train_seasons`/`test_season` to
  `ml_models`) — automatic, no action.
- **The trainer image is rebuilt** (deploy.sh does `docker compose build trainer`)
  — this picks up the new `requirements.txt` (**optuna**). If a later `--tune` run
  fails with `ModuleNotFoundError: optuna`, the trainer image didn't rebuild;
  rerun `docker compose build trainer && docker compose up -d trainer` on the
  server.

Smoke checks (2 minutes):

1. `/admin` loads; the ML card shows **2 models loaded** and the table now has
   **WF RMSE / WF Brier** columns with values (the deployed manifests already
   carry the `walk_forward` block, so the means populate on first reconcile).
2. The train form shows the new controls: feature sets up to `eff-v4 (77)`,
   `target:`/`winprob:` selects, `tune`/`decay` inputs.
3. `/predictions/performance` renders. **No in-sample badges yet** — expected:
   the deployed bundles' manifests predate `train_seasons`. They appear after
   step 1.
4. `/predictions` still shows ML predictions (old bundles keep serving —
   backward compatibility is by design).

**Known temporary skew until step 1 completes:** the serving side now computes
season-scoped rolling windows, but the deployed bundles were trained on
cross-season windows. This is precisely why step 1 happens immediately after
deploying. Don't linger between steps 0 and 1.

---

## 1. Retrain both existing bundles + rebuild evaluations *(plan 1.5)*

From the admin ML card (one run at a time — the trainer 409s a second request):

1. Train: slug **`baseline`**, feature set **`baseline`**, all new options left
   at defaults. Wait for COMPLETED on the polled status card (a 6-season
   training run takes on the order of a few minutes; completion auto-reloads
   bundles and kicks off incremental evaluation).
2. Train: slug **`baseline-plus`**, feature set **`pace-v2`**, defaults.
3. After both complete: **Rebuild Evaluations** (the confirm-dialog button).
   This deletes and recomputes all ~34k games × all models. It will take
   noticeably longer than pre-overhaul rebuilds once prior-v3/eff-v4 bundles are
   loaded (extra gated queries); at this point only baseline bundles are loaded,
   so expect it to be comparable to before. Progress is visible in the scrape
   history/log.

What changed under these retrains (Phase 1 hygiene): season-scoped rolling
windows, non-D-I target filter (nearly a no-op — 11 games across six seasons),
OT-free totals fit, ±30 spread winsorization.

**Verify:**

- `/predictions/performance` now shows **in-sample badges** on the ML rows for
  2021–2025 (and the explanatory note under the disclaimer); 2026 is unbadged.
- The **vs. Closing Line** card renders with ATS/O-U/CLV columns.
- Admin table shows fresh WF means for both models.

**Record in the plan doc results log:** for each of `baseline`/`baseline-plus` —
2026 spread MAE/RMSE, total MAE, Brier, win acc (from the performance page with
year=2026), plus the WF means. Compare against the scoreboard above.

- Expected: **neutral to slightly better.** Hygiene is about correctness.
- **Stop rule:** if `baseline-plus` 2026 spread MAE worsens by more than ~0.1,
  stop and investigate before continuing (check the training run's log tail in
  the admin card for anomalous skip counts — season skips should be a few
  hundred per season, non-D-I ≈ 0–8).

---

## 2. Promote `baseline-plus` to default *(plan 0.5)*

Admin ML table → **Make default** on `baseline-plus` (it wins every season and
was never the default).

**Verify:** `/predictions` headline numbers switch to Baseline Plus; the
by-conference card on the performance page defaults to `Baseline Plus (ML)`.

---

## 3. Backfill adjusted efficiency ratings *(plan 3.4a)*

The new `AdjustedEfficiencyRatingService` runs automatically inside every
post-scrape stats block from now on, but historical seasons need one explicit
pass. From the admin dashboard, run **Power Ratings** for each season
**2021, 2022, 2023, 2024, 2025, 2026** (async; the ADJ solve is a 2T+2 system,
so expect each season to take a couple of minutes — watch scrape history).

**Verify** (server, `docker compose exec db psql -U $POSTGRES_USER -d $POSTGRES_DB`):

```sql
SELECT s.year, r.model_type, count(*) AS rows, count(DISTINCT r.snapshot_date) AS dates
FROM team_power_rating_snapshots r JOIN seasons s ON r.season_id = s.id
WHERE r.model_type IN ('ADJ_OFF','ADJ_DEF')
GROUP BY s.year, r.model_type ORDER BY s.year, r.model_type;
```

Expect both model types present for every season with roughly the same row
counts as MASSEY (box coverage is 100% everywhere, verified 2026-07-26). Sanity:

```sql
SELECT t.name, r.rating FROM team_power_rating_snapshots r
JOIN teams t ON t.id = r.team_id JOIN seasons s ON r.season_id = s.id
WHERE r.model_type='ADJ_OFF' AND s.year=2026
  AND r.snapshot_date = (SELECT max(snapshot_date) FROM team_power_rating_snapshots r2
                         WHERE r2.model_type='ADJ_OFF' AND r2.season_id=r.season_id)
ORDER BY r.rating DESC LIMIT 10;
```

The top-10 should read like an actual offensive-efficiency leaderboard. If it
reads like a pace leaderboard instead, something is wrong — stop and flag it.

---

## 4. Train + judge the `prior-v3` candidate *(plan 2.6)*

Admin train form: slug **`prior-v3`**, feature set **`prior-v3`**, defaults.
It arrives as **CANDIDATE**: shadow-evaluated into `prediction_evaluations`
(and the performance page) but never shown publicly. Completion auto-runs
incremental evaluation, which backfills `ML:prior-v3` rows for **all six
seasons** — the day-one retroactive backtest.

Check the run's log tail while it trains: per-season kept-rows should be ≈92%
(ratings skips a few hundred, box skips a few hundred — resid/rpi/stddev
gaps — per season).

**Judge on three things, in priority order:**

1. **Walk-forward means** (admin table): must beat `baseline-plus`'s WF RMSE
   and WF Brier.
2. **2026 out-of-sample rows** (performance page, year=2026 — the unbadged
   season): spread MAE, Brier, win acc vs `baseline-plus`. The priors thesis
   predicts the biggest gain in November–December — check the **Month by
   Month** chart for exactly that shape.
3. **vs. Closing Line card**: Δ MAE vs book, ATS.

**Decision:** better on 1 AND 2 → **Activate** (public, alongside the default);
optionally **Make default** if the margin is convincing. Worse → leave as
CANDIDATE for now (it keeps shadow-scoring; retire it during step 7 cleanup).
Record the numbers either way.

---

## 5. Train + judge the `eff-v4` candidate *(plan 3.4b)*

Only after step 3 (it needs the ADJ series). Admin form: slug **`eff-v4`**,
feature set **`eff-v4`**, defaults. Judge exactly as in step 4. The eff-v4
thesis targets **totals** (pace-independent scoring signal) — pay attention to
total MAE and totals bias; the pre-overhaul model under-predicted 2026 totals
by +3.68 points on average.

---

## 6. The Phase-4 A/B grid *(plan 4.6)*

Take the best feature set from steps 4–5 (call it `BEST`; examples below assume
`eff-v4`). Each variant is its own slug so they coexist as shadow candidates and
share the same retroactive backtest. Suggested grid, one training run each
(sequential — the trainer is single-run):

| Slug | Form settings | Tests |
|---|---|---|
| `eff-v4-resid` | target: residual | residual learning alone |
| `eff-v4-derived` | winprob: derived | consistent winprob alone |
| `eff-v4-rd` | residual + derived | the combination |
| `eff-v4-rd-d90` | residual + derived + decay 0.9 | recency weighting |
| `eff-v4-rd-d80` | residual + derived + decay 0.8 | stronger recency |

Judgment identical to step 4 (WF means first, 2026 OOS second). Notes:

- Derived-winprob candidates ship **no** `winprob_model.onnx` — expected, not
  an error. Their calibration curve on the performance page is worth a look:
  Φ(spread/σ) should be smooth and well-calibrated.
- The **decay** variants speak directly to the 2026 environment-shift finding —
  watch the totals bias.

**Then one tuned run** for the best surviving configuration: same form settings
plus **tune: 100**. Warn: the objective refits the spread model per trial ×
per walk-forward fold — expect this run to take a few hours. The trainer runs
async; just leave it. Best params land in the manifest and the admin metrics.

**2021 ablation** (needs explicit seasons, which the admin form doesn't expose —
run the one-shot CLI on the server):

```bash
cd /home/admin/deepfij
docker compose run --rm --entrypoint python trainer train_models.py \
  --train-seasons 2022,2023,2024,2025,2026 --test-season 2026 \
  --model-name eff-v4-no2021 --feature-set eff-v4 \
  --spread-target residual_massey --winprob-mode derived   # match your best config
# then reload from the admin card (Reload Models) or:
curl -sf --user "$ADMIN_USERNAME:$ADMIN_PASSWORD" -X POST http://localhost/admin/ml/reload
```

If dropping 2021 is neutral-or-better on walk-forward, prefer the shorter
window for future retrains (2021 is the COVID-adjacent season with the worst
data and the membership gap).

---

## 7. Champion selection + cleanup

- Promote exactly one champion (**Make default**); **Activate** at most one or
  two others you actually want publicly visible (each ACTIVE model adds rows to
  public prediction payloads).
- **Retire** every losing candidate (files and history are kept; retired models
  stop being loaded and evaluated). Don't leave a pile of CANDIDATEs — every
  evaluable bundle is scored on every game of every evaluation pass.
- Rerun **Rebuild Evaluations** once at the end so the performance page's
  history reflects final model versions everywhere.
- Record the final champion's numbers in the plan doc results log **and**
  update the §5 table in [ML_MODELS_REVIEW_2026-07.md](ML_MODELS_REVIEW_2026-07.md)
  with the new honest 2026 (and early-2027, once the season starts) figures.

**Rollback at any point:** old bundle directories under `/models/` are never
deleted by promotion — demote by promoting the previous champion back; retire
the bad model. App-level rollback is a normal `git revert` + deploy; V30 is
additive so no down-migration concerns.

---

## 8. Dev follow-ups (after the ops queue)

In rough priority order:

1. **Update `ADMIN_MANUAL.md`.** It's the operator runbook for the ML system and
   predates all of this. It should now cover: the WF vs Test columns and
   "promote on walk-forward" rule, in-sample badges, the vs-book card, the four
   feature sets, the new train-form options (target/winprob/tune/decay), ADJ
   power ratings, and the champion/retire lifecycle expectations.
2. **2021/2022 conference-membership backfill.** Whole conferences (Pac-12,
   C-USA) lack membership rows in those seasons — discovered in Phase 1, it
   distorts per-conference performance views, RPI, and conference z-scores for
   those years. Path: re-run the standings scrape for 2021/2022 (a full-season
   re-scrape includes it), then re-run stats + Power Ratings + Rebuild
   Evaluations for those seasons. Check `ConferenceNameHistory` handling for the
   defunct Pac-12 branding while you're there.
3. **Watch the first live-season evaluations (Nov 2027 games).** The priors and
   season-scoped windows change early-season behavior the most; the Month-by-
   Month chart in Nov/Dec 2026-27 season is the first genuinely
   out-of-development evidence. Also watch **CLV** on the vs-book card — it
   converges much faster than ATS.
4. **Phase 4.5 (deferred): quantile heads** — `reg:quantileerror` q10/q90 spread
   models, manifest-listed, UI intervals ("−3.2 ± 9"). Do this once a champion
   is stable; the plan doc has the sketch.
5. **Scheduled auto-retraining** (old follow-up from ML_SYSTEM_ANALYSIS):
   a config-gated weekly retrain of the champion slug hooked after the stats
   block, now trivial via the trainer service.
6. **Per-user model selector** on the predictions page (`UserPreference`) — the
   other old follow-up, more attractive now that several models may be ACTIVE.
7. **Housekeeping:** local dev DB has scratchpad-generated ADJ snapshots
   (season 2026) from smoke testing — harmless, but a local admin "Power
   Ratings" run replaces them with the real service's output. The `.venv/` is
   gitignored; `scripts/tests` run with `.venv/bin/python -m pytest scripts/tests -q`.
8. **Memory/docs hygiene:** once the champion is picked, consider a short
   `docs/` note (or update the review doc) stating which config won and why, so
   the next model iteration starts from evidence rather than archaeology.
