#!/usr/bin/env python3
"""
Train Massey + Bradley-Terry feature-augmented ML models and export to ONNX.

Reads game data directly from PostgreSQL (same DB as the Spring Boot app).
Writes spread_model.onnx, total_model.onnx, winprob_model.onnx, and features.json
to the output directory (default /models — the Docker volume mount).

Usage (inside Docker Compose):
    docker compose --profile training run --rm trainer \\
        --train-seasons 2023,2024,2025 \\
        --test-season 2025

Usage (local development):
    python train_models.py \\
        --train-seasons 2023,2024,2025 \\
        --test-season 2025 \\
        --db-url postgresql://user:pass@localhost:5432/basketball_db \\
        --output-dir ./models
"""

import argparse
import bisect
import json
import os
import shutil
import sys
import tempfile
import time
from datetime import datetime, date, timedelta

import numpy as np
import pandas as pd
import psycopg2
from psycopg2.extras import RealDictCursor
from sklearn.calibration import CalibratedClassifierCV
from sklearn.pipeline import Pipeline
from sklearn.metrics import brier_score_loss, root_mean_squared_error
from skl2onnx import convert_sklearn, update_registered_converter
from skl2onnx.common.data_types import FloatTensorType
from skl2onnx.common.shape_calculator import (
    calculate_linear_classifier_output_shapes,
    calculate_linear_regressor_output_shapes,
)
from xgboost import XGBClassifier, XGBRegressor
from onnxmltools.convert.xgboost.operator_converters.XGBoost import convert_xgboost

# Register XGBoost converters with skl2onnx (required; not automatic)
update_registered_converter(
    XGBRegressor,
    "XGBoostXGBRegressor",
    calculate_linear_regressor_output_shapes,
    convert_xgboost,
)
update_registered_converter(
    XGBClassifier,
    "XGBoostXGBClassifier",
    calculate_linear_classifier_output_shapes,
    convert_xgboost,
    options={"nocl": [True, False], "zipmap": [True, False, "columns"]},
)

# ── Constants ──────────────────────────────────────────────────────────────────

FEATURE_NAMES = [
    "massey_beta_home", "massey_beta_away", "massey_beta_diff",
    "massey_gamma_home", "massey_gamma_away", "massey_gamma_sum",
    "bt_theta_home", "bt_theta_away", "bt_logodds",
    "bt_theta_weighted_home", "bt_theta_weighted_away", "bt_logodds_weighted",
    "home_win_pct_l5", "home_avg_margin_l5", "home_avg_total_l5", "home_margin_stddev_l5",
    "away_win_pct_l5", "away_avg_margin_l5", "away_avg_total_l5", "away_margin_stddev_l5",
    "home_games_played", "away_games_played",
    "home_days_rest", "away_days_rest", "season_week",
    "is_neutral_site", "is_conference_game",
]
N_FEATURES = len(FEATURE_NAMES)  # 27


# ── Database helpers ───────────────────────────────────────────────────────────

def build_db_url(args):
    if args.db_url:
        return args.db_url
    host = os.environ.get("DB_HOST", "localhost")
    port = os.environ.get("DB_PORT", "5432")
    dbname = os.environ.get("POSTGRES_DB", "basketball_db")
    user = os.environ.get("POSTGRES_USER", "postgres")
    password = os.environ.get("POSTGRES_PASSWORD", "")
    return f"postgresql://{user}:{password}@{host}:{port}/{dbname}"


