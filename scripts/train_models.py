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
import json
import os
import shutil
import sys
import tempfile
from datetime import datetime, date, timedelta

import numpy as np
import pandas as pd
import psycopg2
from psycopg2.extras import RealDictCursor
from sklearn.calibration import CalibratedClassifierCV
from sklearn.pipeline import Pipeline
from sklearn.metrics import brier_score_loss, mean_squared_error
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
from xgboost import XGBClassifier, XGBRegressor

# ── Constants ──────────────────────────────────────────────────────────────────

FEATURE_NAMES = [
    "massey_beta_home", "massey_beta_away", "massey_beta_diff",
    "massey_gamma_home", "massey_gamma_away", "massey_gamma_sum",
    "bt_theta_home", "bt_theta_away", "bt_logodds",
    "home_win_pct_l5", "home_avg_margin_l5", "home_avg_total_l5", "home_margin_stddev_l5",
    "away_win_pct_l5", "away_avg_margin_l5", "away_avg_total_l5", "away_margin_stddev_l5",
    "home_games_played", "away_games_played",
    "home_days_rest", "away_days_rest", "season_week",
    "is_neutral_site", "is_conference_game",
]
N_FEATURES = len(FEATURE_NAMES)  # 24


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
        WHERE r.model_type = 'BRADLEY_TERRY'
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

def latest_before(df, team_id, season_id, model_type, cutoff_date):
    """Return the most recent snapshot row strictly before cutoff_date, or None."""
    mask = (
        (df["team_id"] == team_id) &
        (df["season_id"] == season_id) &
        (df["model_type"] == model_type) &
        (df["snapshot_date"] < cutoff_date)
    )
    subset = df[mask]
    if subset.empty:
        return None
    return subset.loc[subset["snapshot_date"].idxmax()]


def latest_param_before(df, season_id, model_type, param_name, cutoff_date):
    """Return the most recent param value strictly before cutoff_date, or 0.0."""
    mask = (
        (df["season_id"] == season_id) &
        (df["model_type"] == model_type) &
        (df["param_name"] == param_name) &
        (df["snapshot_date"] < cutoff_date)
    )
    subset = df[mask]
    if subset.empty:
        return 0.0
    return subset.loc[subset["snapshot_date"].idxmax(), "param_value"]


def rolling_stats(games_df, team_id, cutoff_date, n=5):
    """Compute rolling stats for a team's last n games before cutoff_date."""
    mask = (
        ((games_df["home_team_id"] == team_id) | (games_df["away_team_id"] == team_id)) &
        (games_df["game_date"] < cutoff_date)
    )
    recent = games_df[mask].sort_values("game_date", ascending=False).head(n)
    if recent.empty:
        return None, None, None, None, None  # win_pct, avg_margin, avg_total, stddev, days_rest

    margins = []
    totals = []
    wins = 0
    for _, row in recent.iterrows():
        h, a = row["home_score"], row["away_score"]
        total = h + a
        if row["home_team_id"] == team_id:
            margin = h - a
        else:
            margin = a - h
        if margin > 0:
            wins += 1
        margins.append(margin)
        totals.append(total)

    win_pct = wins / len(margins)
    avg_margin = np.mean(margins)
    avg_total = np.mean(totals)
    stddev = np.std(margins, ddof=1) if len(margins) > 1 else 0.0

    # Days rest
    latest_game_date = recent["game_date"].max()
    days_rest = (cutoff_date - latest_game_date).days

    return win_pct, avg_margin, avg_total, stddev, days_rest


