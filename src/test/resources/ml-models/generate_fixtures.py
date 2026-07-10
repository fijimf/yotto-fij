"""Generate minimal deterministic ONNX fixtures for MlPredictionServiceTest.

- spread_model.onnx : output = input[0]  (massey_beta_home)          shape [N,1]
- total_model.onnx  : output = input[5]  (massey_gamma_sum)          shape [N,1]
- winprob_model.onnx: probabilities = softmax(zeros) = [0.5, 0.5]    shape [N,2]

Input name "float_input" [None, 27], matching the real skl2onnx export contract.

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

N_FEATURES = 27
OUT_DIR = sys.argv[1]

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
assert len(FEATURE_NAMES) == N_FEATURES

OPSET = [helper.make_opsetid("", 17)]


def save(graph, path):
    model = helper.make_model(graph, opset_imports=OPSET, ir_version=8)
    onnx.checker.check_model(model)
    onnx.save(model, path)
    print(f"wrote {path} ({os.path.getsize(path)} bytes)")


def regressor(feature_idx, path):
    """MatMul selecting one feature: output[N,1] = input[:, feature_idx]."""
    w = np.zeros((N_FEATURES, 1), dtype=np.float32)
    w[feature_idx, 0] = 1.0
    graph = helper.make_graph(
        nodes=[helper.make_node("MatMul", ["float_input", "W"], ["variable"])],
        name="fixture_regressor",
        inputs=[helper.make_tensor_value_info("float_input", TensorProto.FLOAT, [None, N_FEATURES])],
        outputs=[helper.make_tensor_value_info("variable", TensorProto.FLOAT, [None, 1])],
        initializer=[helper.make_tensor("W", TensorProto.FLOAT, w.shape, w.flatten())],
    )
    save(graph, path)


def classifier(path):
    """MatMul with zero weights -> Softmax => probabilities [N,2] = [0.5, 0.5]."""
    w = np.zeros((N_FEATURES, 2), dtype=np.float32)
    graph = helper.make_graph(
        nodes=[
            helper.make_node("MatMul", ["float_input", "W"], ["logits"]),
            helper.make_node("Softmax", ["logits"], ["probabilities"], axis=1),
        ],
        name="fixture_classifier",
        inputs=[helper.make_tensor_value_info("float_input", TensorProto.FLOAT, [None, N_FEATURES])],
        outputs=[helper.make_tensor_value_info("probabilities", TensorProto.FLOAT, [None, 2])],
        initializer=[helper.make_tensor("W", TensorProto.FLOAT, w.shape, w.flatten())],
    )
    save(graph, path)


os.makedirs(OUT_DIR, exist_ok=True)
regressor(0, os.path.join(OUT_DIR, "spread_model.onnx"))   # massey_beta_home
regressor(5, os.path.join(OUT_DIR, "total_model.onnx"))    # massey_gamma_sum
classifier(os.path.join(OUT_DIR, "winprob_model.onnx"))

features_meta = {
    "version": "test-fixture-1",
    "features": FEATURE_NAMES,
    "spread_model": "spread_model.onnx",
    "total_model": "total_model.onnx",
    "winprob_model": "winprob_model.onnx",
    "metrics": {
        "spread_rmse": 10.5,
        "spread_mae": 8.25,
        "total_rmse": 17.75,
        "total_mae": 14.0,
        "brier_score": 0.1875,
        "win_accuracy": 72.5,
        "in_sample": False,
    },
}
with open(os.path.join(OUT_DIR, "features.json"), "w") as f:
    json.dump(features_meta, f, indent=2)
print("wrote features.json")
