#!/usr/bin/env python3
"""
Train Massey + Bradley-Terry feature-augmented ML models and export to ONNX.

Reads game data directly from PostgreSQL (same DB as the Spring Boot app).
Writes spread_model.onnx, total_model.onnx, winprob_model.onnx, and features.json
to <output-dir>/<model-name>/ (default /models/baseline — the Docker volume mount).

Models are named (--model-name) and select a feature set (--feature-set):
  baseline : the original 27 rating/rolling/context features
  pace-v2  : baseline + 14 box-score-derived features (pace, efficiencies,
             four-factors, RPI) from team_stat_snapshots / team_season_stat_snapshots

Usage (inside Docker Compose):
    docker compose --profile training run --rm trainer \\
        --train-seasons 2023,2024,2025 \\
        --test-season 2025 \\
        --model-name pace-v2

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
import re
import shutil
import sys
import tempfile
import time
from collections import OrderedDict
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

# ── Feature registry ───────────────────────────────────────────────────────────
#
# Each feature is a named function over the per-game context dict built by
# build_game_context(). A feature set is an ordered list of registry names;
# the list drives row assembly, ONNX input width, and features.json.
#
# The Java serving side mirrors these names and this order exactly — do not
# reorder or rename existing entries; add new feature sets instead.

MODEL_NAME_RE = re.compile(r"^[a-z0-9-]{1,40}$")


def _ctx(key):
    """Feature that reads a context key verbatim (None if unavailable)."""
    return lambda c: c.get(key)


FEATURE_REGISTRY = OrderedDict([
    # ── baseline: ratings ──
    ("massey_beta_home",       _ctx("beta_home")),
    ("massey_beta_away",       _ctx("beta_away")),
    ("massey_beta_diff",       lambda c: c["beta_home"] - c["beta_away"]),
    ("massey_gamma_home",      _ctx("gamma_home")),
    ("massey_gamma_away",      _ctx("gamma_away")),
    ("massey_gamma_sum",       lambda c: c["gamma_home"] + c["gamma_away"]),
    ("bt_theta_home",          _ctx("theta_home")),
    ("bt_theta_away",          _ctx("theta_away")),
    ("bt_logodds",             _ctx("bt_logodds")),
    ("bt_theta_weighted_home", _ctx("theta_w_home")),
    ("bt_theta_weighted_away", _ctx("theta_w_away")),
    ("bt_logodds_weighted",    _ctx("bt_logodds_w")),
    # ── baseline: rolling last-5 ──
    ("home_win_pct_l5",        _ctx("h_win_pct")),
    ("home_avg_margin_l5",     _ctx("h_avg_margin")),
    ("home_avg_total_l5",      _ctx("h_avg_total")),
    ("home_margin_stddev_l5",  _ctx("h_stddev")),
    ("away_win_pct_l5",        _ctx("a_win_pct")),
    ("away_avg_margin_l5",     _ctx("a_avg_margin")),
    ("away_avg_total_l5",      _ctx("a_avg_total")),
    ("away_margin_stddev_l5",  _ctx("a_stddev")),
    # ── baseline: context ──
    ("home_games_played",      _ctx("home_gp")),
    ("away_games_played",      _ctx("away_gp")),
    ("home_days_rest",         lambda c: c["h_rest"] if c["h_rest"] is not None else -1),
    ("away_days_rest",         lambda c: c["a_rest"] if c["a_rest"] is not None else -1),
    ("season_week",            _ctx("season_week")),
    ("is_neutral_site",        lambda c: 1.0 if c["neutral"] else 0.0),
    ("is_conference_game",     lambda c: 1.0 if c["conference"] else 0.0),
    # ── pace-v2 extras: box-score-derived snapshots + RPI ──
    ("home_pace",              _ctx("home_pace")),
    ("away_pace",              _ctx("away_pace")),
    ("home_off_eff",           _ctx("home_off_eff")),
    ("away_off_eff",           _ctx("away_off_eff")),
    ("home_def_eff",           _ctx("home_def_eff")),
    ("away_def_eff",           _ctx("away_def_eff")),
    ("home_efg_pct",           _ctx("home_efg_pct")),
    ("away_efg_pct",           _ctx("away_efg_pct")),
    ("home_opp_efg_pct",       _ctx("home_opp_efg_pct")),
    ("away_opp_efg_pct",       _ctx("away_opp_efg_pct")),
    ("home_tov_rate",          _ctx("home_tov_rate")),
    ("away_tov_rate",          _ctx("away_tov_rate")),
    ("home_rpi",               _ctx("home_rpi")),
    ("away_rpi",               _ctx("away_rpi")),
])

BASELINE_FEATURES = [
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

PACE_V2_EXTRAS = [
    "home_pace", "away_pace",
    "home_off_eff", "away_off_eff",
    "home_def_eff", "away_def_eff",
    "home_efg_pct", "away_efg_pct",
    "home_opp_efg_pct", "away_opp_efg_pct",
    "home_tov_rate", "away_tov_rate",
    "home_rpi", "away_rpi",
]

FEATURE_SETS = {
    "baseline": BASELINE_FEATURES,
    "pace-v2":  BASELINE_FEATURES + PACE_V2_EXTRAS,
}

# Features whose absence is a "box" skip (warn only), not a "ratings" skip
# (which counts toward the MAX_SKIP_PCT hard guard).
BOX_FEATURES = set(PACE_V2_EXTRAS)

# team_stat_snapshots stat_name → context-key suffix
BOX_STAT_KEYS = [
    ("pace",           "pace"),
    ("off_efficiency", "off_eff"),
    ("def_efficiency", "def_eff"),
    ("efg_pct",        "efg_pct"),
    ("opp_efg_pct",    "opp_efg_pct"),
    ("tov_rate",       "tov_rate"),
]

# Monotonic constraints (+1 = prediction non-decreasing in the feature)
SPREAD_MONO_POS  = {"massey_beta_diff", "bt_logodds", "bt_logodds_weighted"}
TOTAL_MONO_POS   = {"massey_gamma_sum"}
WINPROB_MONO_POS = SPREAD_MONO_POS

# Early stopping (regressors)
N_ESTIMATORS_MAX = 2000
EARLY_STOPPING_ROUNDS = 30
VALIDATION_FRACTION = 0.15
MIN_ROWS_FOR_EARLY_STOP = 50
DEFAULT_N_ESTIMATORS = 300  # fallback + walk-forward fits


def monotone_constraints_str(feature_list, positive_features):
    """XGBoost monotone_constraints string, e.g. '(0,0,1,0,...)'."""
    return "(" + ",".join("1" if f in positive_features else "0" for f in feature_list) + ")"


def display_name_for(slug):
    """'pace-v2' → 'Pace V2'."""
    return " ".join(part.capitalize() for part in slug.split("-") if part)


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
    """Load MASSEY and MASSEY_TOTALS snapshots for the given seasons."""
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
        WHERE r.model_type IN ('MASSEY', 'MASSEY_TOTALS')
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
    """Load HCA params for MASSEY, MASSEY_TOTALS, BRADLEY_TERRY."""
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


def load_box_stat_snapshots(conn, season_years):
    """Load long-format derived box-score stats (pace-v2 feature set)."""
    stat_names = [s for s, _ in BOX_STAT_KEYS]
    season_ph = ",".join(["%s"] * len(season_years))
    stat_ph = ",".join(["%s"] * len(stat_names))
    sql = f"""
        SELECT
            t.team_id,
            t.season_id,
            t.snapshot_date,
            t.stat_name,
            t.value
        FROM team_stat_snapshots t
        JOIN seasons s ON t.season_id = s.id
        WHERE t.stat_name IN ({stat_ph})
          AND s.year IN ({season_ph})
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, stat_names + list(season_years))
        return pd.DataFrame(cur.fetchall())


