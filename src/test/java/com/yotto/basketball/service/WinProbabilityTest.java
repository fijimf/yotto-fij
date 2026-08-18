package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WinProbabilityTest {

    @Test
    void zeroMarginIsACoinFlip() {
        assertThat(WinProbability.fromMargin(0.0, 11.0)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void oneSigmaFavoriteMatchesNormalCdf() {
        // Φ(1) ≈ 0.8413447
        assertThat(WinProbability.fromMargin(11.0, 11.0)).isCloseTo(0.8413447, within(1e-6));
    }

    @Test
    void sevenPointFavoriteAtSigma11() {
        // Φ(7/11) ≈ 0.7376 — the "a 7-point favorite is roughly 74%" rule of thumb
        assertThat(WinProbability.fromMargin(7.0, 11.0)).isCloseTo(0.7376, within(5e-4));
    }

    @Test
    void symmetricAroundZero() {
        for (double margin : new double[]{0.5, 3.0, 7.0, 11.0, 25.0}) {
            assertThat(WinProbability.fromMargin(-margin, 11.0))
                    .isCloseTo(1.0 - WinProbability.fromMargin(margin, 11.0), within(1e-12));
        }
    }

    @Test
    void strictlyIncreasingInMargin() {
        double prev = 0.0;
        for (double margin = -30.0; margin <= 30.0; margin += 0.5) {
            double p = WinProbability.fromMargin(margin, 11.0);
            assertThat(p).isGreaterThan(prev).isLessThan(1.0);
            prev = p;
        }
    }

    @Test
    void largerSigmaShrinksConfidenceTowardHalf() {
        assertThat(WinProbability.fromMargin(7.0, 14.0))
                .isLessThan(WinProbability.fromMargin(7.0, 11.0))
                .isGreaterThan(0.5);
    }
}
