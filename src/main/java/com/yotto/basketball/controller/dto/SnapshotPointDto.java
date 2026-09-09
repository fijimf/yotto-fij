package com.yotto.basketball.controller.dto;

/**
 * One day of a team's season-form snapshot, for the game chart's season
 * scrubber (ellipse per date). Dates are strictly before the game date.
 */
public record SnapshotPointDto(
        String date,          // ISO date
        int gamesPlayed,
        Double meanFor,
        Double sdFor,
        Double meanAgainst,
        Double sdAgainst,
        Double corr
) {}
