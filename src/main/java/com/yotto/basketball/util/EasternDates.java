package com.yotto.basketball.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Eastern ↔ UTC calendar helpers.
 *
 * <p>{@code Game.gameDate} is stored as a naive UTC {@link LocalDateTime}. "Games on Eastern date D"
 * is resolved by querying the UTC window that D maps to ({@link #dayWindowUtc}). All calendar-date
 * bucketing of a stored UTC instant goes through {@link #toEasternDate}.
 */
public final class EasternDates {

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private EasternDates() {}

    /** The {@code [start, end)} UTC window for the given Eastern calendar date. */
    public static LocalDateTime[] dayWindowUtc(LocalDate easternDate) {
        LocalDateTime start = easternDate.atStartOfDay(EASTERN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime end = easternDate.plusDays(1).atStartOfDay(EASTERN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new LocalDateTime[]{start, end};
    }

    /** The {@code [startOfStart, endOfEnd)} UTC window spanning the inclusive Eastern date range. */
    public static LocalDateTime[] rangeWindowUtc(LocalDate startEastern, LocalDate endEastern) {
        return new LocalDateTime[]{dayWindowUtc(startEastern)[0], dayWindowUtc(endEastern)[1]};
    }

    /** Bucket a stored UTC instant into its Eastern calendar date. */
    public static LocalDate toEasternDate(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(EASTERN).toLocalDate();
    }

    /** Convert a stored UTC instant to Eastern wall-clock time. */
    public static LocalDateTime toEasternTime(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(EASTERN).toLocalDateTime();
    }
}