def load_rpi_snapshots(conn, season_years):
    """Load RPI from the wide per-team daily snapshot table (pace-v2 feature set)."""
    placeholders = ",".join(["%s"] * len(season_years))
    sql = f"""
        SELECT
            t.team_id,
            t.season_id,
            t.snapshot_date,
            t.rpi
        FROM team_season_stat_snapshots t
        JOIN seasons s ON t.season_id = s.id
        WHERE t.rpi IS NOT NULL
          AND s.year IN ({placeholders})
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


def build_value_index(df, key_fn):
    """
    Generic point-in-time index: key -> (sorted dates, parallel values).

    df must have snapshot_date and value columns exposed by key_fn/row access;
    key_fn(row) returns the grouping key, row.value the float value.
    """
    if df is None or df.empty:
        return {}
    groups = {}
    for row in df.itertuples(index=False):
        key = key_fn(row)
        if key not in groups:
            groups[key] = []
        groups[key].append((row.snapshot_date, float(row.value)))
    index = {}
    for key, entries in groups.items():
        entries.sort()
        index[key] = ([e[0] for e in entries], [e[1] for e in entries])
    return index


def build_box_stat_index(df):
    """(team_id, season_id, stat_name) -> (dates, values), sorted by snapshot_date."""
    return build_value_index(df, lambda r: (int(r.team_id), int(r.season_id), r.stat_name))


def build_rpi_index(df):
    """(team_id, season_id) -> (dates, rpi values), sorted by snapshot_date."""
    if df is None or df.empty:
        return {}
    df = df.rename(columns={"rpi": "value"})
    return build_value_index(df, lambda r: (int(r.team_id), int(r.season_id)))


def lookup_value(index, key, cutoff_date):
    """Return the latest value strictly before cutoff_date, or None."""
    result = index.get(key)
    if result is None:
        return None
    dates, values = result
    pos = bisect.bisect_left(dates, cutoff_date)
    if pos == 0:
        return None
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


def build_game_context(row, team_game_index, snapshot_index, param_index,
                       box_stat_index, rpi_index, needs_box):
    """
    Build the per-game context dict feature functions read from.
    Returns (ctx, None) on success or (None, 'ratings') when a required rating
    snapshot or rolling stat is unavailable. Box-stat values are stored as-is
    (possibly None); their absence is detected during row assembly.
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
        return None, "ratings"
    beta_home, home_gp = snap_mh
    beta_away, away_gp = snap_ma

    # ── Massey total ratings ───────────────────────────────────────────────────
    snap_th = lookup_snapshot(snapshot_index, home_id, season_id, "MASSEY_TOTALS", game_date)
    snap_ta = lookup_snapshot(snapshot_index, away_id, season_id, "MASSEY_TOTALS", game_date)
    if snap_th is None or snap_ta is None:
        return None, "ratings"
    gamma_home = snap_th[0]
    gamma_away = snap_ta[0]

    # ── Bradley-Terry ratings ──────────────────────────────────────────────────
    snap_bh = lookup_snapshot(snapshot_index, home_id, season_id, "BRADLEY_TERRY", game_date)
    snap_ba = lookup_snapshot(snapshot_index, away_id, season_id, "BRADLEY_TERRY", game_date)
    if snap_bh is None or snap_ba is None:
        return None, "ratings"
    theta_home = snap_bh[0]
    theta_away = snap_ba[0]
    bt_alpha   = 0.0 if neutral else lookup_param(param_index, season_id, "BRADLEY_TERRY", "hca", game_date)
    bt_logodds = theta_home - theta_away + bt_alpha

    # ── Weighted Bradley-Terry ratings ────────────────────────────────────────
    snap_bwh = lookup_snapshot(snapshot_index, home_id, season_id, "BRADLEY_TERRY_W", game_date)
    snap_bwa = lookup_snapshot(snapshot_index, away_id, season_id, "BRADLEY_TERRY_W", game_date)
    if snap_bwh is None or snap_bwa is None:
        return None, "ratings"
    theta_w_home = snap_bwh[0]
    theta_w_away = snap_bwa[0]
    bt_w_alpha   = 0.0 if neutral else lookup_param(param_index, season_id, "BRADLEY_TERRY_W", "hca", game_date)
    bt_logodds_w = theta_w_home - theta_w_away + bt_w_alpha

    # ── Rolling features ───────────────────────────────────────────────────────
    h_win_pct, h_avg_margin, h_avg_total, h_stddev, h_rest = rolling_stats_fast(team_game_index, home_id, game_date)
    a_win_pct, a_avg_margin, a_avg_total, a_stddev, a_rest = rolling_stats_fast(team_game_index, away_id, game_date)
    if h_win_pct is None or a_win_pct is None:
        return None, "ratings"

    # ── Season week ────────────────────────────────────────────────────────────
    season_week = int((game_date - row.season_start_date).days / 7) + 1

    ctx = {
        "beta_home": beta_home, "beta_away": beta_away,
        "gamma_home": gamma_home, "gamma_away": gamma_away,
        "theta_home": theta_home, "theta_away": theta_away, "bt_logodds": bt_logodds,
        "theta_w_home": theta_w_home, "theta_w_away": theta_w_away, "bt_logodds_w": bt_logodds_w,
        "h_win_pct": h_win_pct, "h_avg_margin": h_avg_margin,
        "h_avg_total": h_avg_total, "h_stddev": h_stddev,
        "a_win_pct": a_win_pct, "a_avg_margin": a_avg_margin,
        "a_avg_total": a_avg_total, "a_stddev": a_stddev,
        "home_gp": home_gp, "away_gp": away_gp,
        "h_rest": h_rest, "a_rest": a_rest,
        "season_week": season_week,
        "neutral": neutral, "conference": conference,
    }

    # ── Box-score-derived snapshots (pace-v2) — no imputation, absence = skip ──
    if needs_box:
        for side, tid in (("home", home_id), ("away", away_id)):
            for stat_name, key_suffix in BOX_STAT_KEYS:
                ctx[f"{side}_{key_suffix}"] = lookup_value(
                    box_stat_index, (tid, season_id, stat_name), game_date)
            ctx[f"{side}_rpi"] = lookup_value(rpi_index, (tid, season_id), game_date)

    return ctx, None


