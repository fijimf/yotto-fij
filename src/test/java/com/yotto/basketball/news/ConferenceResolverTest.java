package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConferenceResolverTest {

    @Test
    void seasonAttribution() {
        // in-season months belong to the season ending that calendar year
        assertEquals(2026, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 1, 15)));
        assertEquals(2026, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 4, 5)));
        assertEquals(2026, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 6, 30)));
        // offseason (July onward) belongs to the upcoming season
        assertEquals(2027, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 7, 1)));
        assertEquals(2027, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 11, 20)));
        assertEquals(2027, ConferenceResolver.seasonYearFor(LocalDate.of(2026, 12, 31)));
    }
}
