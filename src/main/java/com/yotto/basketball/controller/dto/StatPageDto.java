package com.yotto.basketball.controller.dto;

import java.util.List;

/**
 * Everything the stat-detail page needs, serialized inline as JSON for the D3
 * charts (the same controller→template→JSON pattern as {@code ChartDataDto}).
 * All numeric work — bins, KDE, AUC — is done server-side; the client only draws.
 */
public record StatPageDto(
        Meta meta,
        SeasonInfo season,
        Population population,
        Histogram histogram,
        Scatter scatter,
        List<RankRow> rankings) {

    public record Meta(String name,
                       String title,
                       String category,
                       String description,   // null = render no blurb
                       String format,        // PERCENT | RATE | RATING | PER_GAME | RATIO
                       boolean higherIsBetter) {}

    public record SeasonInfo(int year,
                             String date,        // resolved snapshot date (ISO) or null
                             String latestDate,  // most recent available (ISO) or null
                             List<Integer> availableSeasons) {}

    /** League-wide population summary straight from SeasonPopulationStat (authoritative). */
    public record Population(Double min, Double max, Double mean, Double stddev, Integer count) {}

    public record Histogram(double[] binEdges, int[] binCounts, Kde kde) {}

    public record Kde(double[] x, double[] y) {}

    public record Scatter(List<Point> points,
                          double axisMin,
                          double axisMax,
                          int gamesTotal,     // M — completed games up to the date
                          int gamesPlotted,   // N — games with a usable box score on both sides
                          Predictive predictive) {}

    public record Point(double x, double y, boolean homeWin) {}

    /** AUC of the (direction-aware) home stat advantage predicting a home win. */
    public record Predictive(Double auc, Double naiveAccuracy, boolean show) {}

    public record RankRow(Integer rank,
                          Long teamId,
                          String teamName,
                          String logoUrl,
                          Double value,
                          Double zscore,
                          Double percentile,
                          int gamesPlayed) {}
}