def assemble_feature_row(feature_list, ctx):
    """
    Assemble the feature vector for one game from the registry.
    Returns (row, None) or (None, missing_feature_name) when a feature is
    unavailable (only box features can be missing at this point — baseline
    availability is enforced in build_game_context).
    """
    values = []
    for name in feature_list:
        v = FEATURE_REGISTRY[name](ctx)
        if v is None:
            return None, name
        values.append(float(v))
    return values, None


# ── ONNX export ────────────────────────────────────────────────────────────────

_TARGET_OPSET = {"": 17, "ai.onnx.ml": 3}


def export_regressor(model, output_path, n_features):
    initial_type = [("float_input", FloatTensorType([None, n_features]))]
    onnx_model = convert_sklearn(model, initial_types=initial_type, target_opset=_TARGET_OPSET)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())


def export_classifier(model, output_path, n_features):
    initial_type = [("float_input", FloatTensorType([None, n_features]))]
    # zipmap=False → output_probability is float tensor [n, 2], not a sequence of maps
    options = {id(model): {"zipmap": False}}
    onnx_model = convert_sklearn(model, initial_types=initial_type, options=options,
                                 target_opset=_TARGET_OPSET)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())


# ── Model training ─────────────────────────────────────────────────────────────

def _regressor_kwargs(n_estimators, monotone):
    return dict(n_estimators=n_estimators, max_depth=4, learning_rate=0.05,
                subsample=0.8, colsample_bytree=0.8,
                objective="reg:squarederror", random_state=42,
                monotone_constraints=monotone)


