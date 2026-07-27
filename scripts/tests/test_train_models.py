"""Pure-function tests for scripts/train_models.py (no DB, no network).

Run from the repo root:
    .venv/bin/python -m pytest scripts/tests -q
"""
import collections
import datetime as dt
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import train_models as tm  # noqa: E402


def _games(rows):
    """Rows are (date, home_id, away_id, home_score, away_score, season_id[, neutral])."""
    padded = [r if len(r) == 7 else (*r, False) for r in rows]
    return pd.DataFrame(padded, columns=[
        "game_date", "home_team_id", "away_team_id",
        "home_score", "away_score", "season_id", "neutral_site",
    ])


class TestSeasonScopedRollingWindows:

    def _index(self):
        return tm.build_team_game_index(_games([
            # Season 1: team 10 wins five straight in March by 10, total 150
            *[(dt.date(2025, 3, d), 10, 20, 80, 70, 1) for d in range(1, 6)],
            # Season 2: team 10 loses its opener in November, then plays again
            (dt.date(2025, 11, 10), 10, 30, 60, 75, 2),
            (dt.date(2025, 11, 14), 30, 10, 65, 66, 2),
        ]))

    def test_index_is_keyed_by_team_and_season(self):
        idx = self._index()
        assert (10, 1) in idx and (10, 2) in idx
        assert len(idx[(10, 1)][0]) == 5
        assert len(idx[(10, 2)][0]) == 2

    def test_first_game_of_season_is_cold_start_despite_prior_season_history(self):
        idx = self._index()
        assert tm.rolling_stats_fast(idx, 10, 2, dt.date(2025, 11, 10)) == (None,) * 5

    def test_window_sees_only_same_season_games(self):
        idx = self._index()
        win_pct, avg_margin, avg_total, stddev, rest = \
            tm.rolling_stats_fast(idx, 10, 2, dt.date(2025, 11, 14))
        # Exactly the one earlier season-2 game (a 60-75 loss), not March's wins
        assert win_pct == 0.0
        assert avg_margin == -15.0
        assert avg_total == 135.0
        assert stddev == 0.0
        assert rest == 4

    def test_prior_season_window_unaffected(self):
        idx = self._index()
        win_pct, avg_margin, avg_total, _, _ = \
            tm.rolling_stats_fast(idx, 10, 1, dt.date(2025, 4, 1))
        assert win_pct == 1.0
        assert avg_margin == 10.0
        assert avg_total == 150.0

    def test_unknown_team_or_season_is_cold_start(self):
        idx = self._index()
        assert tm.rolling_stats_fast(idx, 99, 1, dt.date(2025, 4, 1)) == (None,) * 5
        assert tm.rolling_stats_fast(idx, 10, 3, dt.date(2025, 4, 1)) == (None,) * 5

    def test_window_caps_at_n_games(self):
        idx = tm.build_team_game_index(_games([
            (dt.date(2025, 1, d), 10, 20, 70 + d, 70, 1) for d in range(1, 8)
        ]))
        # 7 earlier games, n=5 → margins are the LAST five (d=2..6): 2,3,4,5,6
        win_pct, avg_margin, _, _, rest = tm.rolling_stats_fast(idx, 10, 1, dt.date(2025, 1, 7))
        assert win_pct == 1.0
        assert avg_margin == 4.0
        assert rest == 1


