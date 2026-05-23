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
        PostseasonCounts postseason,
        LocalDateTime lastScrapeAt,
        String lastScrapeType,
        String lastScrapeStatus
) {
    /** True once any pipeline run has touched this season. */
    public boolean hasAnyData() {
        return lastScrapeAt != null || totalGames > 0;
    }

    /**
     * Counts of games per tournament category. ncaaExpected = 67 (incl. First Four);
     * ncaaAnomaly flags any final NCAA count that diverges once the season is settled.
     */
    public record PostseasonCounts(
            long confTournament,
            long ncaaTournament,
            long nit,
            long cbi,
            long crown,
            long otherPostseason,
            long inSeasonTournament
    ) {
        public static final long NCAA_EXPECTED = 67;

        public boolean hasAny() {
            return confTournament + ncaaTournament + nit + cbi + crown + otherPostseason > 0;
        }

        public boolean ncaaAnomaly() {
            return ncaaTournament > 0 && ncaaTournament != NCAA_EXPECTED;
        }
    }
}
