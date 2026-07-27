package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the feature-supplier registry. The golden ordering test mirrors the
 * Python trainer's FEATURE_REGISTRY (scripts/train_models.py) — the two registries must
 * list the same names in the same order; a mismatch means train/serve skew.
 */
class MlFeatureRegistryTest {

    /** Exact expected registry contents, in order — baseline 27, pace-v2 +14, prior-v3 +28. */
    private static final List<String> GOLDEN_ORDER = List.of(
            "massey_beta_home", "massey_beta_away", "massey_beta_diff",
            "massey_gamma_home", "massey_gamma_away", "massey_gamma_sum",
            "bt_theta_home", "bt_theta_away", "bt_logodds",
            "bt_theta_weighted_home", "bt_theta_weighted_away", "bt_logodds_weighted",
            "home_win_pct_l5", "home_avg_margin_l5", "home_avg_total_l5", "home_margin_stddev_l5",
            "away_win_pct_l5", "away_avg_margin_l5", "away_avg_total_l5", "away_margin_stddev_l5",
            "home_games_played", "away_games_played",
            "home_days_rest", "away_days_rest", "season_week",
            "is_neutral_site", "is_conference_game",
            "home_pace", "away_pace",
            "home_off_eff", "away_off_eff", "home_def_eff", "away_def_eff",
            "home_efg_pct", "away_efg_pct", "home_opp_efg_pct", "away_opp_efg_pct",
            "home_tov_rate", "away_tov_rate",
            "home_rpi", "away_rpi",
            "home_prev_beta", "away_prev_beta", "home_prev_theta", "away_prev_theta",
            "home_prev_available", "away_prev_available",
            "home_orb_pct", "away_orb_pct", "home_drb_pct", "away_drb_pct",
            "home_ft_rate", "away_ft_rate", "home_opp_ft_rate", "away_opp_ft_rate",
            "home_opp_tov_rate", "away_opp_tov_rate", "home_fg3_rate", "away_fg3_rate",
            "home_stddev_margin", "away_stddev_margin", "home_rpi_owp", "away_rpi_owp",
            "home_win_pct_l10", "away_win_pct_l10", "home_avg_margin_l10", "away_avg_margin_l10",
            "home_massey_resid_l5", "away_massey_resid_l5",
            "home_adj_off", "away_adj_off", "home_adj_def", "away_adj_def",
            "adj_eff_matchup_home", "adj_eff_matchup_away", "adj_eff_diff", "adj_eff_total");

    private static PredictionContext context(Double prevBeta, Double prevTheta, Double resid) {
        return new PredictionContext(
                7.5, 2.5, 70.25, 68.5, 0.8, 0.3, 0.1, 0.9, 0.35, 0.1,
                0.6, 4.2, 145.8, 9.1, 0.4, -2.6, 150.2, 11.3,
                12, 11, 3, 4, 9, false, true,
                Map.of("orb_pct", 0.31), Map.of("orb_pct", 0.28),
                0.55, 0.52,
                8.0, 9.5, 0.61, 0.58,
                0.7, 0.5, 6.0, -1.5,
                prevBeta, null, prevTheta, null,
                resid, null,
                112.0, 104.0, 5.0, 2.0,
                3.0);
    }

    @Test
    void registryOrderMatchesPythonTrainer() {
        assertThat(List.copyOf(MlFeatureRegistry.names())).isEqualTo(GOLDEN_ORDER);
    }

    @Test
    void priorFeaturesImputeZeroWithAvailabilityFlag() {
        // Present: raw value + flag 1
        float[] present = MlFeatureRegistry.buildVector(
                List.of("home_prev_beta", "home_prev_theta", "home_prev_available"),
                context(7.25, 0.85, 0.5));
        assertThat(present).containsExactly(7.25f, 0.85f, 1.0f);

        // Absent: 0-imputed + flag 0 — the vector still builds (never aborts)
        float[] absent = MlFeatureRegistry.buildVector(
                List.of("home_prev_beta", "home_prev_theta", "home_prev_available",
                        "away_prev_beta", "away_prev_available"),
                context(null, null, 0.5));
        assertThat(absent).containsExactly(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Test
    void nullableProirV3FeaturesAbortTheVector() {
        assertThat(MlFeatureRegistry.buildVector(
                List.of("home_massey_resid_l5"), context(1.0, 1.0, null))).isNull();
        assertThat(MlFeatureRegistry.buildVector(
                List.of("away_stddev_margin", "home_stddev_margin"),
                context(1.0, 1.0, 0.5))).isNotNull();
        // Missing box stat key → null → abort
        assertThat(MlFeatureRegistry.buildVector(
                List.of("home_drb_pct"), context(1.0, 1.0, 0.5))).isNull();
        // Present box stat flows through
        assertThat(MlFeatureRegistry.buildVector(
                List.of("home_orb_pct", "away_orb_pct"), context(1.0, 1.0, 0.5)))
                .containsExactly(0.31f, 0.28f);
    }

    @Test
    void adjEfficiencyDerivedFeaturesMatchContract() {
        // Contract literals shared with pytest TestAdjEfficiencyFeatures:
        // off_H=112, def_H=5, off_A=104, def_A=2 → 110, 99, 11, 209
        float[] v = MlFeatureRegistry.buildVector(
                List.of("adj_eff_matchup_home", "adj_eff_matchup_away", "adj_eff_diff", "adj_eff_total"),
                context(null, null, 0.5));
        assertThat(v).containsExactly(110.0f, 99.0f, 11.0f, 209.0f);

        // Missing any leg nulls the derived features → vector aborts
        PredictionContext missingAway = new PredictionContext(
                7.5, 2.5, 70.25, 68.5, 0.8, 0.3, 0.1, 0.9, 0.35, 0.1,
                0.6, 4.2, 145.8, 9.1, 0.4, -2.6, 150.2, 11.3,
                12, 11, 3, 4, 9, false, true,
                Map.of(), Map.of(), null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                112.0, null, 5.0, 2.0,
                3.0);
        assertThat(MlFeatureRegistry.buildVector(List.of("adj_eff_diff"), missingAway)).isNull();
        assertThat(MlFeatureRegistry.buildVector(List.of("home_adj_off"), missingAway))
                .containsExactly(112.0f);
    }

    @Test
    void dependencyGatesClassifyFeatureNeeds() {
        assertThat(MlFeatureRegistry.needsPriorRatings(List.of("home_prev_available"))).isTrue();
        assertThat(MlFeatureRegistry.needsPriorRatings(List.of("massey_beta_home"))).isFalse();
        assertThat(MlFeatureRegistry.needsResidualForm(List.of("away_massey_resid_l5"))).isTrue();
        assertThat(MlFeatureRegistry.needsResidualForm(List.of("home_rpi"))).isFalse();
        assertThat(MlFeatureRegistry.needsExtendedStats(List.of("home_rpi_owp"))).isTrue();
        assertThat(MlFeatureRegistry.needsExtendedStats(List.of("home_win_pct_l10"))).isFalse();
    }
}
