package com.yotto.basketball.controller.dto;

/**
 * A prior meeting between the two teams, oriented to THIS game's home/away
 * assignment so it can be plotted directly in the chart's score space.
 */
public record PastMeetingDto(
        long gameId,
        String date,          // ISO date
        int homeTeamScore,    // score of this game's home team in that meeting
        int awayTeamScore,    // score of this game's away team in that meeting
        String venueAbbr,     // abbreviation of the team that hosted, or null for neutral
        boolean neutral
) {}
