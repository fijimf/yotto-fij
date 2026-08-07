package com.yotto.basketball.service;

/**
 * Ranks games for the front page: the top-N most interesting games, not the first N.
 *
 * <p>ALL margin inputs are home-margin oriented. {@code betting_odds.spread} is handicap
 * orientation (negative = home favored) — callers must negate it before passing it here, so the
 * conversion lives in exactly one caller and this class never sees a raw book spread.
 */
public final class HomeInterestScore {

    private HomeInterestScore() {}

    /**
     * Interest of an upcoming game: model-vs-book disagreement (capped), a bonus when the model
     * picks the other side outright, and closeness to a coin flip.
     */
    public static double upcoming(Double modelSpread, Double bookHomeMargin, Double homeWinProb) {
        double s = 0;
        if (modelSpread != null && bookHomeMargin != null) {
            s += 2.0 * Math.min(Math.abs(modelSpread - bookHomeMargin), 10.0);
            if (Math.signum(modelSpread) != Math.signum(bookHomeMargin)) {
                s += 3.0;
            }
        }
        if (homeWinProb != null) {
            s += 4.0 * (1.0 - Math.abs(homeWinProb - 0.5) * 2.0);
        }
        return s;
    }

    /**
     * Interest of a finished game: close margins, upsets weighted by how heavy the fallen favorite
     * was, and how far the result strayed from the book's number.
     */
    public static double result(Double homeWinProb, Integer actualMargin,
                                Double modelSpread, Double bookHomeMargin) {
        double s = 0;
        if (actualMargin != null) {
            s += 3.0 * (1.0 - Math.min(Math.abs(actualMargin), 20) / 20.0);
        }
        if (homeWinProb != null && actualMargin != null && actualMargin != 0) {
            boolean favoriteLost = (homeWinProb >= 0.5) != (actualMargin > 0);
            if (favoriteLost) {
                s += 6.0 * Math.abs(homeWinProb - 0.5) * 2.0;
            }
        }
        if (modelSpread != null && bookHomeMargin != null) {
            s += Math.min(Math.abs(modelSpread - bookHomeMargin), 10.0);
        }
        return s;
    }
}
