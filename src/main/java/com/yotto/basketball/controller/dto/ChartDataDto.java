package com.yotto.basketball.controller.dto;

import java.util.List;

public record ChartDataDto(
        // Teams
        String homeAbbr,
        String awayAbbr,
        String homeColor,
        String awayColor,
        String homeLogoUrl,
        String awayLogoUrl,
        String homeFullName,
        String awayFullName,

        boolean neutralSite,
        String gameDate,        // ISO date of this game (end of the season scrubber)

        // Actual result (null if not FINAL)
        Integer actualHomeScore,
        Integer actualAwayScore,

        // Betting lines (handicap orientation: negative spread = home favored)
        Double spread,
        Double overUnder,
        Double openingSpread,
        Double openingOverUnder,

        // Residual scale used for the outcome-density blob and cover/over odds
        double marginSigma,
        double totalSigma,

        // Season averages (from SeasonStatistics calc fields)
        Double homeAvgFor,
        Double homeAvgAgainst,
        Double awayAvgFor,
        Double awayAvgAgainst,

        // IQR for home team: [ptsForQ1, ptsForQ3, ptsAgainstQ1, ptsAgainstQ3]
        Integer homeForQ1,
        Integer homeForQ3,
        Integer homeAgainstQ1,
        Integer homeAgainstQ3,

        // IQR for away team
        Integer awayForQ1,
        Integer awayForQ3,
        Integer awayAgainstQ1,
        Integer awayAgainstQ3,

        // Stat snapshot data (from TeamSeasonStatSnapshot)
        Double homeMeanFor,
        Double homeSdFor,
        Double homeMeanAgainst,
        Double homeSdAgainst,
        Double homeCorr,

        Double awayMeanFor,
        Double awaySdFor,
        Double awayMeanAgainst,
        Double awaySdAgainst,
        Double awayCorr,

        // Season game markers for chart
        List<SeasonGameMarkerDto> homeGames,
        List<SeasonGameMarkerDto> awayGames,

        // Pre-game model predictions with both a spread and a total
        List<ChartModelPointDto> models,

        // Prior meetings between the two teams (any season), most recent first
        List<PastMeetingDto> pastMeetings,

        // Daily season-form snapshots before the game (season scrubber)
        List<SnapshotPointDto> homeSeries,
        List<SnapshotPointDto> awaySeries
) {}