def _fit_regressor_early_stop(X_tr, y_tr, X_val, y_val, monotone):
    """
    Fit an XGBRegressor with early stopping against (X_val, y_val).
    Handles both xgboost>=2.0 (constructor arg) and older (fit kwarg) APIs.
    Returns the number of boosting rounds at the best iteration.
    """
    kwargs = _regressor_kwargs(N_ESTIMATORS_MAX, monotone)
    try:
        model = XGBRegressor(early_stopping_rounds=EARLY_STOPPING_ROUNDS, **kwargs)
        model.fit(X_tr, y_tr, eval_set=[(X_val, y_val)], verbose=False)
    except TypeError:
        model = XGBRegressor(**kwargs)
        model.fit(X_tr, y_tr, eval_set=[(X_val, y_val)],
                  early_stopping_rounds=EARLY_STOPPING_ROUNDS, verbose=False)
    best_iteration = getattr(model, "best_iteration", None)
    if best_iteration is None:
        return N_ESTIMATORS_MAX
    return max(1, int(best_iteration) + 1)


def train_regressor(X_train, y_train, monotone):
    """
    Train a regressor with early stopping on a chronological 85/15 split
    (rows are already in game-date order), then refit on ALL training rows
    with n_estimators = best_iteration so no data is wasted.
    Returns (fitted Pipeline, n_estimators used).
    """
    n = X_train.shape[0]
    if n >= MIN_ROWS_FOR_EARLY_STOP:
        split = int(n * (1.0 - VALIDATION_FRACTION))
        best_n = _fit_regressor_early_stop(
            X_train[:split], y_train[:split], X_train[split:], y_train[split:], monotone)
        print(f"[train]   early stop : val rows {n - split:,}, "
              f"best_iteration → {best_n} trees (ceiling {N_ESTIMATORS_MAX})")
    else:
        best_n = DEFAULT_N_ESTIMATORS
        print(f"[train]   early stop : skipped ({n} rows < {MIN_ROWS_FOR_EARLY_STOP}), "
              f"using n_estimators={best_n}")
    model = Pipeline([("model", XGBRegressor(**_regressor_kwargs(best_n, monotone)))])
    model.fit(X_train, y_train)
    return model, best_n


