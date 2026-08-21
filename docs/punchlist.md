1)  ~~Individual conference page:~~
    * ~~correct standings, with power rankings on the standings~~
    * ~~conference tournament~~
    * ~~Average power ranking for conference, rank among conferences~~
    * ~~Record v non-conference~~
2) ~~Make sure all pages (except admin) are reachable via top menu; rationalization of menu~~
3) ~~Fix matchup page~~ (2026-08-21 menu reorganization: type-ahead pickers, one row
   per public model incl. Adjusted Efficiency, moved to /models/matchup)
4) ~~Improve game page~~
5) ~~Allow multiple ML models; Display model performance~~
6) ~~NCAA Bracket~~ 
7) ~~Calc more stats off game data~~
8) ~~Improve stats on team page~~
9) ~~Create Users~~
    * ~~create user/password~~
    * ~~reset password~~
    * ~~login/logout~~
    * ~~disable user~~
    * ~~profile~~
10) Simple model creation
11) ~~Scrape and~~ summarize news.
12) ~~Better landing page~~
    * ~~Off season~~
    * ~~Pre season 10/15 through 1st game~~
    * ~~In season~~
    * ~~NCAA Tourney~~
13) ~~Personalization~~    


# Random thoughts

## Model-improvement baselines (recorded 2026-08-18, post Phases 1–3 deploy + full rebuild)

Log loss (all seasons 2021–2026, all segments; paired same-game book_ll in parens):

| Model | n | Log loss | Spread MAE |
|---|---|---|---|
| ML:model-4 | 29,558 | 0.5095 (book 0.5364) | 8.870 |
| ML:baseline-plus | 30,426 | 0.5221 (0.5344) | 8.962 |
| BOOK closing line | 32,342 | 0.5251 | 8.828 |
| ML:baseline | 31,046 | 0.5280 (0.5319) | 9.108 |
| ML:model-3 | 31,046 | 0.5505 (0.5319) | 9.136 |
| MASSEY | 31,046 | 0.5628 (0.5319) | 9.387 |
| ADJ_EFF | 31,043 | 0.5629 (0.5319) | 9.387 |
| BRADLEY_TERRY | 31,046 | 0.6294 | — |
| BRADLEY_TERRY_W | 31,046 | 0.6868 | — |

Caveats: ML rows are heavily in-sample (models trained on most of these seasons) — the
"beats the book" readings are flattery, judge ML only on held-out seasons. BOOK at 0.525
is the honest bar. Classical models are genuinely out-of-sample.

λ sweep #1 (walk-forward, 31,031 games × grid, 9.5 min): best pooled λ=2.0 at 0.5626 vs
λ=1.0 at 0.5629 — a 0.0003 gap splitting 4–2 by season. NOT promoted (spec decision rule:
needs a stable cross-season margin). λ=1.0 confirmed on the flat part of the curve;
degradation starts at λ≥8. Residual σ ≈ 11.84 (vs margin-sigma 11.0) — possible small
calibration follow-up.

Ops note: full 6-season evaluation rebuild now takes ~10 min (SeasonPredictionCache);
was 2+ h/season before.