def load_games(conn, season_years):
    """Load all FINAL games for the given seasons with scores and season start dates."""
    placeholders = ",".join(["%s"] * len(season_years))
    sql = f"""
        SELECT
            g.id AS game_id,
            g.game_date::date AS game_date,
            g.home_team_id,
            g.away_team_id,
            g.home_score,
            g.away_score,
            g.neutral_site,
            g.conference_game,
            s.id AS season_id,
            s.year AS season_year,
            s.start_date AS season_start_date
        FROM games g
        JOIN seasons s ON g.season_id = s.id
        WHERE g.status = 'FINAL'
          AND g.home_score IS NOT NULL
          AND g.away_score IS NOT NULL
          AND s.year IN ({placeholders})
        ORDER BY g.game_date
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, season_years)
        return pd.DataFrame(cur.fetchall())


def load_massey_snapshots(conn, season_years):
    """Load MASSEY and MASSEY_TOTAL snapshots for the given seasons."""
    placeholders = ",".join(["%s"] * len(season_years))
    sql = f"""
        SELECT
            r.team_id,
            r.season_id,
            r.model_type,
            r.snapshot_date,
            r.rating,
            r.games_played
        FROM team_power_rating_snapshots r
        JOIN seasons s ON r.season_id = s.id
        WHERE r.model_type IN ('MASSEY', 'MASSEY_TOTAL')
          AND s.year IN ({placeholders})
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, season_years)
        return pd.DataFrame(cur.fetchall())


def load_bt_snapshots(conn, season_years):
    """Load BRADLEY_TERRY snapshots for the given seasons."""
    placeholders = ",".join(["%s"] * len(season_years))
    sql = f"""
        SELECT
            r.team_id,
            r.season_id,
            r.model_type,
            r.snapshot_date,
            r.rating,
            r.games_played
        FROM team_power_rating_snapshots r
        JOIN seasons s ON r.season_id = s.id
        WHERE r.model_type IN ('BRADLEY_TERRY', 'BRADLEY_TERRY_W')
          AND s.year IN ({placeholders})
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, season_years)
        return pd.DataFrame(cur.fetchall())


def load_hca_params(conn, season_years):
    """Load HCA params for MASSEY, MASSEY_TOTAL, BRADLEY_TERRY."""
    placeholders = ",".join(["%s"] * len(season_years))
    sql = f"""
        SELECT
            p.season_id,
            p.model_type,
            p.param_name,
            p.snapshot_date,
            p.param_value
        FROM power_model_param_snapshots p
        JOIN seasons s ON p.season_id = s.id
        WHERE s.year IN ({placeholders})
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, season_years)
        return pd.DataFrame(cur.fetchall())


# ── Feature engineering ────────────────────────────────────────────────────────

def build_snapshot_index(df):
    """
    Pre-build a binary-search index from a combined snapshot DataFrame.

    Returns dict: (team_id, season_id, model_type) -> (dates, ratings, games_played)
    where each value is a trio of parallel sorted lists, ordered by snapshot_date.
    Replaces O(N) full-scan lookups in latest_before() with O(log k) bisect lookups.
    """
    groups = {}
    for row in df.itertuples(index=False):
        key = (int(row.team_id), int(row.season_id), row.model_type)
        if key not in groups:
            groups[key] = []
        groups[key].append((row.snapshot_date, float(row.rating), int(row.games_played)))
    index = {}
    for key, entries in groups.items():
        entries.sort()  # sort by (date, ...) — date is always the primary key
        index[key] = (
            [e[0] for e in entries],
            [e[1] for e in entries],
            [e[2] for e in entries],
        )
    return index


def lookup_snapshot(index, team_id, season_id, model_type, cutoff_date):
    """Return (rating, games_played) of the latest snapshot strictly before cutoff_date, or None."""
    result = index.get((team_id, season_id, model_type))
    if result is None:
        return None
    dates, ratings, games_played = result
    pos = bisect.bisect_left(dates, cutoff_date)
    if pos == 0:
        return None
    return ratings[pos - 1], games_played[pos - 1]


def build_param_index(df):
    """
    Pre-build a binary-search index from an HCA param DataFrame.

    Returns dict: (season_id, model_type, param_name) -> (dates, values)
    where each value is a pair of parallel sorted lists.
    """
    if df.empty:
        return {}
    groups = {}
    for row in df.itertuples(index=False):
        key = (int(row.season_id), row.model_type, row.param_name)
        if key not in groups:
            groups[key] = []
        groups[key].append((row.snapshot_date, float(row.param_value)))
    index = {}
    for key, entries in groups.items():
        entries.sort()
        index[key] = ([e[0] for e in entries], [e[1] for e in entries])
    return index


