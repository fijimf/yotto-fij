package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game.GameStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Flat, template-facing view of one game's prediction under a single chosen model. Produced from a
 * {@link PredictionResult} by {@link #from} which resolves the selected model's spread / total /
 * win-probability into scalar fields, so the card template stays free of model-selection logic.
 *
 * <p>For FINAL games the {@code homeScore}/{@code awayScore} are populated and the correctness
 * helpers ({@link #winnerCorrect}, {@link #spreadCorrect}, {@link #totalCorrect}) return a verdict;
 * they return {@code null} when the game is not final or the relevant prediction is unavailable.
 */
public record PredictionCardView(
        Long gameId,
        LocalDate date,
        GameStatus status,
        PredictionResult.TeamSummary homeTeam,
        PredictionResult.TeamSummary awayTeam,

        Integer homeScore,          // actual, null unless FINAL with recorded scores
        Integer awayScore,

        Double predSpread,          // home-margin perspective (positive = home favored)
        Double predTotal,
        Double predHomeWinProb,     // 0..1
        boolean predFromMl,

        BigDecimal bookSpread,      // handicap (negative = home favored)
        BigDecimal bookOverUnder
) {

    public static final String CLASSICAL_KEY = "classical";
    public static final String ML_PREFIX     = "ml:";

    /** Resolves {@code modelKey} against the game's predictions into a flat card view. */
    public static PredictionCardView from(PredictionResult p, String modelKey) {
        Double spread = null, total = null, winProb = null;
        boolean fromMl = false;

        if (modelKey != null && modelKey.startsWith(ML_PREFIX)) {
            String slug = modelKey.substring(ML_PREFIX.length());
            PredictionResult.MlPrediction m = p.mlModels() == null ? null : p.mlModels().get(slug);
            if (m != null) {
                spread = m.spread();
                total = m.total();
                winProb = m.homeWinProbability();
                fromMl = true;
            }
        } else {
            if (p.massey() != null)       spread = p.massey().spread();
            if (p.masseyTotal() != null)  total = p.masseyTotal().total();
            if (p.bradleyTerry() != null) winProb = p.bradleyTerry().homeWinProbability();
        }

        return new PredictionCardView(
                p.gameId(), p.gameDate(), p.gameStatus(),
                p.homeTeam(), p.awayTeam(),
                p.actualHomeScore(), p.actualAwayScore(),
                spread, total, winProb, fromMl,
                p.bookSpread(), p.bookOverUnder());
    }

    public boolean isFinal() {
        return status == GameStatus.FINAL && homeScore != null && awayScore != null;
    }

    public Integer actualMargin() { return isFinal() ? homeScore - awayScore : null; }
    public Integer actualTotal()  { return isFinal() ? homeScore + awayScore : null; }
    public Boolean homeWon()      { return isFinal() ? homeScore > awayScore : null; }

    /** True when the model's favorite (by win prob) actually won. */
    public Boolean winnerCorrect() {
        if (!isFinal() || predHomeWinProb == null) return null;
        return (predHomeWinProb >= 0.5) == (homeScore > awayScore);
    }

    /** True when the actual margin landed on the favored side of the model's own predicted spread. */
    public Boolean spreadCorrect() {
        if (!isFinal() || predSpread == null || predSpread == 0.0) return null;
        double diff = actualMargin() - predSpread;
        return Math.signum(diff) == Math.signum(predSpread);
    }

    /** True when the model's over/under lean matched the actual total, relative to the book line. */
    public Boolean totalCorrect() {
        if (!isFinal() || predTotal == null || bookOverUnder == null) return null;
        double ou = bookOverUnder.doubleValue();
        return (predTotal > ou) == (actualTotal() > ou);
    }
}
