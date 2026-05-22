package com.yotto.basketball.service;

/**
 * Per-team tie-out check between ESPN-reported wins/losses and our own count of
 * D-I games plus tracked non-DI games. A mismatch flags a data-integrity issue:
 * either a game we have isn't classified right, or ESPN's number for that team
 * shifted and we haven't re-scraped, or non-DI games are unaccounted for.
 *
 * scrapedWins/scrapedLosses come from SeasonStatistics (i.e. what ESPN reports);
 * calcWins/calcLosses come from counting our own D-I Game rows; nonD1Wins/Losses
 * come from the non_d1_game_observations table.
 */
public record TeamSeasonTieOut(
        Long teamId,
        String teamName,
        Integer scrapedWins,
        Integer scrapedLosses,
        Integer calcWins,
        Integer calcLosses,
        long nonD1Wins,
        long nonD1Losses
) {
    public Integer expectedWins() {
        if (calcWins == null) return null;
        return calcWins + (int) nonD1Wins;
    }

    public Integer expectedLosses() {
        if (calcLosses == null) return null;
        return calcLosses + (int) nonD1Losses;
    }

    public boolean winsTied() {
        Integer expected = expectedWins();
        return expected != null && expected.equals(scrapedWins);
    }

    public boolean lossesTied() {
        Integer expected = expectedLosses();
        return expected != null && expected.equals(scrapedLosses);
    }

    public boolean tiedOut() {
        return winsTied() && lossesTied();
    }

    public boolean hasMismatch() {
        return calcWins != null && calcLosses != null && !tiedOut();
    }
}
