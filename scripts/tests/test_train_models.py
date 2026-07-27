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
    return pd.DataFrame(rows, columns=[
        "game_date", "home_team_id", "away_team_id",
        "home_score", "away_score", "season_id",
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
        fit, keep = tm.prepare_targets(y_spread, y_total, is_ot)
        assert fit.tolist() == [30.0, -30.0, 10.0]
        assert keep.tolist() == [True, False, True]
