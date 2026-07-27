package com.yotto.basketball.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Registry of named ML feature suppliers over a {@link PredictionContext}. A model
 * bundle's manifest lists its feature names in order; the input vector is assembled
 * here, so adding a feature is one registry entry (mirrored by name in the Python
 * trainer's registry — the two sides must compute each name identically).
 *
 * <p>A supplier returning {@code null} means the feature is unavailable for this game
 * and any model that uses it must not predict (cold start / missing snapshot). Suppliers
 * encode their own imputation where the trainer does the same (e.g. days-rest → −1).
 */
public final class MlFeatureRegistry {

    private static final Map<String, Function<PredictionContext, Double>> SUPPLIERS = build();

    /** Feature names that require box-score/season-snapshot stats in the context (extra queries). */
    private static final Set<String> EXTENDED_STAT_FEATURES = Set.of(
            "home_pace", "away_pace",
            "home_off_eff", "away_off_eff", "home_def_eff", "away_def_eff",
            "home_efg_pct", "away_efg_pct", "home_opp_efg_pct", "away_opp_efg_pct",
            "home_tov_rate", "away_tov_rate",
            "home_rpi", "away_rpi",
            "home_orb_pct", "away_orb_pct", "home_drb_pct", "away_drb_pct",
            "home_ft_rate", "away_ft_rate", "home_opp_ft_rate", "away_opp_ft_rate",
            "home_opp_tov_rate", "away_opp_tov_rate", "home_fg3_rate", "away_fg3_rate",
            "home_stddev_margin", "away_stddev_margin", "home_rpi_owp", "away_rpi_owp");

    /** Feature names needing previous-season final ratings in the context (extra queries). */
    private static final Set<String> PRIOR_RATING_FEATURES = Set.of(
            "home_prev_beta", "away_prev_beta", "home_prev_theta", "away_prev_theta",
            "home_prev_available", "away_prev_available");

    /** Feature names needing the Massey-residual form computation (extra queries). */
    private static final Set<String> RESIDUAL_FORM_FEATURES = Set.of(
            "home_massey_resid_l5", "away_massey_resid_l5");

    /** Feature names needing ADJ_OFF/ADJ_DEF efficiency snapshots (extra queries). */
    private static final Set<String> ADJ_EFF_FEATURES = Set.of(
            "home_adj_off", "away_adj_off", "home_adj_def", "away_adj_def",
            "adj_eff_matchup_home", "adj_eff_matchup_away", "adj_eff_diff", "adj_eff_total");

    private MlFeatureRegistry() {}

    public static boolean supports(String featureName) {
        return SUPPLIERS.containsKey(featureName);
    }

    public static Set<String> names() {
        return SUPPLIERS.keySet();
    }

    /** True when any of the given features needs box-score/season-snapshot context data. */
    public static boolean needsExtendedStats(List<String> featureNames) {
        return featureNames.stream().anyMatch(EXTENDED_STAT_FEATURES::contains);
    }

    /** True when any of the given features needs previous-season final ratings. */
    public static boolean needsPriorRatings(List<String> featureNames) {
        return featureNames.stream().anyMatch(PRIOR_RATING_FEATURES::contains);
    }

    /** True when any of the given features needs the Massey-residual form computation. */
    public static boolean needsResidualForm(List<String> featureNames) {
        return featureNames.stream().anyMatch(RESIDUAL_FORM_FEATURES::contains);
    }

    /** True when any of the given features needs adjusted-efficiency snapshots. */
    public static boolean needsAdjEfficiency(List<String> featureNames) {
        return featureNames.stream().anyMatch(ADJ_EFF_FEATURES::contains);
    }

    /**
     * Assembles the input vector for the given ordered feature names.
     * Returns {@code null} when any feature value is unavailable.
     */
    public static float[] buildVector(List<String> featureNames, PredictionContext c) {
        float[] vector = new float[featureNames.size()];
        for (int i = 0; i < featureNames.size(); i++) {
            Function<PredictionContext, Double> supplier = SUPPLIERS.get(featureNames.get(i));
            if (supplier == null) return null;
            Double value = supplier.apply(c);
            if (value == null) return null;
            vector[i] = value.floatValue();
        }
        return vector;
    }

    private static Map<String, Function<PredictionContext, Double>> build() {
        Map<String, Function<PredictionContext, Double>> m = new LinkedHashMap<>();

        // ── Rating features (always available when the ML path is taken) ──────
        m.put("massey_beta_home",       c -> c.masseyBetaHome());
        m.put("massey_beta_away",       c -> c.masseyBetaAway());
        m.put("massey_beta_diff",       c -> c.masseyBetaHome() - c.masseyBetaAway());
        m.put("massey_gamma_home",      c -> c.masseyGammaHome());
        m.put("massey_gamma_away",      c -> c.masseyGammaAway());
        m.put("massey_gamma_sum",       c -> c.masseyGammaHome() + c.masseyGammaAway());
        m.put("bt_theta_home",          c -> c.btThetaHome());
        m.put("bt_theta_away",          c -> c.btThetaAway());
        m.put("bt_logodds",             c -> c.btThetaHome() - c.btThetaAway() + c.btAlpha());
        m.put("bt_theta_weighted_home", c -> c.btThetaWeightedHome());
        m.put("bt_theta_weighted_away", c -> c.btThetaWeightedAway());
        m.put("bt_logodds_weighted",    c -> c.btThetaWeightedHome() - c.btThetaWeightedAway() + c.btWeightedAlpha());

        // ── Rolling form (null → model must not predict) ──────────────────────
        m.put("home_win_pct_l5",        PredictionContext::homeWinPctL5);
        m.put("home_avg_margin_l5",     PredictionContext::homeAvgMarginL5);
        m.put("home_avg_total_l5",      PredictionContext::homeAvgTotalL5);
        m.put("home_margin_stddev_l5",  PredictionContext::homeMarginStddevL5);
        m.put("away_win_pct_l5",        PredictionContext::awayWinPctL5);
        m.put("away_avg_margin_l5",     PredictionContext::awayAvgMarginL5);
        m.put("away_avg_total_l5",      PredictionContext::awayAvgTotalL5);
        m.put("away_margin_stddev_l5",  PredictionContext::awayMarginStddevL5);

        // ── Season-to-date context (days-rest imputed to −1 like the trainer) ─
        m.put("home_games_played",      c -> (double) c.homeGamesPlayed());
        m.put("away_games_played",      c -> (double) c.awayGamesPlayed());
        m.put("home_days_rest",         c -> c.homeDaysRest() == null ? -1.0 : c.homeDaysRest().doubleValue());
        m.put("away_days_rest",         c -> c.awayDaysRest() == null ? -1.0 : c.awayDaysRest().doubleValue());
        m.put("season_week",            c -> (double) c.seasonWeek());

        // ── Game context ──────────────────────────────────────────────────────
        m.put("is_neutral_site",        c -> c.isNeutralSite() ? 1.0 : 0.0);
        m.put("is_conference_game",     c -> c.isConferenceGame() ? 1.0 : 0.0);

        // ── Box-score derived (team_stat_snapshots; null when missing) ────────
        m.put("home_pace",              c -> c.homeBoxStats().get("pace"));
        m.put("away_pace",              c -> c.awayBoxStats().get("pace"));
        m.put("home_off_eff",           c -> c.homeBoxStats().get("off_efficiency"));
        m.put("away_off_eff",           c -> c.awayBoxStats().get("off_efficiency"));
        m.put("home_def_eff",           c -> c.homeBoxStats().get("def_efficiency"));
        m.put("away_def_eff",           c -> c.awayBoxStats().get("def_efficiency"));
        m.put("home_efg_pct",           c -> c.homeBoxStats().get("efg_pct"));
        m.put("away_efg_pct",           c -> c.awayBoxStats().get("efg_pct"));
        m.put("home_opp_efg_pct",       c -> c.homeBoxStats().get("opp_efg_pct"));
        m.put("away_opp_efg_pct",       c -> c.awayBoxStats().get("opp_efg_pct"));
        m.put("home_tov_rate",          c -> c.homeBoxStats().get("tov_rate"));
        m.put("away_tov_rate",          c -> c.awayBoxStats().get("tov_rate"));

        // ── RPI (team_season_stat_snapshots; null when missing) ───────────────
        m.put("home_rpi",               PredictionContext::homeRpi);
        m.put("away_rpi",               PredictionContext::awayRpi);

        // ── prior-v3: preseason priors — the ONLY imputed features (0.0 with an
        //    availability flag; the service sets beta+theta both-or-neither) ────
        m.put("home_prev_beta",         c -> c.homePrevBeta() == null ? 0.0 : c.homePrevBeta());
        m.put("away_prev_beta",         c -> c.awayPrevBeta() == null ? 0.0 : c.awayPrevBeta());
        m.put("home_prev_theta",        c -> c.homePrevTheta() == null ? 0.0 : c.homePrevTheta());
        m.put("away_prev_theta",        c -> c.awayPrevTheta() == null ? 0.0 : c.awayPrevTheta());
        m.put("home_prev_available",    c -> c.homePrevBeta() == null ? 0.0 : 1.0);
        m.put("away_prev_available",    c -> c.awayPrevBeta() == null ? 0.0 : 1.0);

        // ── prior-v3: remaining four factors + shot profile (box stats) ───────
        m.put("home_orb_pct",           c -> c.homeBoxStats().get("orb_pct"));
        m.put("away_orb_pct",           c -> c.awayBoxStats().get("orb_pct"));
        m.put("home_drb_pct",           c -> c.homeBoxStats().get("drb_pct"));
        m.put("away_drb_pct",           c -> c.awayBoxStats().get("drb_pct"));
        m.put("home_ft_rate",           c -> c.homeBoxStats().get("ft_rate"));
        m.put("away_ft_rate",           c -> c.awayBoxStats().get("ft_rate"));
        m.put("home_opp_ft_rate",       c -> c.homeBoxStats().get("opp_ft_rate"));
        m.put("away_opp_ft_rate",       c -> c.awayBoxStats().get("opp_ft_rate"));
        m.put("home_opp_tov_rate",      c -> c.homeBoxStats().get("opp_tov_rate"));
        m.put("away_opp_tov_rate",      c -> c.awayBoxStats().get("opp_tov_rate"));
        m.put("home_fg3_rate",          c -> c.homeBoxStats().get("fg3_rate"));
        m.put("away_fg3_rate",          c -> c.awayBoxStats().get("fg3_rate"));

        // ── prior-v3: season-snapshot consistency + SOS ───────────────────────
        m.put("home_stddev_margin",     PredictionContext::homeStddevMargin);
        m.put("away_stddev_margin",     PredictionContext::awayStddevMargin);
        m.put("home_rpi_owp",           PredictionContext::homeRpiOwp);
        m.put("away_rpi_owp",           PredictionContext::awayRpiOwp);

        // ── prior-v3: rolling-10 form ─────────────────────────────────────────
        m.put("home_win_pct_l10",       PredictionContext::homeWinPctL10);
        m.put("away_win_pct_l10",       PredictionContext::awayWinPctL10);
        m.put("home_avg_margin_l10",    PredictionContext::homeAvgMarginL10);
        m.put("away_avg_margin_l10",    PredictionContext::awayAvgMarginL10);

        // ── prior-v3: hot/cold vs rating ──────────────────────────────────────
        m.put("home_massey_resid_l5",   PredictionContext::homeMasseyResidL5);
        m.put("away_massey_resid_l5",   PredictionContext::awayMasseyResidL5);

        // ── eff-v4: adjusted per-possession efficiency ratings + matchups ─────
        // (derived features mirror the trainer's adj_efficiency_context: matchup =
        // own offense vs the OPPOSING defense; null-propagating)
        m.put("home_adj_off",           PredictionContext::homeAdjOff);
        m.put("away_adj_off",           PredictionContext::awayAdjOff);
        m.put("home_adj_def",           PredictionContext::homeAdjDef);
        m.put("away_adj_def",           PredictionContext::awayAdjDef);
        m.put("adj_eff_matchup_home",   c -> matchupHome(c));
        m.put("adj_eff_matchup_away",   c -> matchupAway(c));
        m.put("adj_eff_diff",           c -> combine(c, false));
        m.put("adj_eff_total",          c -> combine(c, true));

        return m;
    }

    private static Double matchupHome(PredictionContext c) {
        if (c.homeAdjOff() == null || c.awayAdjDef() == null) return null;
        return c.homeAdjOff() - c.awayAdjDef();
    }

    private static Double matchupAway(PredictionContext c) {
        if (c.awayAdjOff() == null || c.homeAdjDef() == null) return null;
        return c.awayAdjOff() - c.homeAdjDef();
    }

    private static Double combine(PredictionContext c, boolean sum) {
        Double home = matchupHome(c);
        Double away = matchupAway(c);
        if (home == null || away == null) return null;
        return sum ? home + away : home - away;
    }
}
