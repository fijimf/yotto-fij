"""Generate minimal deterministic ONNX fixtures for ML serving tests.

Baseline bundle (this directory, legacy flat layout → slug "baseline"), 27 features:
- spread_model.onnx : output = input[0]  (massey_beta_home)          shape [N,1]
- total_model.onnx  : output = input[5]  (massey_gamma_sum)          shape [N,1]
- winprob_model.onnx: probabilities = softmax(zeros) = [0.5, 0.5]    shape [N,2]

Prior-v3 bundle (../ml-models-prior-v3, flat layout, manifest slug "prior-v3-fixture"),
69 features — outputs echo prior-v3 features so integration tests can observe them:
- spread_model.onnx : output = input[41] (home_prev_beta)
- total_model.onnx  : output = input[67] (home_massey_resid_l5)
- winprob_model.onnx: probabilities [0.5, 0.5]

Eff-v4 bundle (../ml-models-eff-v4, flat layout, manifest slug "eff-v4-fixture"),
77 features:
- spread_model.onnx : output = input[69] (home_adj_off)
- total_model.onnx  : output = input[75] (adj_eff_diff)
- winprob_model.onnx: probabilities [0.5, 0.5]

Input name "float_input" [None, n], matching the real skl2onnx export contract.

Regenerate (only needed if the feature schema changes):
    pip install onnx numpy
    python generate_fixtures.py .
"""
import json
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper

OUT_DIR = sys.argv[1]

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
    "home_off_eff", "away_off_eff", "home_def_eff", "away_def_eff",
    "home_efg_pct", "away_efg_pct", "home_opp_efg_pct", "away_opp_efg_pct",
    "home_tov_rate", "away_tov_rate",
    "home_rpi", "away_rpi",
]
PRIOR_V3_EXTRAS = [
    "home_prev_beta", "away_prev_beta",
    "home_prev_theta", "away_prev_theta",
    "home_prev_available", "away_prev_available",
    "home_orb_pct", "away_orb_pct",
    "home_drb_pct", "away_drb_pct",
    "home_ft_rate", "away_ft_rate",
    "home_opp_ft_rate", "away_opp_ft_rate",
    "home_opp_tov_rate", "away_opp_tov_rate",
    "home_fg3_rate", "away_fg3_rate",
    "home_stddev_margin", "away_stddev_margin",
    "home_rpi_owp", "away_rpi_owp",
    "home_win_pct_l10", "away_win_pct_l10",
    "home_avg_margin_l10", "away_avg_margin_l10",
    "home_massey_resid_l5", "away_massey_resid_l5",
]
EFF_V4_EXTRAS = [
    "home_adj_off", "away_adj_off",
    "home_adj_def", "away_adj_def",
    "adj_eff_matchup_home", "adj_eff_matchup_away",
    "adj_eff_diff", "adj_eff_total",
]
PRIOR_V3_FEATURES = BASELINE_FEATURES + PACE_V2_EXTRAS + PRIOR_V3_EXTRAS
EFF_V4_FEATURES = PRIOR_V3_FEATURES + EFF_V4_EXTRAS
assert len(BASELINE_FEATURES) == 27
assert len(PRIOR_V3_FEATURES) == 69
assert len(EFF_V4_FEATURES) == 77

OPSET = [helper.make_opsetid("", 17)]


def save(graph, path):
    model = helper.make_model(graph, opset_imports=OPSET, ir_version=8)
    onnx.checker.check_model(model)
    onnx.save(model, path)
    print(f"wrote {path} ({os.path.getsize(path)} bytes)")


