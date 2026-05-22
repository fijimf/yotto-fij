package com.yotto.basketball.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-only snapshot of a season's data-completeness state, rendered in the
 * admin dashboard's Season Card. All counts come from cheap aggregate queries
 * — see SeasonHealthService.
 */
public record SeasonHealth(
        int seasonYear,
        LocalDate seasonStart,
        LocalDate seasonEnd,
        long conferenceCount,
        long activeTeamCount,
        int totalDates,
        long scrapedDates,
        long totalGames,
        long finalGames,
        long inProgressGames,
        long staleInProgressGames,
        long gamesWithStats,
        long finalGamesMissingStats,
        long gamesWithOdds,
        long finalGamesMissingOdds,
        long teamsWithStandings,
        long nonD1OpponentGames,
        long teamsWithTieOutMismatch,
        LocalDateTime lastScrapeAt,
        String lastScrapeType,
        String lastScrapeStatus
) {
    /** True once any pipeline run has touched this season. */
    public boolean hasAnyData() {
        return lastScrapeAt != null || totalGames > 0;
    }
}
