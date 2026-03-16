package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game.GameStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable prediction result for a single game. All model sub-blocks are nullable:
 * a null sub-block means no valid pre-game snapshot existed for one or both teams,
 * or the game status is POSTPONED/CANCELLED.
 *
 * <p>When {@code gameStatus == FINAL} and scores are available, the {@code actual*}
 * fields are populated so callers can compute prediction error without a second request.
 */
public record PredictionResult(
        Long gameId,
        LocalDate gameDate,
        GameStatus gameStatus,
        Boolean neutralSite,
        TeamSummary homeTeam,
        TeamSummary awayTeam,

        // Populated only for FINAL games with recorded scores
        Integer actualHomeScore,
        Integer actualAwayScore,
        Integer actualMargin,   // homeScore − awayScore
        Integer actualTotal,    // homeScore + awayScore

        MasseyPrediction massey,
        MasseyTotalPrediction masseyTotal,
        BradleyTerryPrediction bradleyTerry,
        BradleyTerryPrediction bradleyTerryWeighted,
        MlPrediction ml,

        // Book lines from BettingOdds (null if no odds recorded)
        BigDecimal bookSpread,      // home team perspective (positive = home favored)
        BigDecimal bookOverUnder
) {

    public record TeamSummary(Long id, String name, String abbreviation, String logoUrl, String color) {}

    /**
     * Massey spread prediction: β_h − β_a + α (negative = away team favored).
     * HCA term is zero for neutral-site games.
     */
    public record MasseyPrediction(
            double spread,
            int homeGamesPlayed,
            int awayGamesPlayed,
            LocalDate modelDate   // earlier of the two teams' snapshot dates
    ) {}

    /**
     * Massey total prediction: β_h + β_a + γ₀ + δ.
     * Intercept γ₀ is always added; HCA bump δ is zero for neutral-site games.
     */
    public record MasseyTotalPrediction(
            double total,
            int homeGamesPlayed,
            int awayGamesPlayed,
            LocalDate modelDate
    ) {}

    /**
     * Bradley-Terry win probability prediction.
     * {@code homeWinProbability = σ(θ_h − θ_a + α)}, {@code awayWinProbability = 1 − home}.
     * Moneylines are no-vig fair American odds derived from the probabilities.
     */
    public record BradleyTerryPrediction(
            double homeWinProbability,
            double awayWinProbability,
            int homeImpliedMoneyline,
            int awayImpliedMoneyline,
            int homeGamesPlayed,
            int awayGamesPlayed,
            LocalDate modelDate
    ) {}

    /**
     * ML (gradient-boosted) enhancement predictions. Present only when ML is enabled,
     * all three Phase 1 model snapshots are available, and both teams have ≥ 5 games played
     * (rolling feature window is complete).
     */
    public record MlPrediction(
            double spread,
            double total,
            double homeWinProbability,
            double awayWinProbability,
            int homeImpliedMoneyline,
            int awayImpliedMoneyline,
            String modelVersion,
            boolean featuresComplete   // false if any rolling feature was imputed
    ) {}
}