def lookup_param(index, season_id, model_type, param_name, cutoff_date):
    """Return the latest param value strictly before cutoff_date, or 0.0."""
    result = index.get((season_id, model_type, param_name))
    if result is None:
        return 0.0
    dates, values = result
    pos = bisect.bisect_left(dates, cutoff_date)
    if pos == 0:
        return 0.0
    return values[pos - 1]


def build_team_game_index(games_df):
    """
    Pre-build per-team sorted game lists for fast rolling-stats lookups.

    Returns dict: team_id -> (dates, home_scores, away_scores, home_team_ids)
    where each value is a quartet of parallel lists sorted by game_date.
    Replaces O(N) full-scan + sort in rolling_stats() with O(log k) bisect lookups.
    """
    groups = {}
    for row in games_df.itertuples(index=False):
        entry = (row.game_date, int(row.home_score), int(row.away_score), int(row.home_team_id))
        for tid in (int(row.home_team_id), int(row.away_team_id)):
            if tid not in groups:
                groups[tid] = []
            groups[tid].append(entry)
    index = {}
    for tid, entries in groups.items():
        entries.sort()
        index[tid] = (
            [e[0] for e in entries],
            [e[1] for e in entries],
            [e[2] for e in entries],
            [e[3] for e in entries],
        )
    return index


def rolling_stats_fast(team_game_index, team_id, cutoff_date, n=5):
    """
    Compute rolling stats for a team's last n games strictly before cutoff_date.

    Uses binary search on a pre-sorted per-team list — O(log k) per call vs
    the original O(total_games) full scan + sort.
    Returns (win_pct, avg_margin, avg_total, margin_stddev, days_rest), or all-None on cold start.
    """
    result = team_game_index.get(team_id)
    if result is None:
        return None, None, None, None, None
    dates, home_scores, away_scores, home_team_ids = result
    pos = bisect.bisect_left(dates, cutoff_date)
    if pos == 0:
        return None, None, None, None, None
    start = max(0, pos - n)
    margins, totals, wins = [], [], 0
    for i in range(start, pos):
        h, a = home_scores[i], away_scores[i]
        margin = (h - a) if home_team_ids[i] == team_id else (a - h)
        if margin > 0:
            wins += 1
        margins.append(margin)
        totals.append(h + a)
    n_actual = len(margins)
    avg_margin = sum(margins) / n_actual
    avg_total  = sum(totals)  / n_actual
    stddev = (sum((m - avg_margin) ** 2 for m in margins) / (n_actual - 1)) ** 0.5 if n_actual > 1 else 0.0
    days_rest = (cutoff_date - dates[pos - 1]).days
    return wins / n_actual, avg_margin, avg_total, stddev, days_rest


