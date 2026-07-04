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
                       String mechanics,     // longer how-it's-computed note; null = none
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
                          int gamesPlotted,   // N — games where both teams had an entering value
                          Predictive predictive) {}

    /**
     * x/y are each team's season-to-date value entering the game (pregame snapshot);
     * homeAbbr/awayAbbr label the tooltip (abbreviation, falling back to team name).
     */
    public record Point(double x, double y, boolean homeWin, String homeAbbr, String awayAbbr) {}

    /** AUC of the (direction-aware) home entering-stat advantage predicting a home win. */
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