def build_feature_row(row, games_df, massey_df, bt_df, hca_df):
    """Build a feature vector for one game row. Returns None if ratings unavailable."""
    game_date = row["game_date"]
    season_id = row["season_id"]
    home_id = row["home_team_id"]
    away_id = row["away_team_id"]
    neutral = bool(row["neutral_site"])
    conference = bool(row["conference_game"]) if row["conference_game"] is not None else False

    # ── Massey spread ratings ──────────────────────────────────────────────────
    massey_home = latest_before(massey_df[massey_df["model_type"] == "MASSEY"], home_id, season_id, "MASSEY", game_date)
    massey_away = latest_before(massey_df[massey_df["model_type"] == "MASSEY"], away_id, season_id, "MASSEY", game_date)
    if massey_home is None or massey_away is None:
        return None

    beta_home = massey_home["rating"]
    beta_away = massey_away["rating"]
    massey_hca = 0.0 if neutral else latest_param_before(hca_df, season_id, "MASSEY", "hca", game_date)

    # ── Massey total ratings ───────────────────────────────────────────────────
    mt_home = latest_before(massey_df[massey_df["model_type"] == "MASSEY_TOTAL"], home_id, season_id, "MASSEY_TOTAL", game_date)
    mt_away = latest_before(massey_df[massey_df["model_type"] == "MASSEY_TOTAL"], away_id, season_id, "MASSEY_TOTAL", game_date)
    if mt_home is None or mt_away is None:
        return None

    gamma_home = mt_home["rating"]
    gamma_away = mt_away["rating"]

    # ── Bradley-Terry ratings ──────────────────────────────────────────────────
    bt_home_row = latest_before(bt_df, home_id, season_id, "BRADLEY_TERRY", game_date)
    bt_away_row = latest_before(bt_df, away_id, season_id, "BRADLEY_TERRY", game_date)
    if bt_home_row is None or bt_away_row is None:
        return None

    theta_home = bt_home_row["rating"]
    theta_away = bt_away_row["rating"]
    bt_alpha = 0.0 if neutral else latest_param_before(hca_df, season_id, "BRADLEY_TERRY", "hca", game_date)
    bt_logodds = theta_home - theta_away + bt_alpha

    home_gp = massey_home["games_played"]
    away_gp = massey_away["games_played"]

    # ── Rolling features ───────────────────────────────────────────────────────
    h_win_pct, h_avg_margin, h_avg_total, h_stddev, h_rest = rolling_stats(games_df, home_id, game_date)
    a_win_pct, a_avg_margin, a_avg_total, a_stddev, a_rest = rolling_stats(games_df, away_id, game_date)

    if any(v is None for v in [h_win_pct, a_win_pct]):
        return None  # skip games where rolling window is empty (cold start)

    # ── Season week ────────────────────────────────────────────────────────────
    season_week = int((game_date - row["season_start_date"]).days / 7) + 1

    return [
        beta_home, beta_away, beta_home - beta_away,
        gamma_home, gamma_away, gamma_home + gamma_away,
        theta_home, theta_away, bt_logodds,
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

def export_regressor(model, output_path):
    initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
    onnx_model = convert_sklearn(model, initial_types=initial_type)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())


def export_classifier(model, output_path):
    initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
    # zipmap=False → output_probability is float tensor [n, 2], not a sequence of maps
    options = {id(model): {"zipmap": False}}
    onnx_model = convert_sklearn(model, initial_types=initial_type, options=options)
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


