package com.yotto.basketball.news;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.entity.Team;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the ambiguity matrix from docs/NEWS_MODULE.md §5.6–5.7 against a
 * fixture gazetteer (no DB).
 */
class TeamTaggerTest {

    private static final Team KANSAS = team(1L, "Kansas");
    private static final Team KENTUCKY = team(2L, "Kentucky");
    private static final Team ARIZONA = team(3L, "Arizona");
    private static final Team MIAMI_FL = team(4L, "Miami");
    private static final Team MIAMI_OH = team(5L, "Miami");
    private static final Team CALIFORNIA = team(6L, "California");
    private static final Team USC = team(7L, "USC");
    private static final Team NC_STATE = team(8L, "NC State");
    private static final Team UNC = team(9L, "North Carolina");
    private static final Team GONZAGA = team(10L, "Gonzaga");
    private static final Conference BIG12 = conference(100L, "Big 12");
    private static final Conference ACC = conference(101L, "ACC");

    private static Team team(Long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static Conference conference(Long id, String name) {
        Conference c = new Conference();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static List<NewsAlias> fixtureAliases() {
        List<NewsAlias> aliases = new ArrayList<>();
        // Kansas: ambiguous bare state name, unambiguous full name, ambiguous mascot
        aliases.add(NewsAlias.forTeam("Kansas", KANSAS, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Kansas Jayhawks", KANSAS, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("Jayhawks", KANSAS, NewsAlias.Kind.AUTO, true, false));
        // Wildcats shared by Kentucky and Arizona
        aliases.add(NewsAlias.forTeam("Kentucky", KENTUCKY, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Kentucky Wildcats", KENTUCKY, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("Wildcats", KENTUCKY, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Arizona", ARIZONA, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Arizona Wildcats", ARIZONA, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("Wildcats", ARIZONA, NewsAlias.Kind.AUTO, true, false));
        // Miami ×2 with distinct mascots
        aliases.add(NewsAlias.forTeam("Miami", MIAMI_FL, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Hurricanes", MIAMI_FL, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("Miami", MIAMI_OH, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("RedHawks", MIAMI_OH, NewsAlias.Kind.AUTO, false, false));
        // Cal: short alias, forced case-sensitive; must not fire inside "Calipari"
        aliases.add(NewsAlias.forTeam("Cal", CALIFORNIA, NewsAlias.Kind.AUTO, false, true));
        aliases.add(NewsAlias.forTeam("California", CALIFORNIA, NewsAlias.Kind.AUTO, true, false));
        // USC abbreviation, case-sensitive
        aliases.add(NewsAlias.forTeam("USC", USC, NewsAlias.Kind.AUTO, false, true));
        aliases.add(NewsAlias.forTeam("USC Trojans", USC, NewsAlias.Kind.AUTO, false, false));
        // Longest-match pair
        aliases.add(NewsAlias.forTeam("NC State", NC_STATE, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("North Carolina State", NC_STATE, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("North Carolina", UNC, NewsAlias.Kind.AUTO, true, false));
        aliases.add(NewsAlias.forTeam("Tar Heels", UNC, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forTeam("Gonzaga", GONZAGA, NewsAlias.Kind.AUTO, false, false));
        // Conferences
        aliases.add(NewsAlias.forConference("Big 12", BIG12, NewsAlias.Kind.AUTO, false, false));
        aliases.add(NewsAlias.forConference("ACC", ACC, NewsAlias.Kind.AUTO, false, true));
        return aliases;
    }

    private static TagResult tag(String title, String body) {
        return TeamTagger.tagWith(fixtureAliases(), title, body);
    }

    @Test
    void unambiguousTitleHitScoresThree() {
        TagResult result = tag("Kansas Jayhawks roll to another win", "");
        TagResult.TargetScore score = result.teams().get(KANSAS.getId());
        assertNotNull(score);
        assertEquals(3.0, score.score(), 0.001);
        assertEquals(0.6, score.confidence(), 0.001);
        assertTrue(score.matchedVia().contains("Kansas Jayhawks"));
    }

    @Test
    void ambiguousAliasAloneTagsNothing() {
        TagResult result = tag("Miami wins big", "Miami cruised to a comfortable victory on Tuesday.");
        assertNull(result.teams().get(MIAMI_FL.getId()));
        assertNull(result.teams().get(MIAMI_OH.getId()));
    }

    @Test
    void miamiDisambiguatedByMascot() {
        TagResult result = tag("Miami wins big",
                "The Hurricanes cruised as Miami controlled the boards all night.");
        TagResult.TargetScore fl = result.teams().get(MIAMI_FL.getId());
        assertNotNull(fl);
        // Hurricanes body hit (1.0) + corroborated ambiguous bonus (1.5)
        assertEquals(2.5, fl.score(), 0.001);
        assertNull(result.teams().get(MIAMI_OH.getId()));

        TagResult other = tag("Miami wins big",
                "The RedHawks cruised as Miami controlled the boards all night.");
        assertNotNull(other.teams().get(MIAMI_OH.getId()));
        assertNull(other.teams().get(MIAMI_FL.getId()));
    }

    @Test
    void sharedMascotCreditsOnlyCorroboratedTeam() {
        TagResult result = tag("Wildcats survive scare",
                "Kentucky Wildcats guard play saved the game late as the Wildcats rallied.");
        TagResult.TargetScore kentucky = result.teams().get(KENTUCKY.getId());
        assertNotNull(kentucky);
        // "Kentucky Wildcats" body hit (1.0) + corroborated ambiguous "Wildcats" bonus (1.5):
        // above the 2.0 tag threshold even with no unambiguous title mention
        assertEquals(2.5, kentucky.score(), 0.001);
        assertNull(result.teams().get(ARIZONA.getId()));
    }

    @Test
    void sharedMascotWithBothTeamsCorroboratedGetsNoBonus() {
        TagResult result = tag("Wildcats meet Wildcats",
                "Kentucky Wildcats face the Arizona Wildcats in a marquee matchup.");
        TagResult.TargetScore kentucky = result.teams().get(KENTUCKY.getId());
        TagResult.TargetScore arizona = result.teams().get(ARIZONA.getId());
        assertNotNull(kentucky);
        assertNotNull(arizona);
        // both get their unambiguous body hit; neither gets the ambiguous "Wildcats" bonus
        assertEquals(1.0, kentucky.score(), 0.001);
        assertEquals(1.0, arizona.score(), 0.001);
    }

    @Test
    void calDoesNotFireInsideCalipari() {
        TagResult result = tag("Calipari discusses the season", "John Calipari spoke at length on Tuesday.");
        assertNull(result.teams().get(CALIFORNIA.getId()));
    }

    @Test
    void calMatchesAsExactWord() {
        TagResult result = tag("Cal upsets Gonzaga", "");
        assertNotNull(result.teams().get(CALIFORNIA.getId()));
        assertNotNull(result.teams().get(GONZAGA.getId()));
    }

    @Test
    void shortAliasesAreCaseSensitiveEvenWhenNotFlagged() {
        // "usc" lowercase in a slug-like context must not match the USC abbreviation
        TagResult result = tag("reading the usc tea leaves", "");
        assertNull(result.teams().get(USC.getId()));

        TagResult upper = tag("USC lands transfer guard", "");
        assertNotNull(upper.teams().get(USC.getId()));
    }

    @Test
    void longestMatchWinsForNcState() {
        TagResult result = tag("North Carolina State clinches bye", "");
        assertNotNull(result.teams().get(NC_STATE.getId()));
        // "North Carolina" must not fire inside "North Carolina State"
        assertNull(result.teams().get(UNC.getId()));
    }

    @Test
    void possessiveMatches() {
        TagResult result = tag("Gonzaga's frontcourt dominates", "");
        assertNotNull(result.teams().get(GONZAGA.getId()));
    }

    @Test
    void bodyHitsCappedAtFour() {
        String body = "Gonzaga scored. Gonzaga defended. Gonzaga rebounded. Gonzaga ran. "
                + "Gonzaga pressed. Gonzaga shot well. Gonzaga celebrated.";
        TagResult result = tag("A very long recap", body);
        assertEquals(4.0, result.teams().get(GONZAGA.getId()).score(), 0.001);
    }

    @Test
    void blockSuppressesAlias() {
        List<NewsAlias> aliases = new ArrayList<>(fixtureAliases());
        aliases.add(NewsAlias.forTeam("Gonzaga", GONZAGA, NewsAlias.Kind.BLOCK, false, false));
        TagResult result = TeamTagger.tagWith(aliases, "Gonzaga wins again", "");
        assertNull(result.teams().get(GONZAGA.getId()));
    }

    @Test
    void conferenceDirectHitsScored() {
        TagResult result = tag("Big 12 race tightens", "The ACC standings also shifted this week.");
        assertNotNull(result.conferences().get(BIG12.getId()));
        assertNotNull(result.conferences().get(ACC.getId()));
        assertEquals(3.0, result.conferences().get(BIG12.getId()).score(), 0.001);
        assertEquals(1.0, result.conferences().get(ACC.getId()).score(), 0.001);
    }

    @Test
    void lowercaseAccDoesNotMatch() {
        TagResult result = tag("acc network schedule notes", "");
        assertNull(result.conferences().get(ACC.getId()));
    }

    @Test
    void gameRecapTagsBothTeams() {
        TagResult result = tag("Kansas Jayhawks beat Kentucky Wildcats",
                "The Jayhawks outlasted the Wildcats in overtime.");
        assertNotNull(result.teams().get(KANSAS.getId()));
        assertNotNull(result.teams().get(KENTUCKY.getId()));
    }

    @Test
    void noMatchesReturnsEmpty() {
        TagResult result = tag("Completely unrelated news story", "Nothing about sports here at all.");
        assertFalse(result.hasAnyMatch());
    }
}
