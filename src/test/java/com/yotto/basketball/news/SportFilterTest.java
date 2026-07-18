package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SportFilterTest {

    @Test
    void keepsMensBasketballStory() {
        assertTrue(SportFilter.keep(
                "Kansas tops Iowa State in college basketball showdown as Dickinson posts a double-double", true));
    }

    @Test
    void rejectsWhenNoAliasHit() {
        assertFalse(SportFilter.keep("Great basketball game last night in the NCAA tournament", false));
    }

    @Test
    void rejectsFootballStoryAboutBasketballSchool() {
        assertFalse(SportFilter.keep(
                "Alabama lands five-star quarterback as football recruiting heats up before signing day", true));
    }

    @Test
    void rejectsWomensBasketball() {
        // every basketball keyword occurrence here is inside a women's-basketball phrase
        assertFalse(SportFilter.keep(
                "South Carolina remains unbeaten in women's basketball; the women's basketball rankings are out", true));
    }

    @Test
    void keepsMixedRoundupWhenBasketballDominates() {
        assertTrue(SportFilter.keep(
                "Hoops roundup: bracketology update, Final Four odds, and a basketball transfer note; "
                        + "also the football team hired a coordinator", true));
    }

    @Test
    void rejectsWhenNoBasketballContext() {
        assertFalse(SportFilter.keep("Kansas announces new athletic director for all sports", true));
    }

    @Test
    void urlVerdictRecognizesBasketballPaths() {
        assertEquals(Boolean.TRUE, SportFilter.urlVerdict(
                "https://www.espn.com/mens-college-basketball/story/_/id/1/recruiting-rankings"));
        assertEquals(Boolean.TRUE, SportFilter.urlVerdict(
                "https://www.cbssports.com/college-basketball/news/some-story/"));
        assertEquals(Boolean.TRUE, SportFilter.urlVerdict("https://www.espn.com/espn/rss/ncb/news-item"));
    }

    @Test
    void urlVerdictRejectsOtherSports() {
        assertEquals(Boolean.FALSE, SportFilter.urlVerdict(
                "https://www.espn.com/college-football/story/_/id/2/swac-deal"));
        assertEquals(Boolean.FALSE, SportFilter.urlVerdict(
                "https://www.espn.com/nba/story/_/id/3/trade"));
        // substring trap: contains both "mens-college-basketball" and "college-basketball"
        assertEquals(Boolean.FALSE, SportFilter.urlVerdict(
                "https://www.espn.com/womens-college-basketball/story/_/id/4/final-four"));
    }

    @Test
    void urlVerdictNeutralWhenNoSignal() {
        assertNull(SportFilter.urlVerdict("https://hoopsblog.example.com/kansas-notes"));
        assertNull(SportFilter.urlVerdict(null));
    }

    @Test
    void wordBoundariesInCounting() {
        // "basketball" inside another word must not count
        assertEquals(0, SportFilter.countOccurrences("thebasketballer said", "basketball"));
        assertEquals(2, SportFilter.countOccurrences("basketball and more basketball!", "basketball"));
        assertEquals(1, SportFilter.countOccurrences("cbb news", "cbb"));
    }
}