def regressor(n_features, feature_idx, path):
    """MatMul selecting one feature: output[N,1] = input[:, feature_idx]."""
    w = np.zeros((n_features, 1), dtype=np.float32)
    w[feature_idx, 0] = 1.0
    graph = helper.make_graph(
        nodes=[helper.make_node("MatMul", ["float_input", "W"], ["variable"])],
        name="fixture_regressor",
        inputs=[helper.make_tensor_value_info("float_input", TensorProto.FLOAT, [None, n_features])],
        outputs=[helper.make_tensor_value_info("variable", TensorProto.FLOAT, [None, 1])],
        initializer=[helper.make_tensor("W", TensorProto.FLOAT, w.shape, w.flatten())],
    )
    save(graph, path)


def classifier(n_features, path):
    """MatMul with zero weights -> Softmax => probabilities [N,2] = [0.5, 0.5]."""
    w = np.zeros((n_features, 2), dtype=np.float32)
    graph = helper.make_graph(
        nodes=[
            helper.make_node("MatMul", ["float_input", "W"], ["logits"]),
            helper.make_node("Softmax", ["logits"], ["probabilities"], axis=1),
        ],
        name="fixture_classifier",
        inputs=[helper.make_tensor_value_info("float_input", TensorProto.FLOAT, [None, n_features])],
        outputs=[helper.make_tensor_value_info("probabilities", TensorProto.FLOAT, [None, 2])],
        initializer=[helper.make_tensor("W", TensorProto.FLOAT, w.shape, w.flatten())],
    )
    save(graph, path)


def write_bundle(out_dir, features, spread_idx, total_idx, meta_extra):
    os.makedirs(out_dir, exist_ok=True)
    regressor(len(features), spread_idx, os.path.join(out_dir, "spread_model.onnx"))
    regressor(len(features), total_idx, os.path.join(out_dir, "total_model.onnx"))
    classifier(len(features), os.path.join(out_dir, "winprob_model.onnx"))
    meta = {
        "version": "test-fixture-1",
        "features": features,
        "spread_model": "spread_model.onnx",
        "total_model": "total_model.onnx",
        "winprob_model": "winprob_model.onnx",
        "train_seasons": [2021, 2022],
        "test_season": 2026,
        "metrics": {
            "spread_rmse": 10.5,
            "spread_mae": 8.25,
            "total_rmse": 17.75,
            "total_mae": 14.0,
            "brier_score": 0.1875,
            "win_accuracy": 72.5,
            "in_sample": False,
        },
        "walk_forward": [
            {"season": 2022, "train_rows": 3702, "eval_rows": 5259,
             "spread_rmse": 11.5, "total_rmse": 17.0, "brier": 0.195},
            {"season": 2023, "train_rows": 8961, "eval_rows": 5499,
             "spread_rmse": 12.5, "total_rmse": 17.5, "brier": 0.185},
        ],
        **meta_extra,
    }
    with open(os.path.join(out_dir, "features.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print(f"wrote {os.path.join(out_dir, 'features.json')}")


# Baseline bundle: spread echoes massey_beta_home, total echoes massey_gamma_sum
write_bundle(OUT_DIR, BASELINE_FEATURES, 0, 5, {})

# Prior-v3 bundle: spread echoes home_prev_beta (41), total home_massey_resid_l5 (67)
write_bundle(
    os.path.join(OUT_DIR, "..", "ml-models-prior-v3"),
    PRIOR_V3_FEATURES,
    PRIOR_V3_FEATURES.index("home_prev_beta"),
    PRIOR_V3_FEATURES.index("home_massey_resid_l5"),
    {"slug": "prior-v3-fixture", "display_name": "Prior V3 Fixture", "feature_set": "prior-v3"},
)

# Eff-v4 bundle: spread echoes home_adj_off (69), total adj_eff_diff (75)
write_bundle(
    os.path.join(OUT_DIR, "..", "ml-models-eff-v4"),
    EFF_V4_FEATURES,
    EFF_V4_FEATURES.index("home_adj_off"),
    EFF_V4_FEATURES.index("adj_eff_diff"),
    {"slug": "eff-v4-fixture", "display_name": "Eff V4 Fixture", "feature_set": "eff-v4"},
)