def build_feature_row(row, team_game_index, snapshot_index, param_index):
    """
    Build a feature vector for one game (namedtuple from itertuples).
    Returns None if any required rating snapshot is unavailable.
    """
    game_date  = row.game_date
    season_id  = int(row.season_id)
    home_id    = int(row.home_team_id)
    away_id    = int(row.away_team_id)
    neutral    = bool(row.neutral_site) if pd.notna(row.neutral_site) else False
    conference = bool(row.conference_game) if pd.notna(row.conference_game) else False

    # ── Massey spread ratings ──────────────────────────────────────────────────
    snap_mh = lookup_snapshot(snapshot_index, home_id, season_id, "MASSEY", game_date)
    snap_ma = lookup_snapshot(snapshot_index, away_id, season_id, "MASSEY", game_date)
    if snap_mh is None or snap_ma is None:
        return None
    beta_home, home_gp = snap_mh
    beta_away, away_gp = snap_ma
    massey_hca = 0.0 if neutral else lookup_param(param_index, season_id, "MASSEY", "hca", game_date)

    # ── Massey total ratings ───────────────────────────────────────────────────
    snap_th = lookup_snapshot(snapshot_index, home_id, season_id, "MASSEY_TOTAL", game_date)
    snap_ta = lookup_snapshot(snapshot_index, away_id, season_id, "MASSEY_TOTAL", game_date)
    if snap_th is None or snap_ta is None:
        return None
    gamma_home = snap_th[0]
    gamma_away = snap_ta[0]

    # ── Bradley-Terry ratings ──────────────────────────────────────────────────
    snap_bh = lookup_snapshot(snapshot_index, home_id, season_id, "BRADLEY_TERRY", game_date)
    snap_ba = lookup_snapshot(snapshot_index, away_id, season_id, "BRADLEY_TERRY", game_date)
    if snap_bh is None or snap_ba is None:
        return None
    theta_home = snap_bh[0]
    theta_away = snap_ba[0]
    bt_alpha   = 0.0 if neutral else lookup_param(param_index, season_id, "BRADLEY_TERRY", "hca", game_date)
    bt_logodds = theta_home - theta_away + bt_alpha

    # ── Weighted Bradley-Terry ratings ────────────────────────────────────────
    snap_bwh = lookup_snapshot(snapshot_index, home_id, season_id, "BRADLEY_TERRY_W", game_date)
    snap_bwa = lookup_snapshot(snapshot_index, away_id, season_id, "BRADLEY_TERRY_W", game_date)
    if snap_bwh is None or snap_bwa is None:
        return None
    theta_w_home = snap_bwh[0]
    theta_w_away = snap_bwa[0]
    bt_w_alpha   = 0.0 if neutral else lookup_param(param_index, season_id, "BRADLEY_TERRY_W", "hca", game_date)
    bt_logodds_w = theta_w_home - theta_w_away + bt_w_alpha

    # ── Rolling features ───────────────────────────────────────────────────────
    h_win_pct, h_avg_margin, h_avg_total, h_stddev, h_rest = rolling_stats_fast(team_game_index, home_id, game_date)
    a_win_pct, a_avg_margin, a_avg_total, a_stddev, a_rest = rolling_stats_fast(team_game_index, away_id, game_date)
    if h_win_pct is None or a_win_pct is None:
        return None

    # ── Season week ────────────────────────────────────────────────────────────
    season_week = int((game_date - row.season_start_date).days / 7) + 1

    return [
        beta_home, beta_away, beta_home - beta_away,
        gamma_home, gamma_away, gamma_home + gamma_away,
        theta_home, theta_away, bt_logodds,
        theta_w_home, theta_w_away, bt_logodds_w,
        h_win_pct, h_avg_margin, h_avg_total, h_stddev,
        a_win_pct, a_avg_margin, a_avg_total, a_stddev,
        home_gp, away_gp,
        h_rest if h_rest is not None else -1,
        a_rest if a_rest is not None else -1,
        season_week,
        1.0 if neutral else 0.0,
        1.0 if conference else 0.0,
    ]


# ── ONNX export ────────────────────────────────────────────────────────────────

_TARGET_OPSET = {"": 17, "ai.onnx.ml": 3}


def export_regressor(model, output_path):
    initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
    onnx_model = convert_sklearn(model, initial_types=initial_type, target_opset=_TARGET_OPSET)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())


def export_classifier(model, output_path):
    initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
    # zipmap=False → output_probability is float tensor [n, 2], not a sequence of maps
    options = {id(model): {"zipmap": False}}
    onnx_model = convert_sklearn(model, initial_types=initial_type, options=options,
                                 target_opset=_TARGET_OPSET)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())


# ── Main ───────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="Train ML prediction models")
    p.add_argument("--train-seasons", required=True,
                   help="Comma-separated season years to train on, e.g. 2023,2024,2025")
    p.add_argument("--test-season", required=True, type=int,
                   help="Season year to use as test set")
    p.add_argument("--db-url", default=None,
                   help="PostgreSQL connection URL (default: built from env vars DB_HOST etc.)")
    p.add_argument("--output-dir", default="/models",
                   help="Directory to write ONNX files and features.json (default: /models)")
    return p.parse_args()


