package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimHasherTest {

    // Realistic wire-story length (~280 tokens). SimHash tolerance to edits scales
    // with body length; the min-body-tokens ingest guard (80) keeps degenerately
    // short bodies out of simhash clustering entirely.
    private static final String WIRE_STORY = """
            LAWRENCE, Kan. — Hunter Dickinson scored 21 points and grabbed 12 rebounds as
            No. 4 Kansas beat Iowa State 78-65 on Saturday night at Allen Fieldhouse. Dajuan
            Harris added 14 points and eight assists for the Jayhawks, who extended their
            home winning streak to 16 games. Kansas shot 52 percent from the field and held
            the Cyclones to 38 percent. Iowa State was led by Curtis Jones with 18 points.
            KJ Adams contributed 11 points and six rebounds, and the Kansas bench outscored
            the Iowa State reserves 24-9. The Jayhawks opened the game on a 14-2 run and
            never trailed, pushing the lead to 22 midway through the second half before the
            Cyclones trimmed the margin with a late flurry of three-pointers. Kansas
            dominated the paint, outscoring Iowa State 42-26 inside, and won the rebounding
            battle 38-27. The Cyclones committed 16 turnovers, which Kansas converted into
            21 points. Tamin Lipsey added 12 points for Iowa State, which had won four
            straight coming in. Kansas coach Bill Self praised his team's defensive
            intensity afterward, noting the Jayhawks held their fifth straight opponent
            under 70 points. The victory moved Kansas to 12-1 in conference play, a game
            ahead of Houston in the Big 12 standings, while Iowa State fell to 9-4. The
            Jayhawks host Baylor on Tuesday before traveling to face the Cougars in a
            first-place showdown next weekend. Iowa State returns home to face Kansas
            State, which has lost six of its last eight games this season.
            """;

    @Test
    void identicalTextIdenticalHash() {
        assertEquals(SimHasher.hash(WIRE_STORY), SimHasher.hash(WIRE_STORY));
    }

    @Test
    void republishedWireStoryWithinThreshold() {
        // Same wire body with a different lede attribution and editorial trims at both
        // ends, the way a republisher typically alters an AP story.
        String republished = WIRE_STORY
                .replace("LAWRENCE, Kan. —", "LAWRENCE, Kansas (AP) —")
                .replace("on Saturday night at Allen Fieldhouse", "Saturday at Allen Fieldhouse")
                .replace(" Iowa State returns home to face Kansas\nState, which has lost six of its last eight games this season.", "");
        int distance = SimHasher.hammingDistance(SimHasher.hash(WIRE_STORY), SimHasher.hash(republished));
        assertTrue(distance <= 10, "expected near-duplicate within default threshold, hamming=" + distance);
    }

    @Test
    void verbatimBodyDifferentBoilerplateIsNearIdentical() {
        // The most common real case: extraction strips site chrome and the wire body
        // itself survives verbatim, differing only in attribution framing.
        String a = "By The Associated Press | " + WIRE_STORY;
        String b = WIRE_STORY + " Copyright Associated Press. All rights reserved.";
        int distance = SimHasher.hammingDistance(SimHasher.hash(a), SimHasher.hash(b));
        assertTrue(distance <= 10, "hamming=" + distance);
    }

    @Test
    void differentStoriesFarApart() {
        String other = """
                DURHAM, N.C. — Cooper Flagg poured in 30 points as Duke overwhelmed North
                Carolina 92-70 at Cameron Indoor Stadium. The Blue Devils forced 19 turnovers
                and led by as many as 28. Kon Knueppel chipped in 17 for Duke, which clinched
                at least a share of the ACC regular-season title. The Tar Heels got 15 points
                from RJ Davis but shot just 35 percent. Duke visits Wake Forest next week
                before the conference tournament begins in Washington.
                """;
        int distance = SimHasher.hammingDistance(SimHasher.hash(WIRE_STORY), SimHasher.hash(other));
        assertTrue(distance > 15, "expected distinct stories well beyond threshold, hamming=" + distance);
    }

    @Test
    void punctuationAndCaseInsensitive() {
        assertEquals(SimHasher.hash("Kansas beats Iowa State, 78-65!"),
                SimHasher.hash("kansas beats iowa state 78 65"));
    }

    @Test
    void degenerateInputs() {
        assertEquals(0L, SimHasher.hash(null));
        assertEquals(0L, SimHasher.hash(""));
        assertEquals(0L, SimHasher.hash("Kansas"));
        assertEquals(0L, SimHasher.hash("!!! ... ---"));
    }

    @Test
    void tokenizeStripsPunctuation() {
        assertArrayEquals(new String[]{"kansas", "78", "65", "win"},
                SimHasher.tokenize("Kansas' 78-65 win."));
    }

    @Test
    void hammingDistanceBasics() {
        assertEquals(0, SimHasher.hammingDistance(5L, 5L));
        assertEquals(64, SimHasher.hammingDistance(0L, -1L));
        assertEquals(1, SimHasher.hammingDistance(0L, 1L));
    }
}