def walk_forward_report(X, y_spread, y_total, y_win, seasons_arr,
                        train_seasons, mono_spread, mono_total, mono_winprob):
    """
    Expanding-window walk-forward evaluation over the train seasons: for each
    season s (except the first), train on all earlier train seasons and
    evaluate on s. Cheap fixed-size fits (n_estimators=300) — this is a
    methodology report, not the final model. Returns a list of per-season
    dicts (empty when <2 train seasons).
    """
    wf_seasons = sorted(set(train_seasons))
    if len(wf_seasons) < 2:
        return []

    _sep("Walk-forward evaluation")
    results = []
    print(f"[train]   {'season':>8} | {'train rows':>10} | {'eval rows':>9} | "
          f"{'spread RMSE':>11} | {'total RMSE':>10} | {'Brier':>6}")
    for s in wf_seasons[1:]:
        earlier = [y for y in wf_seasons if y < s]
        tr_mask = np.isin(seasons_arr, earlier)
        te_mask = seasons_arr == s
        n_tr, n_te = int(tr_mask.sum()), int(te_mask.sum())
        if n_tr == 0 or n_te == 0:
            print(f"[train]   {s:>8} | skipped (train rows: {n_tr}, eval rows: {n_te})")
            continue

        sp = XGBRegressor(**_regressor_kwargs(DEFAULT_N_ESTIMATORS, mono_spread))
        sp.fit(X[tr_mask], y_spread[tr_mask])
        sp_rmse = float(root_mean_squared_error(y_spread[te_mask], sp.predict(X[te_mask])))

        tt = XGBRegressor(**_regressor_kwargs(DEFAULT_N_ESTIMATORS, mono_total))
        tt.fit(X[tr_mask], y_total[tr_mask])
        tt_rmse = float(root_mean_squared_error(y_total[te_mask], tt.predict(X[te_mask])))

        clf = XGBClassifier(n_estimators=DEFAULT_N_ESTIMATORS, max_depth=4, learning_rate=0.05,
                            subsample=0.8, colsample_bytree=0.8,
                            objective="binary:logistic", random_state=42,
                            eval_metric="logloss", monotone_constraints=mono_winprob)
        clf.fit(X[tr_mask], y_win[tr_mask])
        brier = float(brier_score_loss(y_win[te_mask], clf.predict_proba(X[te_mask])[:, 1]))

        print(f"[train]   {s:>8} | {n_tr:>10,} | {n_te:>9,} | "
              f"{sp_rmse:>11.3f} | {tt_rmse:>10.3f} | {brier:>6.4f}")
        results.append({
            "season": int(s),
            "train_rows": n_tr,
            "eval_rows": n_te,
            "spread_rmse": round(sp_rmse, 3),
            "total_rmse": round(tt_rmse, 3),
            "brier": round(brier, 4),
        })
    return results


