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
    void rejectsProBasketballStoryThatNameDropsACollege() {
        // The 2026-08 Yahoo failure mode: an NBA/WNBA story whose snippet mentions
        // the player's college scores a gazetteer hit + a "basketball" keyword.
        assertFalse(SportFilter.keep(
                "Caitlin Clark draws major praise as WNBA analyst credits the former Iowa "
                        + "basketball star for the league's historic growth", true));
        assertFalse(SportFilter.keep(
                "NBA veteran and former Kentucky guard nears 20-year basketball milestone "
                        + "as NBA contenders circle", true));
    }

    @Test
    void keepsCbbStoryThatMentionsTheNbaDraft() {
        assertTrue(SportFilter.keep(
                "Duke's point guard declares for the NBA draft after a Final Four run and a "
                        + "record field goal percentage season", true));
    }

    @Test
    void rejectsWomensRecruitStoryWhereBasketballHitsAreMostlyEmbedded() {
        // The 2026-08 leak: a WBB recruit story whose only standalone basketball
        // signal was "shooting guard" — the "basketball" occurrences all sat
        // inside "women's basketball", which must not count as men's evidence.
        assertFalse(SportFilter.keep(
                "Four-star recruit decommits from Gophers. The Rosemount shooting guard was "
                        + "Minnesota's lone women's basketball commit; the women's basketball staff "
                        + "now has no commits for next season", true));
    }

    @Test
    void rejectsFootballStoryUsingNflSignal() {
        // "Texas judge puts SEC ban on hold, opening door for players on NFL
        // rosters to return" — football eligibility news with no basketball signal
        // beyond a gazetteer hit.
        assertFalse(SportFilter.keep(
                "Texas judge puts SEC ban on hold, opening door for players on NFL rosters "
                        + "to return; Alabama and the NFL players association reacted", true));
    }

    @Test
    void dedicatedFeedBackstopDiscardsFootballStory() {
        // Dedicated-CBB feeds skip the full filter, but an outright football story
        // (the 2026-08 Lane Kiffin leak) has ≥2 other-sport hits and no basketball.
        assertFalse(SportFilter.keepDedicated(
                "SEC schools approve penalties for signing returning pro athletes. Tigers "
                        + "football coach Lane Kiffin has recruited at least two football players "
                        + "made NCAA eligible by a Louisiana court injunction"));
    }

    @Test
    void dedicatedFeedBackstopKeepsCbbStories() {
        // An incidental football mention must not discard a real CBB story...
        assertTrue(SportFilter.keepDedicated(
                "Kansas basketball opens practice; the point guard rotation looks deep and the "
                        + "football team's stadium hosted the hoops scrimmage"));
        // ...nor may NBA-draft coverage, which dedicated feeds legitimately carry.
        assertTrue(SportFilter.keepDedicated(
                "Duke's point guard declares for the NBA draft; NBA scouts watched every game"));
        // ...and metadata-only items (no fetched body) keep the feed's word.
        assertTrue(SportFilter.keepDedicated(""));
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
