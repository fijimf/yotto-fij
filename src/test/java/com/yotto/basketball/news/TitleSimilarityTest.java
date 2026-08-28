package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleSimilarityTest {

    // The 2026-08 front-page triple: three outlets, one Todd Golden contract story.
    private static final String ESPN_TITLE =
            "Florida's Golden gets another contract extension and $1.5M raise";
    private static final String NBC_TITLE =
            "Florida basketball coach Todd Golden gets another contract extension and a $1.5M raise";
    private static final String YAHOO_TITLE =
            "Florida basketball inks new contract with Todd Golden";

    @Test
    void tokensDropStopwordsAndSingleCharacters() {
        Set<String> tokens = TitleSimilarity.tokens("The Gators and a win at home");
        assertEquals(Set.of("gators", "win", "home"), tokens);
    }

    @Test
    void sameStoryRewritesClearTheClusterBar() {
        double j = TitleSimilarity.jaccard(
                TitleSimilarity.tokens(ESPN_TITLE), TitleSimilarity.tokens(NBC_TITLE));
        assertTrue(j >= 0.6, "expected >= 0.6, was " + j);
        assertTrue(TitleSimilarity.sharedCount(
                TitleSimilarity.tokens(ESPN_TITLE), TitleSimilarity.tokens(NBC_TITLE)) >= 4);
    }

    @Test
    void looselyRelatedHeadlineStaysBelowTheBar() {
        // Same topic, genuinely different angle — must NOT cluster at 0.6.
        double j = TitleSimilarity.jaccard(
                TitleSimilarity.tokens(NBC_TITLE), TitleSimilarity.tokens(YAHOO_TITLE));
        assertTrue(j < 0.6, "expected < 0.6, was " + j);
    }

    @Test
    void differentGameRecapsDoNotCluster() {
        double j = TitleSimilarity.jaccard(
                TitleSimilarity.tokens("Kansas beats Baylor 78-70 in Big 12 opener"),
                TitleSimilarity.tokens("Kansas holds off Baylor in Big 12 opener"));
        assertTrue(j < 0.6, "expected < 0.6, was " + j);
    }

    @Test
    void emptyOrNullTitlesAreSafe() {
        assertEquals(Set.of(), TitleSimilarity.tokens(null));
        assertEquals(0.0, TitleSimilarity.jaccard(Set.of(), Set.of("kansas")));
    }
}
