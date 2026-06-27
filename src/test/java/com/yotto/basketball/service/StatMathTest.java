package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatMathTest {

    @Test
    void histogramEdgesAndCountsAreConsistent() {
        double[] values = {1, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 8, 9, 10};
        StatMath.Histogram h = StatMath.histogram(values);

        assertEquals(h.binCounts().length + 1, h.binEdges().length);
        int total = 0;
        for (int c : h.binCounts()) total += c;
        assertEquals(values.length, total, "every value lands in exactly one bin");
        assertEquals(values.length >= 8 ? 8 : h.binCounts().length, Math.max(8, h.binCounts().length));
    }

    @Test
    void histogramHandlesNoSpread() {
        StatMath.Histogram h = StatMath.histogram(new double[]{5, 5, 5});
        int total = 0;
        for (int c : h.binCounts()) total += c;
        assertEquals(3, total);
    }

    @Test
    void kdeIsEmptyBelowTwoPoints() {
        assertEquals(0, StatMath.kde(new double[]{1}, 50).x().length);
    }

    @Test
    void kdeProducesPositiveSampledDensity() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8};
        StatMath.Kde kde = StatMath.kde(values, 64);
        assertEquals(64, kde.x().length);
        assertEquals(64, kde.y().length);
        for (double y : kde.y()) assertTrue(y >= 0, "density is never negative");
    }

    @Test
    void aucIsOneForPerfectSeparation() {
        double[] d = {-2, -1, 1, 2};
        boolean[] win = {false, false, true, true};
        assertEquals(1.0, StatMath.auc(d, win), 1e-9);
    }

    @Test
    void aucIsZeroForPerfectlyWrongOrdering() {
        double[] d = {-2, -1, 1, 2};
        boolean[] win = {true, true, false, false};
        assertEquals(0.0, StatMath.auc(d, win), 1e-9);
    }

    @Test
    void aucIsHalfOnTies() {
        double[] d = {1, 1, 1, 1};
        boolean[] win = {true, false, true, false};
        assertEquals(0.5, StatMath.auc(d, win), 1e-9);
    }

    @Test
    void aucIsNaNWhenAClassIsEmpty() {
        assertTrue(Double.isNaN(StatMath.auc(new double[]{1, 2}, new boolean[]{true, true})));
    }

    @Test
    void naiveAccuracyCountsBetterValueWinsAndExcludesTies() {
        // d>0 predicts home win. Game 3 is a tie (d==0) and is excluded.
        double[] d = {1, -1, 0, 2};
        boolean[] win = {true, false, true, false};
        // game1 correct, game2 correct, game3 excluded, game4 wrong -> 2/3
        assertEquals(2.0 / 3.0, StatMath.naiveAccuracy(d, win), 1e-9);
    }
}
