package com.yotto.basketball.service;

import java.util.Arrays;

/**
 * Pure numeric helpers for the per-statistic pages: histogram binning, a Gaussian
 * kernel-density estimate, and two single-game predictiveness measures (AUC and the
 * naive "better value wins" accuracy). No Spring, no I/O — unit-tested directly.
 */
public final class StatMath {

    private StatMath() {}

    /** Histogram geometry ready for the client to draw: bin edges (length n+1) and counts (length n). */
    public record Histogram(double[] binEdges, int[] binCounts) {}

    /** Sampled density curve: paired x positions and density values. */
    public record Kde(double[] x, double[] y) {}

    /**
     * Freedman–Diaconis bins over [min, max], clamped to [8, 30] bins. Falls back to
     * a single degenerate bin when there is no spread (all values equal) or too few
     * points to bin meaningfully.
     */
    public static Histogram histogram(double[] values) {
        if (values.length == 0) {
            return new Histogram(new double[]{0, 1}, new int[]{0});
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        if (max <= min) {
            return new Histogram(new double[]{min, min + 1}, new int[]{values.length});
        }

        double iqr = percentile(sorted, 75) - percentile(sorted, 25);
        int binCount;
        if (iqr <= 0) {
            binCount = (int) Math.ceil(Math.sqrt(values.length)); // Sturges-ish fallback
        } else {
            double width = 2 * iqr / Math.cbrt(values.length);
            binCount = (int) Math.ceil((max - min) / width);
        }
        binCount = Math.max(8, Math.min(30, binCount));

        double[] edges = new double[binCount + 1];
        double step = (max - min) / binCount;
        for (int i = 0; i <= binCount; i++) {
            edges[i] = min + i * step;
        }
        int[] counts = new int[binCount];
        for (double v : values) {
            int idx = (int) ((v - min) / step);
            if (idx >= binCount) idx = binCount - 1; // max value lands in the last bin
            if (idx < 0) idx = 0;
            counts[idx]++;
        }
        return new Histogram(edges, counts);
    }

    /**
     * Gaussian KDE sampled at {@code samples} evenly spaced points spanning the data
     * range (padded by 3 bandwidths so the tails resolve). Bandwidth by Silverman's
     * rule of thumb. Returns an empty curve for fewer than two points.
     */
    public static Kde kde(double[] values, int samples) {
        if (values.length < 2 || samples < 2) {
            return new Kde(new double[0], new double[0]);
        }
        double mean = mean(values);
        double sd = Math.sqrt(variance(values, mean));
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double iqr = percentile(sorted, 75) - percentile(sorted, 25);
        double spread = iqr > 0 ? Math.min(sd, iqr / 1.34) : sd;
        if (spread <= 0) spread = sd > 0 ? sd : 1.0;
        double bandwidth = 1.06 * spread * Math.pow(values.length, -0.2);
        if (bandwidth <= 0) bandwidth = 1.0;

        double lo = sorted[0] - 3 * bandwidth;
        double hi = sorted[sorted.length - 1] + 3 * bandwidth;
        double step = (hi - lo) / (samples - 1);
        double norm = 1.0 / (values.length * bandwidth * Math.sqrt(2 * Math.PI));

        double[] xs = new double[samples];
        double[] ys = new double[samples];
        for (int i = 0; i < samples; i++) {
            double x = lo + i * step;
            double sum = 0;
            for (double v : values) {
                double u = (x - v) / bandwidth;
                sum += Math.exp(-0.5 * u * u);
            }
            xs[i] = x;
            ys[i] = norm * sum;
        }
        return new Kde(xs, ys);
    }

    /**
     * Area under the ROC curve for using score {@code d} to rank positive outcomes —
     * here, the home stat advantage predicting a home win. Equivalent to the
     * Mann–Whitney U statistic normalised by the pair count; ties contribute 0.5.
     * Returns NaN when either class is empty.
     */
    public static double auc(double[] d, boolean[] homeWin) {
        if (d.length != homeWin.length) {
            throw new IllegalArgumentException("d and homeWin must be the same length");
        }
        int n = d.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(d[a], d[b]));

        // Average ranks (1-based), tie-aware.
        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && d[order[j + 1]] == d[order[i]]) j++;
            double avg = (i + j) / 2.0 + 1; // average of 1-based ranks i+1..j+1
            for (int k = i; k <= j; k++) rank[order[k]] = avg;
            i = j + 1;
        }

        double sumRankPos = 0;
        long pos = 0;
        for (int k = 0; k < n; k++) {
            if (homeWin[k]) {
                sumRankPos += rank[k];
                pos++;
            }
        }
        long neg = n - pos;
        if (pos == 0 || neg == 0) return Double.NaN;
        return (sumRankPos - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    /**
     * Fraction of games in which the team with the larger stat advantage won, with
     * ties (d == 0) excluded. Returns NaN when no non-tied games exist.
     */
    public static double naiveAccuracy(double[] d, boolean[] homeWin) {
        if (d.length != homeWin.length) {
            throw new IllegalArgumentException("d and homeWin must be the same length");
        }
        int correct = 0;
        int total = 0;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == 0) continue;
            total++;
            boolean predictsHome = d[i] > 0;
            if (predictsHome == homeWin[i]) correct++;
        }
        return total == 0 ? Double.NaN : (double) correct / total;
    }

    // ── small stats helpers ──────────────────────────────────────────────────────

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    private static double variance(double[] v, double mean) {
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return v.length > 1 ? s / (v.length - 1) : 0;
    }

    /** Linear-interpolated percentile (0–100) over an already-sorted array. */
    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double rank = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }
}
