package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inputs are HOME-MARGIN oriented by contract: the caller (HomePageService) negates
 * {@code betting_odds.spread} (handicap, negative = home favored) before calling. A home team
 * favored by 3.5 at the book arrives here as {@code bookHomeMargin = +3.5}.
 */
class HomeInterestScoreTest {

    // ── upcoming ──

    @Test
    void upcoming_coinFlipBeatsBlowout() {
        double coinFlip = HomeInterestScore.upcoming(1.0, 1.0, 0.52);
        double blowout = HomeInterestScore.upcoming(22.0, 21.0, 0.97);
        assertThat(coinFlip).isGreaterThan(blowout);
    }

    @Test
    void upcoming_modelBookDisagreementRaisesInterest() {
        double agrees = HomeInterestScore.upcoming(5.0, 5.0, 0.68);
        double disagreesBySix = HomeInterestScore.upcoming(11.0, 5.0, 0.68);
        assertThat(disagreesBySix).isGreaterThan(agrees);
    }

    @Test
    void upcoming_sideFlipOutranksSameSizedDisagreementOnOneSide() {
        // Both disagree with the book by 4 points, but one crosses zero: the model picks the
        // OTHER team. That's the more interesting game.
        double sameSide = HomeInterestScore.upcoming(9.0, 5.0, null);
        double sideFlip = HomeInterestScore.upcoming(-2.0, 2.0, null);
        assertThat(sideFlip).isGreaterThan(sameSide);
    }

    @Test
    void upcoming_homeFavoredAtBook_smallDisagreementWhenNormalizedCorrectly() {
        // Model: home by 4.5. Book: home favored by 3.5 → raw spread −3.5, normalized +3.5.
        // Correct normalization → tiny disagreement. Forgetting to negate (passing −3.5)
        // would fake an 8-point disagreement — the exact bug that shipped twice.
        double normalized = HomeInterestScore.upcoming(4.5, 3.5, null);
        double signBotched = HomeInterestScore.upcoming(4.5, -3.5, null);
        assertThat(normalized).isLessThan(signBotched);
        assertThat(normalized).isEqualTo(2.0 * 1.0); // |4.5 − 3.5| = 1, no side-flip bonus
    }

    @Test
    void upcoming_nullsScoreZero_neverThrow() {
        assertThat(HomeInterestScore.upcoming(null, null, null)).isZero();
    }

    // ── results ──

    @Test
    void result_bigUpsetOutranksChalkBlowout() {
        // 90% favorite loses by 2 vs. favorite wins by 25
        double upset = HomeInterestScore.result(0.9, -2, null, null);
        double chalk = HomeInterestScore.result(0.9, 25, null, null);
        assertThat(upset).isGreaterThan(chalk);
    }

    @Test
    void result_closeGameBeatsBlowout() {
        double onePoint = HomeInterestScore.result(null, 1, null, null);
        double thirtyPoints = HomeInterestScore.result(null, 30, null, null);
        assertThat(onePoint).isGreaterThan(thirtyPoints);
    }

    @Test
    void result_heavierFallenFavoriteScoresHigher() {
        double heavyFavoriteLost = HomeInterestScore.result(0.95, -3, null, null);
        double slightFavoriteLost = HomeInterestScore.result(0.55, -3, null, null);
        assertThat(heavyFavoriteLost).isGreaterThan(slightFavoriteLost);
    }

    @Test
    void result_nullsScoreZero_neverThrow() {
        assertThat(HomeInterestScore.result(null, null, null, null)).isZero();
    }
}