# ── Main ───────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="Train ML prediction models")
    p.add_argument("--train-seasons", required=True,
                   help="Comma-separated season years to train on, e.g. 2023,2024,2025")
    p.add_argument("--test-season", required=True, type=int,
                   help="Season year to use as test set")
    p.add_argument("--model-name", default="baseline",
                   help="Model slug (lowercase letters, digits, hyphens; max 40 chars). "
                        "Output goes to <output-dir>/<model-name>/ (default: baseline)")
    p.add_argument("--feature-set", default=None, choices=sorted(FEATURE_SETS),
                   help="Feature set to train with (default: same as --model-name when "
                        "that names a feature set, else 'baseline')")
    p.add_argument("--db-url", default=None,
                   help="PostgreSQL connection URL (default: built from env vars DB_HOST etc.)")
    p.add_argument("--output-dir", default="/models",
                   help="Base directory for model output (default: /models)")
    args = p.parse_args()
    if not MODEL_NAME_RE.match(args.model_name):
        p.error(f"--model-name '{args.model_name}' is invalid: must match {MODEL_NAME_RE.pattern}")
    if args.feature_set is None:
        args.feature_set = args.model_name if args.model_name in FEATURE_SETS else "baseline"
    return args


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

    model_name = args.model_name
    feature_set = args.feature_set
    feature_list = FEATURE_SETS[feature_set]
    n_features = len(feature_list)
    needs_box = any(name in BOX_FEATURES for name in feature_list)

    mono_spread  = monotone_constraints_str(feature_list, SPREAD_MONO_POS)
    mono_total   = monotone_constraints_str(feature_list, TOTAL_MONO_POS)
    mono_winprob = monotone_constraints_str(feature_list, WINPROB_MONO_POS)

    _sep("Configuration")
    print(f"[train]   model name    : {model_name}")
    print(f"[train]   feature set   : {feature_set}")
    print(f"[train]   train seasons : {train_seasons}")
    print(f"[train]   test season   : {test_season}")
    print(f"[train]   output dir    : {os.path.join(args.output_dir, model_name)}")
    print(f"[train]   features      : {n_features}")

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

    box_df = rpi_df = None
    if needs_box:
        print(f"[train] Querying box-score stat snapshots...")
        t0 = time.time()
        box_df = load_box_stat_snapshots(conn, all_seasons)
        print(f"[train]   → {len(box_df):,} box stat rows ({_fmt_seconds(time.time() - t0)})")
        print(f"[train] Querying RPI snapshots...")
        t0 = time.time()
        rpi_df = load_rpi_snapshots(conn, all_seasons)
        print(f"[train]   → {len(rpi_df):,} RPI rows ({_fmt_seconds(time.time() - t0)})")
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
    if box_df is not None and not box_df.empty:
        box_df["snapshot_date"]  = pd.to_datetime(box_df["snapshot_date"]).dt.date
    if rpi_df is not None and not rpi_df.empty:
        rpi_df["snapshot_date"]  = pd.to_datetime(rpi_df["snapshot_date"]).dt.date

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
    box_stat_index, rpi_index = {}, {}
    if needs_box:
        t0 = time.time()
        box_stat_index = build_box_stat_index(box_df)
        rpi_index = build_rpi_index(rpi_df)
        print(f"[train]   box stat index : {len(box_stat_index):,} keys, "
              f"RPI index: {len(rpi_index):,} keys ({_fmt_seconds(time.time()-t0)})")

    # ── Feature engineering ───────────────────────────────────────────────────
    _sep("Building features")
    print(f"[train] Processing {len(games_df):,} games...")
    t0 = time.time()
    rows_X, rows_y_spread, rows_y_total, rows_y_win, rows_season = [], [], [], [], []
    skipped_ratings, skipped_box = 0, 0
    season_counts = {}   # season_year -> {"kept": n, "ratings": n, "box": n}
    box_miss_counts = {}  # feature name -> count, for diagnostics
    log_interval = max(500, len(games_df) // 10)

    for i, row in enumerate(games_df.itertuples(index=False), 1):
        yr = int(row.season_year)
        counts = season_counts.setdefault(yr, {"kept": 0, "ratings": 0, "box": 0})
        ctx, skip_reason = build_game_context(row, team_game_index, snapshot_index,
                                              param_index, box_stat_index, rpi_index,
                                              needs_box)
        feat = None
        if skip_reason is None:
            feat, missing = assemble_feature_row(feature_list, ctx)
            if feat is None:
                skip_reason = "box" if missing in BOX_FEATURES else "ratings"
                if skip_reason == "box":
                    box_miss_counts[missing] = box_miss_counts.get(missing, 0) + 1
        if feat is None:
            counts[skip_reason] += 1
            if skip_reason == "ratings":
                skipped_ratings += 1
            else:
                skipped_box += 1
            continue
        counts["kept"] += 1
        rows_X.append(feat)
        rows_y_spread.append(row.home_score - row.away_score)
        rows_y_total.append(row.home_score + row.away_score)
        rows_y_win.append(1 if row.home_score > row.away_score else 0)
        rows_season.append(row.season_year)
        if i % log_interval == 0:
            pct = 100 * i / len(games_df)
            kept = len(rows_X)
            print(f"[train]   {i:,}/{len(games_df):,} ({pct:.0f}%) — kept {kept:,}, "
                  f"skipped {skipped_ratings + skipped_box:,} so far "
                  f"({_fmt_seconds(time.time() - t0)})")

    feat_elapsed = time.time() - t0
    kept_total = len(rows_X)
    skipped = skipped_ratings + skipped_box
    print(f"[train] Feature build complete: {kept_total:,} rows kept, "
          f"{skipped:,} skipped ({skipped_ratings:,} ratings, {skipped_box:,} box) "
          f"in {_fmt_seconds(feat_elapsed)}")
    print(f"[train] Per-season feature build:")
    for yr in sorted(season_counts):
        c = season_counts[yr]
        total = c["kept"] + c["ratings"] + c["box"]
        print(f"[train]   season {yr}: kept {c['kept']:,} / {total:,} "
              f"(ratings skips {c['ratings']:,}, box skips {c['box']:,})")
    if box_miss_counts:
        top = sorted(box_miss_counts.items(), key=lambda kv: -kv[1])
        detail = ", ".join(f"{name}: {n:,}" for name, n in top)
        print(f"[train]   box skips by first missing feature — {detail}")

    # Box-score skips (pace-v2-only features) never fail the run — early seasons may
    # simply lack team_stat_snapshots history — but a mostly-skipped season deserves
    # a loud warning so nobody trains on a sliver of data unknowingly.
    BOX_WARN_PCT = 60.0
    for yr in sorted(season_counts):
        c = season_counts[yr]
        total = c["kept"] + c["ratings"] + c["box"]
        if total > 0 and 100 * c["box"] / total > BOX_WARN_PCT:
            print(f"[train] WARNING: season {yr} skipped {100 * c['box'] / total:.1f}% of games "
                  f"for missing box-score stats (limit for silence {BOX_WARN_PCT:.0f}%). "
                  f"Check team_stat_snapshots / team_season_stat_snapshots coverage.")

    # Early-season games are legitimately skipped (no pre-game snapshot yet), which is
    # normally well under 15%. A high skip rate means snapshots are missing wholesale —
    # e.g. power ratings never computed for a season, or a model_type string mismatch.
    # Only ratings skips count here; box-score skips warn above instead.
    MAX_SKIP_PCT = 30.0
    ratings_skip_pct = 100 * skipped_ratings / len(games_df)
    if ratings_skip_pct > MAX_SKIP_PCT:
        print(f"[train] ERROR: {ratings_skip_pct:.1f}% of games were skipped for missing "
              f"ratings (limit {MAX_SKIP_PCT:.0f}%).")
        print(f"[train]        Check that Power Ratings have been calculated for all requested")
        print(f"[train]        seasons and that snapshot model_type values match "
              f"(MASSEY, MASSEY_TOTALS, BRADLEY_TERRY, BRADLEY_TERRY_W).")
        sys.exit(1)

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

    # ── Walk-forward report (expanding window over train seasons) ─────────────
    walk_forward = walk_forward_report(X, y_spread, y_total, y_win, seasons,
                                       train_seasons, mono_spread, mono_total,
                                       mono_winprob)

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
    print(f"[train] XGBRegressor(max_depth=4, lr=0.05, early stopping, monotone) ...")
    t0 = time.time()
    spread_model, spread_n = train_regressor(X_train, ys_train, mono_spread)
    elapsed = time.time() - t0
    spread_preds = spread_model.predict(X_test)
    spread_rmse = root_mean_squared_error(ys_test, spread_preds)
    spread_mae  = float(np.abs(ys_test - spread_preds).mean())
    baseline_rmse = float(np.sqrt(((ys_test - ys_train.mean()) ** 2).mean()))
    print(f"[train]   fit time  : {_fmt_seconds(elapsed)}  (n_estimators={spread_n})")
    print(f"[train]   RMSE      : {spread_rmse:.3f} pts  (baseline naive: {baseline_rmse:.3f} pts)")
    print(f"[train]   MAE       : {spread_mae:.3f} pts")
    correct_side = float(((spread_preds > 0) == (ys_test > 0)).mean() * 100)
    print(f"[train]   side acc  : {correct_side:.1f}%  (predicted correct winner)")

    # ── Total model ────────────────────────────────────────────────────────────
    _sep("Total model")
    print(f"[train] XGBRegressor(max_depth=4, lr=0.05, early stopping, monotone) ...")
    t0 = time.time()
    total_model, total_n = train_regressor(X_train, yt_train, mono_total)
    elapsed = time.time() - t0
    total_preds = total_model.predict(X_test)
    total_rmse = root_mean_squared_error(yt_test, total_preds)
    total_mae  = float(np.abs(yt_test - total_preds).mean())
    baseline_total_rmse = float(np.sqrt(((yt_test - yt_train.mean()) ** 2).mean()))
    print(f"[train]   fit time  : {_fmt_seconds(elapsed)}  (n_estimators={total_n})")
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
                             eval_metric="logloss", monotone_constraints=mono_winprob)
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
    output_dir = os.path.join(args.output_dir, model_name)
    pending_dir = os.path.join(output_dir, ".pending")
    os.makedirs(pending_dir, exist_ok=True)

    for label, fn, path in [
        ("spread",   "spread_model.onnx",  os.path.join(pending_dir, "spread_model.onnx")),
        ("total",    "total_model.onnx",   os.path.join(pending_dir, "total_model.onnx")),
        ("winprob",  "winprob_model.onnx", os.path.join(pending_dir, "winprob_model.onnx")),
    ]:
        t0 = time.time()
        if label == "winprob":
            export_classifier(winprob_model, path, n_features)
        else:
            export_regressor(spread_model if label == "spread" else total_model, path, n_features)
        size_kb = os.path.getsize(path) / 1024
        print(f"[train]   {label:<8} → {fn}  ({size_kb:.0f} KB, {_fmt_seconds(time.time()-t0)})")

    features_meta = {
        "version": datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "slug": model_name,
        "display_name": display_name_for(model_name),
        "feature_set": feature_set,
        "features": feature_list,
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
        "walk_forward": walk_forward,
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
    print(f"[train]   Model           : {model_name} (feature set {feature_set}, {n_features} features)")
    print(f"[train]   Total wall time : {_fmt_seconds(total_elapsed)}")
    print(f"[train]   Training rows   : {X_train.shape[0]:,}  |  Test rows: {X_test.shape[0]:,}{sample_tag}")
    print(f"[train]   Spread  RMSE={spread_rmse:.3f} pts  MAE={spread_mae:.3f} pts  "
          f"side-acc={correct_side:.1f}%  trees={spread_n}")
    print(f"[train]   Total   RMSE={total_rmse:.3f} pts  MAE={total_mae:.3f} pts  trees={total_n}")
    print(f"[train]   WinProb Brier={brier:.4f}  acc={acc:.1f}%")
    print(f"[train]   Models written to {output_dir}")
    _sep()


if __name__ == "__main__":
    main()