def _sep(title=""):
    """Print a section separator."""
    if title:
        pad = max(0, 60 - len(title) - 2)
        print(f"\n[train] {'─' * 3} {title} {'─' * pad}")
    else:
        print(f"[train] {'─' * 64}")


def _fmt_seconds(s):
    return f"{s:.1f}s" if s < 60 else f"{int(s)//60}m {int(s)%60}s"


def main():
    wall_start = time.time()
    args = parse_args()
    train_seasons = [int(y.strip()) for y in args.train_seasons.split(",")]
    test_season = args.test_season
    all_seasons = sorted(set(train_seasons + [test_season]))

    _sep("Configuration")
    print(f"[train]   train seasons : {train_seasons}")
    print(f"[train]   test season   : {test_season}")
    print(f"[train]   output dir    : {args.output_dir}")
    print(f"[train]   features      : {N_FEATURES}")

    # ── Load data ─────────────────────────────────────────────────────────────
    _sep("Loading data")
    db_url = build_db_url(args)
    print(f"[train] Connecting to database...")
    t0 = time.time()
    conn = psycopg2.connect(db_url)
    print(f"[train] Connected ({_fmt_seconds(time.time() - t0)})")

    print(f"[train] Querying games for seasons {all_seasons}...")
    t0 = time.time()
    games_df = load_games(conn, all_seasons)
    print(f"[train]   → {len(games_df):,} FINAL games ({_fmt_seconds(time.time() - t0)})")
    if not games_df.empty:
        for yr, grp in games_df.groupby("season_year"):
            label = f"(test)" if yr == test_season else "(train)"
            print(f"[train]       season {yr} {label}: {len(grp):,} games")

    print(f"[train] Querying Massey snapshots...")
    t0 = time.time()
    massey_df = load_massey_snapshots(conn, all_seasons)
    print(f"[train]   → {len(massey_df):,} Massey snapshots ({_fmt_seconds(time.time() - t0)})")

    print(f"[train] Querying Bradley-Terry snapshots...")
    t0 = time.time()
    bt_df = load_bt_snapshots(conn, all_seasons)
    print(f"[train]   → {len(bt_df):,} BT snapshots ({_fmt_seconds(time.time() - t0)})")

    print(f"[train] Querying HCA params...")
    t0 = time.time()
    hca_df = load_hca_params(conn, all_seasons)
    print(f"[train]   → {len(hca_df):,} HCA param rows ({_fmt_seconds(time.time() - t0)})")
    conn.close()

    # ── Validate ──────────────────────────────────────────────────────────────
    if games_df.empty:
        print("[train] ERROR: No FINAL games found. Run a full scrape first.")
        sys.exit(1)
    if massey_df.empty:
        print("[train] ERROR: No Massey snapshots. Run 'Power Ratings' from the admin dashboard.")
        sys.exit(1)
    if bt_df.empty:
        print("[train] ERROR: No Bradley-Terry snapshots. Run 'Power Ratings' from the admin dashboard.")
        sys.exit(1)

    # ── Date coercions ────────────────────────────────────────────────────────
    games_df["game_date"]        = pd.to_datetime(games_df["game_date"]).dt.date
    games_df["season_start_date"]= pd.to_datetime(games_df["season_start_date"]).dt.date
    massey_df["snapshot_date"]   = pd.to_datetime(massey_df["snapshot_date"]).dt.date
    bt_df["snapshot_date"]       = pd.to_datetime(bt_df["snapshot_date"]).dt.date
    if not hca_df.empty:
        hca_df["snapshot_date"]  = pd.to_datetime(hca_df["snapshot_date"]).dt.date

    # Report snapshot coverage
    massey_dates = massey_df["snapshot_date"]
    bt_dates     = bt_df["snapshot_date"]
    print(f"[train]   Massey date range : {massey_dates.min()} → {massey_dates.max()}")
    print(f"[train]   BT date range     : {bt_dates.min()} → {bt_dates.max()}")

    # ── Build lookup indexes ───────────────────────────────────────────────────
    _sep("Building lookup indexes")
    t0 = time.time()
    all_snapshots_df = pd.concat([massey_df, bt_df], ignore_index=True)
    snapshot_index = build_snapshot_index(all_snapshots_df)
    print(f"[train]   snapshot index : {len(snapshot_index):,} keys ({_fmt_seconds(time.time()-t0)})")
    t0 = time.time()
    param_index = build_param_index(hca_df)
    print(f"[train]   param index    : {len(param_index):,} keys ({_fmt_seconds(time.time()-t0)})")
    t0 = time.time()
    team_game_index = build_team_game_index(games_df)
    print(f"[train]   team game index: {len(team_game_index):,} teams ({_fmt_seconds(time.time()-t0)})")

    # ── Feature engineering ───────────────────────────────────────────────────
    _sep("Building features")
    print(f"[train] Processing {len(games_df):,} games...")
    t0 = time.time()
    rows_X, rows_y_spread, rows_y_total, rows_y_win, rows_season = [], [], [], [], []
    skipped = 0
    log_interval = max(500, len(games_df) // 10)

    for i, row in enumerate(games_df.itertuples(index=False), 1):
        feat = build_feature_row(row, team_game_index, snapshot_index, param_index)
        if feat is None:
            skipped += 1
            continue
        rows_X.append(feat)
        rows_y_spread.append(row.home_score - row.away_score)
        rows_y_total.append(row.home_score + row.away_score)
        rows_y_win.append(1 if row.home_score > row.away_score else 0)
        rows_season.append(row.season_year)
        if i % log_interval == 0:
            pct = 100 * i / len(games_df)
            kept = i - skipped
            print(f"[train]   {i:,}/{len(games_df):,} ({pct:.0f}%) — kept {kept:,}, skipped {skipped:,} so far "
                  f"({_fmt_seconds(time.time() - t0)})")

    feat_elapsed = time.time() - t0
    kept_total = len(rows_X)
    print(f"[train] Feature build complete: {kept_total:,} rows kept, "
          f"{skipped:,} skipped ({100*skipped/len(games_df):.1f}%) "
          f"in {_fmt_seconds(feat_elapsed)}")

    X        = np.array(rows_X, dtype=np.float32)
    y_spread = np.array(rows_y_spread, dtype=np.float32)
    y_total  = np.array(rows_y_total,  dtype=np.float32)
    y_win    = np.array(rows_y_win,    dtype=np.int32)
    seasons  = np.array(rows_season)

    if X.shape[0] == 0:
        print("[train] ERROR: No feature rows could be built. "
              "Check that power ratings exist for the requested seasons.")
        sys.exit(1)

    # Per-season breakdown
    print(f"[train] Feature matrix: {X.shape[0]:,} rows × {X.shape[1]} features")
    for yr in sorted(set(rows_season)):
        mask = seasons == yr
        n = mask.sum()
        hw = y_win[mask].mean() * 100
        label = "(test)" if yr == test_season else "(train)"
        print(f"[train]   season {yr} {label}: {n:,} rows, home win rate {hw:.1f}%")

    # Label statistics
    print(f"[train] Spread  — mean {y_spread.mean():+.1f} pts, "
          f"std {y_spread.std():.1f} pts, "
          f"range [{y_spread.min():.0f}, {y_spread.max():.0f}]")
    print(f"[train] Total   — mean {y_total.mean():.1f} pts, "
          f"std {y_total.std():.1f} pts, "
          f"range [{y_total.min():.0f}, {y_total.max():.0f}]")
    print(f"[train] Win     — home wins {y_win.mean()*100:.1f}% "
          f"({y_win.sum():,} / {len(y_win):,})")

    # ── Train/test split ──────────────────────────────────────────────────────
    train_mask = seasons != test_season
    test_mask  = seasons == test_season

    X_train,    X_test    = X[train_mask],        X[test_mask]
    ys_train,   ys_test   = y_spread[train_mask], y_spread[test_mask]
    yt_train,   yt_test   = y_total[train_mask],  y_total[test_mask]
    yw_train,   yw_test   = y_win[train_mask],    y_win[test_mask]

    in_sample_metrics = False
    if X_train.shape[0] == 0:
        print(f"\n[train] WARNING: No out-of-season training rows for season {test_season}. "
              f"Falling back to in-sample training — metrics will be optimistic.")
        X_train,  X_test  = X,        X
        ys_train, ys_test = y_spread, y_spread
        yt_train, yt_test = y_total,  y_total
        yw_train, yw_test = y_win,    y_win
        in_sample_metrics = True

    sample_tag = " [IN-SAMPLE]" if in_sample_metrics else ""
    print(f"\n[train] Train: {X_train.shape[0]:,} rows  |  "
          f"Test (season {test_season}): {X_test.shape[0]:,} rows{sample_tag}")

    # ── Spread model ───────────────────────────────────────────────────────────
    _sep("Spread model")
    print(f"[train] XGBRegressor(n_estimators=300, max_depth=4, lr=0.05) ...")
    t0 = time.time()
    spread_model = Pipeline([
        ("model", XGBRegressor(n_estimators=300, max_depth=4, learning_rate=0.05,
                               subsample=0.8, colsample_bytree=0.8,
                               objective="reg:squarederror", random_state=42))
    ])
    spread_model.fit(X_train, ys_train)
    elapsed = time.time() - t0
    spread_preds = spread_model.predict(X_test)
    spread_rmse = root_mean_squared_error(ys_test, spread_preds)
    spread_mae  = float(np.abs(ys_test - spread_preds).mean())
    baseline_rmse = float(np.sqrt(((ys_test - ys_train.mean()) ** 2).mean()))
    print(f"[train]   fit time  : {_fmt_seconds(elapsed)}")
    print(f"[train]   RMSE      : {spread_rmse:.3f} pts  (baseline naive: {baseline_rmse:.3f} pts)")
    print(f"[train]   MAE       : {spread_mae:.3f} pts")
    correct_side = float(((spread_preds > 0) == (ys_test > 0)).mean() * 100)
    print(f"[train]   side acc  : {correct_side:.1f}%  (predicted correct winner)")

    # ── Total model ────────────────────────────────────────────────────────────
    _sep("Total model")
    print(f"[train] XGBRegressor(n_estimators=300, max_depth=4, lr=0.05) ...")
    t0 = time.time()
    total_model = Pipeline([
        ("model", XGBRegressor(n_estimators=300, max_depth=4, learning_rate=0.05,
                               subsample=0.8, colsample_bytree=0.8,
                               objective="reg:squarederror", random_state=42))
    ])
    total_model.fit(X_train, yt_train)
    elapsed = time.time() - t0
    total_preds = total_model.predict(X_test)
    total_rmse = root_mean_squared_error(yt_test, total_preds)
    total_mae  = float(np.abs(yt_test - total_preds).mean())
    baseline_total_rmse = float(np.sqrt(((yt_test - yt_train.mean()) ** 2).mean()))
    print(f"[train]   fit time  : {_fmt_seconds(elapsed)}")
    print(f"[train]   RMSE      : {total_rmse:.3f} pts  (baseline naive: {baseline_total_rmse:.3f} pts)")
    print(f"[train]   MAE       : {total_mae:.3f} pts")

    # ── Win probability model ─────────────────────────────────────────────────
    _sep("Win probability model")
    cv_folds = min(5, X_train.shape[0] // 2)
    print(f"[train] CalibratedClassifierCV(XGBClassifier, method=sigmoid, cv={cv_folds}) ...")
    t0 = time.time()
    base_clf = XGBClassifier(n_estimators=300, max_depth=4, learning_rate=0.05,
                             subsample=0.8, colsample_bytree=0.8,
                             objective="binary:logistic", random_state=42,
                             eval_metric="logloss")
    winprob_model = CalibratedClassifierCV(base_clf, method="sigmoid", cv=cv_folds)
    winprob_model.fit(X_train, yw_train)
    elapsed = time.time() - t0
    probs_test = winprob_model.predict_proba(X_test)[:, 1]
    brier = brier_score_loss(yw_test, probs_test)
    baseline_brier = float(yw_train.mean() * (1 - yw_train.mean()))
    acc = float(((probs_test > 0.5) == yw_test).mean() * 100)
    print(f"[train]   fit time     : {_fmt_seconds(elapsed)}")
    print(f"[train]   Brier score  : {brier:.4f}  (baseline naive: {baseline_brier:.4f})")
    print(f"[train]   Accuracy     : {acc:.1f}%")
    # Calibration: show mean predicted prob vs actual win rate in deciles
    decile_edges = np.percentile(probs_test, np.linspace(0, 100, 11))
    print(f"[train]   Calibration (predicted → actual, by decile):")
    for lo, hi in zip(decile_edges[:-1], decile_edges[1:]):
        mask = (probs_test >= lo) & (probs_test <= hi)
        if mask.sum() == 0:
            continue
        pred_mean = probs_test[mask].mean()
        act_mean  = yw_test[mask].mean()
        n = mask.sum()
        print(f"[train]     {pred_mean*100:4.0f}% predicted → {act_mean*100:4.0f}% actual  (n={n:,})")

    # ── Export to ONNX ────────────────────────────────────────────────────────
    _sep("Exporting ONNX models")
    output_dir = args.output_dir
    pending_dir = os.path.join(output_dir, ".pending")
    os.makedirs(pending_dir, exist_ok=True)

    for label, fn, path in [
        ("spread",   "spread_model.onnx",  os.path.join(pending_dir, "spread_model.onnx")),
        ("total",    "total_model.onnx",   os.path.join(pending_dir, "total_model.onnx")),
        ("winprob",  "winprob_model.onnx", os.path.join(pending_dir, "winprob_model.onnx")),
    ]:
        t0 = time.time()
        if label == "winprob":
            export_classifier(winprob_model, path)
        else:
            export_regressor(spread_model if label == "spread" else total_model, path)
        size_kb = os.path.getsize(path) / 1024
        print(f"[train]   {label:<8} → {fn}  ({size_kb:.0f} KB, {_fmt_seconds(time.time()-t0)})")

    features_meta = {
        "version": datetime.utcnow().strftime("%Y-%m-%d"),
        "features": FEATURE_NAMES,
        "spread_model":  "spread_model.onnx",
        "total_model":   "total_model.onnx",
        "winprob_model": "winprob_model.onnx",
        "metrics": {
            "spread_rmse": round(float(spread_rmse), 3),
            "spread_mae":  round(float(spread_mae),  3),
            "total_rmse":  round(float(total_rmse),  3),
            "total_mae":   round(float(total_mae),   3),
            "brier_score": round(float(brier),       4),
            "win_accuracy":round(float(acc),         1),
            "in_sample":   in_sample_metrics,
        },
    }
    with open(os.path.join(pending_dir, "features.json"), "w") as f:
        json.dump(features_meta, f, indent=2)
    print(f"[train]   features.json written")

    # Atomic rename
    for fname in ["spread_model.onnx", "total_model.onnx", "winprob_model.onnx", "features.json"]:
        shutil.move(os.path.join(pending_dir, fname), os.path.join(output_dir, fname))

    # ── Summary ───────────────────────────────────────────────────────────────
    _sep("Summary")
    total_elapsed = time.time() - wall_start
    print(f"[train]   Total wall time : {_fmt_seconds(total_elapsed)}")
    print(f"[train]   Training rows   : {X_train.shape[0]:,}  |  Test rows: {X_test.shape[0]:,}{sample_tag}")
    print(f"[train]   Spread  RMSE={spread_rmse:.3f} pts  MAE={spread_mae:.3f} pts  "
          f"side-acc={correct_side:.1f}%")
    print(f"[train]   Total   RMSE={total_rmse:.3f} pts  MAE={total_mae:.3f} pts")
    print(f"[train]   WinProb Brier={brier:.4f}  acc={acc:.1f}%")
    print(f"[train]   Models written to {output_dir}")
    _sep()


if __name__ == "__main__":
    main()