class TestNonD1Filter:

    Row = collections.namedtuple(
        "Row", ["season_id", "home_team_id", "away_team_id", "home_is_member", "away_is_member"])

    def _index(self, appearances_by_team):
        """Index where each team has the given number of season-1 games."""
        rows = []
        day = 1
        for tid, n in appearances_by_team.items():
            for i in range(n):
                rows.append((dt.date(2025, 1 + (day + i) // 28, 1 + (day + i) % 28),
                             tid, 999, 70, 60, 1))
            day += n
        return tm.build_team_game_index(_games(rows))

    def test_non_member_with_few_games_is_skipped(self):
        idx = self._index({10: 30, 50: 2})
        row = self.Row(1, 10, 50, True, False)
        assert tm.is_non_d1(row, idx) is True

    def test_non_member_with_full_schedule_is_kept(self):
        # Membership data gap (e.g. Pac-12 2022): no membership row but a real season
        idx = self._index({10: 30, 50: 30})
        row = self.Row(1, 10, 50, True, False)
        assert tm.is_non_d1(row, idx) is False

    def test_members_are_always_kept(self):
        idx = self._index({10: 2, 50: 2})
        row = self.Row(1, 10, 50, True, True)
        assert tm.is_non_d1(row, idx) is False

    def test_unknown_team_without_membership_is_skipped(self):
        idx = self._index({10: 30})
        row = self.Row(1, 10, 777, True, False)
        assert tm.is_non_d1(row, idx) is True

    def test_rows_without_membership_columns_default_to_d1(self):
        Legacy = collections.namedtuple("Legacy", ["season_id", "home_team_id", "away_team_id"])
        assert tm.is_non_d1(Legacy(1, 10, 50), {}) is False


class TestOvertimeHandling:

    def test_is_overtime(self):
        Row = collections.namedtuple("Row", ["periods"])
        assert tm.is_overtime(Row(periods=3)) is True
        assert tm.is_overtime(Row(periods=4)) is True
        assert tm.is_overtime(Row(periods=2)) is False
        assert tm.is_overtime(Row(periods=None)) is False
        assert tm.is_overtime(Row(periods=float("nan"))) is False
        Legacy = collections.namedtuple("Legacy", ["game_id"])
        assert tm.is_overtime(Legacy(game_id=1)) is False

    def test_prepare_targets_clips_spread_and_masks_ot_totals(self):
        import numpy as np
        y_spread = np.array([45.0, -35.0, 10.0], dtype=np.float32)
        y_total = np.array([150.0, 160.0, 170.0], dtype=np.float32)
        is_ot = np.array([False, True, False])
        fit, keep, base = tm.prepare_targets(y_spread, y_total, is_ot)
        assert fit.tolist() == [30.0, -30.0, 10.0]
        assert keep.tolist() == [True, False, True]
        assert base.tolist() == [0.0, 0.0, 0.0]


class TestMasseyResidualL5:
    """
    Hand-computed contract scenario — the SAME literals are asserted through the Java
    serving path in PriorV3PredictionIntegrationTest. Do not change one side only.

    Teams H=100, A=200, O=300; season 1; hca = 3.0 (from 2025-01-02).
    g1 2025-01-05  H 80–70 O   snapshots 01-04: H=5.0 O=2.0 A=4.0
    g2 2025-01-12  O 75–72 H   snapshots 01-11: H=5.5 O=2.5
    g3 2025-01-10  A 60–58 O
    resid(H) = ((10 − (5.0−2.0+3.0)) + −(3 − (2.5−5.5+3.0))) / 2 = (4.0 − 3.0)/2 = 0.5
    resid(A) = (2 − (4.0−2.0+3.0)) = −3.0
    """

    def _fixtures(self):
        games = _games([
            (dt.date(2025, 1, 5), 100, 300, 80, 70, 1),
            (dt.date(2025, 1, 12), 300, 100, 75, 72, 1),
            (dt.date(2025, 1, 10), 200, 300, 60, 58, 1),
        ])
        snaps = pd.DataFrame([
            (100, 1, "MASSEY", dt.date(2025, 1, 4), 5.0, 5),
            (300, 1, "MASSEY", dt.date(2025, 1, 4), 2.0, 5),
            (200, 1, "MASSEY", dt.date(2025, 1, 4), 4.0, 5),
            (100, 1, "MASSEY", dt.date(2025, 1, 11), 5.5, 6),
            (300, 1, "MASSEY", dt.date(2025, 1, 11), 2.5, 6),
        ], columns=["team_id", "season_id", "model_type", "snapshot_date", "rating", "games_played"])
        params = pd.DataFrame([
            (1, "MASSEY", "hca", dt.date(2025, 1, 2), 3.0),
        ], columns=["season_id", "model_type", "param_name", "snapshot_date", "param_value"])
        return (tm.build_team_game_index(games),
                tm.build_snapshot_index(snaps),
                tm.build_param_index(params))

    def test_home_team_residual_matches_hand_computation(self):
        game_idx, snap_idx, param_idx = self._fixtures()
        resid = tm.massey_residual_l5(game_idx, snap_idx, param_idx, 100, 1, dt.date(2025, 1, 20))
        assert resid == 0.5

    def test_away_perspective_residual(self):
        game_idx, snap_idx, param_idx = self._fixtures()
        resid = tm.massey_residual_l5(game_idx, snap_idx, param_idx, 200, 1, dt.date(2025, 1, 20))
        assert resid == -3.0

    def test_no_usable_past_snapshot_returns_none(self):
        game_idx, snap_idx, param_idx = self._fixtures()
        assert tm.massey_residual_l5(game_idx, {}, param_idx, 100, 1, dt.date(2025, 1, 20)) is None

    def test_unusable_past_games_are_skipped_not_fatal(self):
        game_idx, _, param_idx = self._fixtures()
        # Only the 01-11 snapshots exist → g1 (needs 01-04) is unusable, g2 usable:
        # resid(H) = −(3 − (2.5−5.5+3.0)) = −3.0 over the single usable game
        snaps = pd.DataFrame([
            (100, 1, "MASSEY", dt.date(2025, 1, 11), 5.5, 6),
            (300, 1, "MASSEY", dt.date(2025, 1, 11), 2.5, 6),
        ], columns=["team_id", "season_id", "model_type", "snapshot_date", "rating", "games_played"])
        resid = tm.massey_residual_l5(game_idx, tm.build_snapshot_index(snaps), param_idx,
                                      100, 1, dt.date(2025, 1, 20))
        assert resid == -3.0

    def test_no_prior_games_returns_none(self):
        game_idx, snap_idx, param_idx = self._fixtures()
        assert tm.massey_residual_l5(game_idx, snap_idx, param_idx, 100, 1, dt.date(2025, 1, 5)) is None
        assert tm.massey_residual_l5(game_idx, snap_idx, param_idx, 999, 1, dt.date(2025, 1, 20)) is None


class TestPreseasonPriorLookup:

    def test_end_of_season_sentinel_picks_last_snapshot(self):
        snaps = pd.DataFrame([
            (100, 7, "MASSEY", dt.date(2025, 1, 4), 5.0, 5),
            (100, 7, "MASSEY", dt.date(2025, 3, 30), 9.25, 30),
        ], columns=["team_id", "season_id", "model_type", "snapshot_date", "rating", "games_played"])
        idx = tm.build_snapshot_index(snaps)
        rating, gp = tm.lookup_snapshot(idx, 100, 7, "MASSEY", tm.END_OF_SEASON)
        assert rating == 9.25 and gp == 30

    def test_feature_set_registration(self):
        assert len(tm.FEATURE_SETS["prior-v3"]) == 69
        assert tm.FEATURE_SETS["prior-v3"][:41] == tm.FEATURE_SETS["pace-v2"]
        # every registered set name resolves entirely to registry entries, in order
        for name, features in tm.FEATURE_SETS.items():
            assert all(f in tm.FEATURE_REGISTRY for f in features), name
        # golden order of the prior-v3 extras (guards against accidental reordering)
        assert tm.PRIOR_V3_EXTRAS[0] == "home_prev_beta"
        assert tm.PRIOR_V3_EXTRAS[-1] == "away_massey_resid_l5"
        assert tm.FEATURE_SETS["prior-v3"][41] == "home_prev_beta"
        assert tm.FEATURE_SETS["prior-v3"][67] == "home_massey_resid_l5"


class TestAdjEfficiencyFeatures:
    """
    Contract literals shared with EffV4PredictionIntegrationTest (Java):
    off_H=112, def_H=5, off_A=104, def_A=2 →
    matchup_home = 112−2 = 110, matchup_away = 104−5 = 99, diff = 11, total = 209.
    """

    def _snapshot_index(self):
        snaps = pd.DataFrame([
            (100, 1, "ADJ_OFF", dt.date(2025, 1, 14), 112.0, 10),
            (100, 1, "ADJ_DEF", dt.date(2025, 1, 14), 5.0, 10),
            (200, 1, "ADJ_OFF", dt.date(2025, 1, 14), 104.0, 10),
            (200, 1, "ADJ_DEF", dt.date(2025, 1, 14), 2.0, 10),
        ], columns=["team_id", "season_id", "model_type", "snapshot_date", "rating", "games_played"])
        return tm.build_snapshot_index(snaps)

    def test_matchup_math_matches_contract(self):
        ctx = tm.adj_efficiency_context(self._snapshot_index(), 100, 200, 1, dt.date(2025, 1, 20))
        assert ctx["home_adj_off"] == 112.0
        assert ctx["away_adj_def"] == 2.0
        assert ctx["adj_eff_matchup_home"] == 110.0
        assert ctx["adj_eff_matchup_away"] == 99.0
        assert ctx["adj_eff_diff"] == 11.0
        assert ctx["adj_eff_total"] == 209.0

    def test_missing_any_rating_nulls_derived_features(self):
        ctx = tm.adj_efficiency_context(self._snapshot_index(), 100, 999, 1, dt.date(2025, 1, 20))
        assert ctx["home_adj_off"] == 112.0
        assert ctx["away_adj_off"] is None
        assert ctx["adj_eff_diff"] is None
        assert ctx["adj_eff_total"] is None

    def test_eff_v4_registration_and_monotone(self):
        assert len(tm.FEATURE_SETS["eff-v4"]) == 77
        assert tm.FEATURE_SETS["eff-v4"][:69] == tm.FEATURE_SETS["prior-v3"]
        assert tm.FEATURE_SETS["eff-v4"][69] == "home_adj_off"
        assert tm.FEATURE_SETS["eff-v4"][75] == "adj_eff_diff"
        assert all(f in tm.FEATURE_REGISTRY for f in tm.EFF_V4_EXTRAS)
        assert set(tm.EFF_V4_EXTRAS) <= tm.BOX_FEATURES
        # monotone constraint strings place +1 at the right positions
        spread = tm.monotone_constraints_str(tm.FEATURE_SETS["eff-v4"], tm.SPREAD_MONO_POS)
        total = tm.monotone_constraints_str(tm.FEATURE_SETS["eff-v4"], tm.TOTAL_MONO_POS)
        assert spread.strip("()").split(",")[75] == "1"   # adj_eff_diff
        assert total.strip("()").split(",")[76] == "1"    # adj_eff_total
        assert spread.strip("()").split(",")[76] == "0"


class TestPhase4Training:

    def test_residual_target_round_trip(self):
        import numpy as np
        y_spread = np.array([10.0, -3.0, 45.0], dtype=np.float32)
        y_total = np.array([150.0, 140.0, 160.0], dtype=np.float32)
        is_ot = np.array([False, False, False])
        massey = np.array([6.0, 0.0, 5.0], dtype=np.float32)
        fit, _, base = tm.prepare_targets(y_spread, y_total, is_ot, massey, "residual_massey")
        # target is the clipped residual; reconstruction restores the full margin
        assert fit.tolist() == [4.0, -3.0, 30.0]   # 40 clips to 30
        assert base.tolist() == [6.0, 0.0, 5.0]
        assert (fit + base).tolist() == [10.0, -3.0, 35.0]

    def test_season_weights(self):
        import numpy as np
        seasons = np.array([2023, 2024, 2025])
        assert tm.season_weights(seasons, 1.0, 2025).tolist() == [1.0, 1.0, 1.0]
        w = tm.season_weights(seasons, 0.5, 2025)
        assert w.tolist() == [0.25, 0.5, 1.0]

    def test_derived_probs_and_sigma(self):
        import numpy as np
        # Φ(0) = 0.5; positive spreads favor home; symmetry
        probs = tm.derived_probs(np.array([0.0, 10.0, -10.0]), 10.0)
        assert abs(probs[0] - 0.5) < 1e-12
        assert abs(probs[1] - 0.8413447) < 1e-6
        assert abs(probs[1] + probs[2] - 1.0) < 1e-12
        # sigma from residuals; degenerate fallback
        sigma = tm.fit_margin_sigma(np.array([0.0, 0.0]), np.array([10.0, -10.0]))
        assert abs(sigma - np.std([10.0, -10.0], ddof=1)) < 1e-9
        assert tm.fit_margin_sigma(np.array([1.0]), np.array([5.0])) == 11.0

    def test_hyperparam_overrides_merge_into_kwargs(self):
        kwargs = tm._regressor_kwargs(300, "(0,1)", {"max_depth": 7, "reg_alpha": 2.5})
        assert kwargs["max_depth"] == 7
        assert kwargs["reg_alpha"] == 2.5
        assert kwargs["monotone_constraints"] == "(0,1)"
        ckw = tm._classifier_kwargs(300, "(1,0)", {"learning_rate": 0.02})
        assert ckw["learning_rate"] == 0.02
        assert ckw["objective"] == "binary:logistic"

    def test_tuning_runs_on_tiny_synthetic_data(self):
        import numpy as np
        rng = np.random.RandomState(0)
        n = 120
        X = rng.randn(n, 3).astype(np.float32)
        y = (2.0 * X[:, 0] + rng.randn(n) * 0.1).astype(np.float32)
        seasons = np.array([2023] * 60 + [2024] * 60)
        base = np.zeros(n, dtype=np.float32)
        params = tm.tune_hyperparams(X, y, y, base, seasons, [2023, 2024],
                                     "(0,0,0)", 1.0, 2)
        assert set(params) == {"max_depth", "learning_rate", "min_child_weight",
                               "subsample", "colsample_bytree", "reg_alpha", "reg_lambda"}

    def test_tuning_skipped_with_single_season(self):
        import numpy as np
        X = np.zeros((10, 2), dtype=np.float32)
        y = np.zeros(10, dtype=np.float32)
        assert tm.tune_hyperparams(X, y, y, y, np.array([2024] * 10), [2024],
                                   "(0,0)", 1.0, 5) == {}
