package com.yotto.basketball.service;

/**
 * Fixed-width 27-feature input vector for all three ML models.
 *
 * <p>Rating features (12) use {@code double} primitives — they are always available
 * from Phase 1 snapshots when the ML path is taken. Rolling features (8) and
 * rest-day features (2) use boxed {@code Double}/{@code Integer} so that a missing
 * value (e.g., a team with no prior games this season) can be represented as
 * {@code null}. {@link MlPredictionService#predict} returns {@code null} when any
 * nullable field is null.
 *
 * <p>Feature ordering must exactly match {@code features.json} written by the
 * Python training script (27 features, same names).
 */
public record MlFeatureVector(
        // ── Rating features (from Phase 1 snapshots, pre-game) ───────────────
        double masseyBetaHome,         // Massey β for home team
        double masseyBetaAway,         // Massey β for away team
        double masseyBetaDiff,         // β_home − β_away  (Massey spread prediction)
        double masseyGammaHome,        // Massey Totals β for home team
        double masseyGammaAway,        // Massey Totals β for away team
        double masseyGammaSum,         // β_home + β_away  (Massey totals component)
        double btThetaHome,            // BT θ for home team (unweighted)
        double btThetaAway,            // BT θ for away team (unweighted)
        double btLogodds,              // θ_home − θ_away + α  (BT log-odds)
        double btThetaWeightedHome,    // Weighted BT θ_w for home team
        double btThetaWeightedAway,    // Weighted BT θ_w for away team
        double btLogoddsWeighted,      // θ_w_home − θ_w_away + α_w  (weighted BT log-odds)

        // ── Rolling form — home team (last 5 completed games before game date) ─
        Double homeWinPctL5,      // wins / games, last 5
        Double homeAvgMarginL5,   // mean margin from home team's perspective
        Double homeAvgTotalL5,    // mean total score, last 5
        Double homeMarginStddevL5,// std dev of margin, last 5

        // ── Rolling form — away team ──────────────────────────────────────────
        Double awayWinPctL5,
        Double awayAvgMarginL5,
        Double awayAvgTotalL5,
        Double awayMarginStddevL5,

        // ── Season-to-date context ─────────────────────────────────────────────
        int homeGamesPlayed,      // from the Massey snapshot
        int awayGamesPlayed,
        Integer homeDaysRest,     // days since last game; null if first game of season
        Integer awayDaysRest,
        int seasonWeek,           // floor((gameDate − seasonStartDate) / 7) + 1

        // ── Game context ──────────────────────────────────────────────────────
        boolean isNeutralSite,
        boolean isConferenceGame
) {}
