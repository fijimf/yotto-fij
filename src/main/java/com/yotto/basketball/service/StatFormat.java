package com.yotto.basketball.service;

import java.util.Locale;

/**
 * Renders a raw stat value for humans per its {@link StatCatalog.Format}. Mirrors
 * {@code formatValue} in stat-page.js so the server-rendered table and the
 * client-drawn charts agree. Exposed to Thymeleaf as a model attribute.
 */
public class StatFormat {

    /** Format a value given the stat's format name (the {@code StatCatalog.Format} enum name). */
    public String value(Double v, String format) {
        if (v == null || format == null) return "—";
        return switch (format) {
            case "PERCENT", "RATE" -> String.format(Locale.US, "%.1f%%", v * 100);
            case "RATING", "PER_GAME" -> String.format(Locale.US, "%.1f", v);
            case "RATIO" -> String.format(Locale.US, "%.2f", v);
            default -> String.format(Locale.US, "%.2f", v);
        };
    }

    /** Signed z-score to two decimals, or an em dash. */
    public String zscore(Double z) {
        return z == null ? "—" : String.format(Locale.US, "%+.2f", z);
    }

    /** Percentile to the nearest integer, or an em dash. */
    public String percentile(Double p) {
        return p == null ? "—" : String.format(Locale.US, "%.0f", p);
    }
}
