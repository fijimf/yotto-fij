package com.yotto.basketball.service;

import org.apache.commons.math3.special.Erf;

/**
 * Normal-margin win probability (Stern 1991): margins of victory are approximately
 * N(μ, σ²), so P(home win) = Φ(μ/σ) for an expected home margin μ. σ comes from
 * {@code app.prediction.margin-sigma} (default 11.0, empirically 10.5–11 for college
 * basketball). Every margin→probability conversion goes through this class.
 */
public final class WinProbability {

    private WinProbability() {}

    /** P(margin &gt; 0) = Φ(margin/σ) for an expected home margin and margin stddev σ. */
    public static double fromMargin(double margin, double sigma) {
        return 0.5 * (1.0 + Erf.erf(margin / (sigma * Math.sqrt(2.0))));
    }
}
