package com.yotto.basketball.news;

import java.util.Map;

/**
 * Output of the gazetteer tagger: raw match scores per team/conference id.
 * Includes near-misses — callers decide what to persist via the thresholds
 * in NewsProperties.Tagging (score >= tagThreshold = a real tag).
 */
public record TagResult(Map<Long, TargetScore> teams, Map<Long, TargetScore> conferences) {

    /**
     * @param score      raw match score (§5.7: 3.0/title hit + 1.0/body hit capped at 4 + 1.5 corroborated ambiguous)
     * @param confidence min(1.0, score / 5.0)
     * @param matchedVia distinct alias texts that contributed, for admin debugging
     */
    public record TargetScore(double score, double confidence, String matchedVia) {
    }

    public static TagResult empty() {
        return new TagResult(Map.of(), Map.of());
    }

    public boolean hasAnyMatch() {
        return !teams.isEmpty() || !conferences.isEmpty();
    }
}
