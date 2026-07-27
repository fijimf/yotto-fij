package com.yotto.basketball.service;

import java.util.Map;

/**
 * All pre-game ingredients needed to assemble ML feature vectors for one game.
 * Feature values are derived from this by {@link MlFeatureRegistry} suppliers, so a
 * model bundle's manifest (its ordered feature-name list) fully determines its input
 * vector — different bundles can use different feature subsets over one context.
 *
 * <p>Nullable fields mean "not available before this game" (cold start / missing
 * snapshot); whether that aborts a prediction is decided per feature by its supplier.
 * {@code homeBoxStats}/{@code awayBoxStats} are the latest pre-game
 * {@code team_stat_snapshots} values keyed by stat name (empty when not fetched or not
 * available); {@code homeRpi}/{@code awayRpi}, {@code *StddevMargin}/{@code *RpiOwp}
 * come from {@code team_season_stat_snapshots}.
 *
 * <p>Prior-v3 fields: {@code *PrevBeta}/{@code *PrevTheta} are the previous season's
 * FINAL Massey/BT ratings, set both-or-neither per side (their suppliers 0-impute with
 * an availability flag); {@code *MasseyResidL5} is the hot/cold-vs-rating form feature;
 * {@code *L10} fields are the 10-game rolling window.
 */
public record PredictionContext(
        double masseyBetaHome, double masseyBetaAway,
        double masseyGammaHome, double masseyGammaAway,
        double btThetaHome, double btThetaAway, double btAlpha,
        double btThetaWeightedHome, double btThetaWeightedAway, double btWeightedAlpha,

        Double homeWinPctL5, Double homeAvgMarginL5, Double homeAvgTotalL5, Double homeMarginStddevL5,
        Double awayWinPctL5, Double awayAvgMarginL5, Double awayAvgTotalL5, Double awayMarginStddevL5,

        int homeGamesPlayed, int awayGamesPlayed,
        Integer homeDaysRest, Integer awayDaysRest, int seasonWeek,
        boolean isNeutralSite, boolean isConferenceGame,

        Map<String, Double> homeBoxStats, Map<String, Double> awayBoxStats,
        Double homeRpi, Double awayRpi,

        Double homeStddevMargin, Double awayStddevMargin,
        Double homeRpiOwp, Double awayRpiOwp,
        Double homeWinPctL10, Double awayWinPctL10,
        Double homeAvgMarginL10, Double awayAvgMarginL10,
        Double homePrevBeta, Double awayPrevBeta,
        Double homePrevTheta, Double awayPrevTheta,
        Double homeMasseyResidL5, Double awayMasseyResidL5,

        Double homeAdjOff, Double awayAdjOff,
        Double homeAdjDef, Double awayAdjDef,

        /** Massey HCA in points (0 for neutral sites) — residual-target reconstruction. */
        double masseyHca
) {

    /** The classical Massey margin prediction — the baseline residual-target bundles add back. */
    public double masseyPredictedMargin() {
        return masseyBetaHome - masseyBetaAway + masseyHca;
    }
}
