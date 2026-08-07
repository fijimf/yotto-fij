package com.yotto.basketball.service;

import com.yotto.basketball.entity.Season;

import java.time.LocalDate;

/**
 * Where "now" falls in the basketball calendar, resolved by {@link SeasonPhaseService}.
 *
 * <p>All dates are Eastern calendar dates (see {@code EasternDates}). {@code season} is the season
 * the phase refers to: the in-progress or upcoming season when one exists, otherwise the most
 * recently completed one; {@code null} only on an empty database. Derived dates are {@code null}
 * when the underlying data doesn't exist yet (e.g. no games scraped).
 *
 * @param championshipDate Eastern date of the NCAA title game once it is FINAL, else null
 * @param confTourneyWeek  flair: conference-tournament games within [today-1, today+5]
 * @param selectionSunday  flair: today is day 1 of POSTSEASON (bracket reveal + conf title games)
 */
public record SeasonPhase(
        Phase phase,
        Season season,
        LocalDate today,
        LocalDate firstGameDate,
        LocalDate lastGameDate,
        LocalDate championshipDate,
        boolean confTourneyWeek,
        boolean selectionSunday) {

    public enum Phase { OFFSEASON, PRESEASON, IN_SEASON, POSTSEASON, EPILOGUE }

    /**
     * The bracket nav link is hidden only while a season is underway without a bracket of its own;
     * every other phase shows the most recent bracket (POSTSEASON/EPILOGUE: the live one).
     */
    public boolean showBracketLink() {
        return phase != Phase.IN_SEASON;
    }
}