def main():
    args = parse_args()
    train_seasons = [int(y.strip()) for y in args.train_seasons.split(",")]
    test_season = args.test_season
    all_seasons = sorted(set(train_seasons + [test_season]))

    db_url = build_db_url(args)
    print(f"[train] Connecting to database...")
    conn = psycopg2.connect(db_url)

    print(f"[train] Loading data for seasons {all_seasons}...")
    games_df    = load_games(conn, all_seasons)
    massey_df   = load_massey_snapshots(conn, all_seasons)
    bt_df       = load_bt_snapshots(conn, all_seasons)
    hca_df      = load_hca_params(conn, all_seasons)
    conn.close()

    # Ensure date columns are Python date objects
    games_df["game_date"] = pd.to_datetime(games_df["game_date"]).dt.date
    games_df["season_start_date"] = pd.to_datetime(games_df["season_start_date"]).dt.date
    massey_df["snapshot_date"] = pd.to_datetime(massey_df["snapshot_date"]).dt.date
    bt_df["snapshot_date"] = pd.to_datetime(bt_df["snapshot_date"]).dt.date
    hca_df["snapshot_date"] = pd.to_datetime(hca_df["snapshot_date"]).dt.date

    print(f"[train] Building feature matrix from {len(games_df)} games...")
    rows_X, rows_y_spread, rows_y_total, rows_y_win, rows_season = [], [], [], [], []

    for _, row in games_df.iterrows():
        feat = build_feature_row(row, games_df, massey_df, bt_df, hca_df)
        if feat is None:
            continue
        rows_X.append(feat)
        rows_y_spread.append(row["home_score"] - row["away_score"])
        rows_y_total.append(row["home_score"] + row["away_score"])
        rows_y_win.append(1 if row["home_score"] > row["away_score"] else 0)
        rows_season.append(row["season_year"])

    X        = np.array(rows_X, dtype=np.float32)
    y_spread = np.array(rows_y_spread, dtype=np.float32)
    y_total  = np.array(rows_y_total,  dtype=np.float32)
    y_win    = np.array(rows_y_win,    dtype=np.int32)
    seasons  = np.array(rows_season)

    train_mask = seasons != test_season
    test_mask  = seasons == test_season

    X_train,    X_test    = X[train_mask],     X[test_mask]
    ys_train,   ys_test   = y_spread[train_mask], y_spread[test_mask]
    yt_train,   yt_test   = y_total[train_mask],  y_total[test_mask]
    yw_train,   yw_test   = y_win[train_mask],    y_win[test_mask]

    print(f"[train] Train rows: {X_train.shape[0]}, Test rows (season {test_season}): {X_test.shape[0]}")

    # ── Spread model ───────────────────────────────────────────────────────────
    print("[train] Fitting spread model...")
    spread_model = Pipeline([
        ("model", XGBRegressor(n_estimators=300, max_depth=4, learning_rate=0.05,
                               subsample=0.8, colsample_bytree=0.8,
                               objective="reg:squarederror", random_state=42))
    ])
    spread_model.fit(X_train, ys_train)
    spread_rmse = mean_squared_error(ys_test, spread_model.predict(X_test), squared=False)
    print(f"[train] Spread RMSE (test): {spread_rmse:.3f} pts")

    # ── Total model ────────────────────────────────────────────────────────────
    print("[train] Fitting total model...")
    total_model = Pipeline([
        ("model", XGBRegressor(n_estimators=300, max_depth=4, learning_rate=0.05,
                               subsample=0.8, colsample_bytree=0.8,
                               objective="reg:squarederror", random_state=42))
    ])
    total_model.fit(X_train, yt_train)
    total_rmse = mean_squared_error(yt_test, total_model.predict(X_test), squared=False)
    print(f"[train] Total RMSE (test): {total_rmse:.3f} pts")

    # ── Win probability model ─────────────────────────────────────────────────
    print("[train] Fitting win probability model...")
    base_clf = XGBClassifier(n_estimators=300, max_depth=4, learning_rate=0.05,
                             subsample=0.8, colsample_bytree=0.8,
                             objective="binary:logistic", random_state=42,
                             eval_metric="logloss")
    # method="sigmoid" (Platt scaling) exports to ONNX reliably
    winprob_model = CalibratedClassifierCV(base_clf, method="sigmoid", cv=5)
    winprob_model.fit(X_train, yw_train)
    probs_test = winprob_model.predict_proba(X_test)[:, 1]
    brier = brier_score_loss(yw_test, probs_test)
    print(f"[train] Win prob Brier score (test): {brier:.4f}")

    # ── Export to ONNX (atomic: write to .pending/, then rename) ──────────────
    output_dir = args.output_dir
    pending_dir = os.path.join(output_dir, ".pending")
    os.makedirs(pending_dir, exist_ok=True)

    print("[train] Exporting ONNX models...")
    export_regressor(spread_model,  os.path.join(pending_dir, "spread_model.onnx"))
    export_regressor(total_model,   os.path.join(pending_dir, "total_model.onnx"))
    export_classifier(winprob_model, os.path.join(pending_dir, "winprob_model.onnx"))

    features_meta = {
        "version": datetime.utcnow().strftime("%Y-%m-%d"),
        "features": FEATURE_NAMES,
        "spread_model":  "spread_model.onnx",
        "total_model":   "total_model.onnx",
        "winprob_model": "winprob_model.onnx",
        "metrics": {
            "spread_rmse": round(float(spread_rmse), 3),
            "total_rmse":  round(float(total_rmse),  3),
            "brier_score": round(float(brier),       4),
        },
    }
    with open(os.path.join(pending_dir, "features.json"), "w") as f:
        json.dump(features_meta, f, indent=2)

    # Atomic rename: move each file from .pending/ to output_dir/
    for fname in ["spread_model.onnx", "total_model.onnx", "winprob_model.onnx", "features.json"]:
        src = os.path.join(pending_dir, fname)
        dst = os.path.join(output_dir, fname)
        shutil.move(src, dst)

    # features.json written last — its presence signals a complete, consistent set
    print(f"[train] Models written to {output_dir}")
    print(f"[train] Done. spread_rmse={spread_rmse:.3f}, total_rmse={total_rmse:.3f}, brier={brier:.4f}")


if __name__ == "__main__":
    main()
