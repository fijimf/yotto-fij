package com.yotto.basketball.news;

import java.util.List;
import java.util.Locale;

/**
 * Keeps men's college basketball items and discards other sports — notably
 * football recruiting stories about basketball schools and (per open question
 * 1's answer) women's basketball. Applied only to items from sources with
 * dedicated_cbb=false (docs/NEWS_MODULE.md §5.4).
 *
 * Rule: at least one gazetteer hit AND at least one basketball keyword AND
 * (no exclusion hits, or basketball hits strictly outnumber them — mixed
 * roundups survive, dedicated WBB/football stories don't).
 */
public final class SportFilter {

    static final List<String> BASKETBALL_KEYWORDS = List.of(
            "basketball", "hoops", "march madness", "final four", "ncaa tournament",
            "bracketology", "bracket", "big dance", "sweet 16", "sweet sixteen",
            "elite eight", "first four", "backcourt", "frontcourt", "point guard",
            "shooting guard", "three-pointer", "3-pointer", "layup", "dunk",
            "jump shot", "buzzer-beater", "tip-off", "double-double", "triple-double",
            "rebounds", "field goal percentage", "cbb", "mbb");

    static final List<String> EXCLUSION_KEYWORDS = List.of(
            // women's basketball (excluded in v1 by decision on open question 1)
            "women's basketball", "womens basketball", "wbb", "women's hoops",
            "women's college basketball",
            // other sports
            "football", "quarterback", "touchdown", "gridiron",
            "volleyball", "baseball", "softball", "soccer", "hockey", "lacrosse",
            "wrestling", "gymnastics", "track and field", "cross country",
            "swimming", "golf", "tennis", "rowing");

    /**
     * URL-path markers checked BEFORE keyword counting: major sites encode the
     * sport in the canonical URL, which survives even when body extraction
     * fails. Discard markers are checked first because
     * "womens-college-basketball" contains both "college-basketball" and
     * "mens-college-basketball" as substrings.
     */
    static final List<String> URL_DISCARD_MARKERS = List.of(
            "womens-college-basketball", "/womens-basketball", "college-football",
            "/nfl/", "/nba/", "/wnba/", "/mlb/", "/nhl/", "/soccer/", "/golf/",
            "/tennis/", "/racing/", "/mma/", "/boxing/", "/olympics/");

    static final List<String> URL_KEEP_MARKERS = List.of(
            "mens-college-basketball", "college-basketball", "/mens-basketball",
            "/ncb/", "/cbk/");

    private SportFilter() {
    }

    /**
     * Sport verdict from the canonical URL path alone: {@code TRUE} = clearly
     * men's college basketball, {@code FALSE} = clearly another sport,
     * {@code null} = no signal (fall through to keyword filtering).
     */
    public static Boolean urlVerdict(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : URL_DISCARD_MARKERS) {
            if (lower.contains(marker)) {
                return Boolean.FALSE;
            }
        }
        for (String marker : URL_KEEP_MARKERS) {
            if (lower.contains(marker)) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /**
     * @param fullText   title + summary + body (any of them may be missing)
     * @param anyTagHit  whether the gazetteer matched at least one team/conference
     */
    public static boolean keep(String fullText, boolean anyTagHit) {
        if (!anyTagHit || fullText == null || fullText.isBlank()) {
            return false;
        }
        String lower = fullText.toLowerCase(Locale.ROOT);
        int basketball = countAll(lower, BASKETBALL_KEYWORDS);
        if (basketball == 0) {
            return false;
        }
        int excluded = countAll(lower, EXCLUSION_KEYWORDS);
        return excluded == 0 || basketball > excluded;
    }

    private static int countAll(String textLower, List<String> keywords) {
        int total = 0;
        for (String keyword : keywords) {
            total += countOccurrences(textLower, keyword);
        }
        return total;
    }

    /** Substring count with word boundaries on both ends. */
    static int countOccurrences(String textLower, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = textLower.indexOf(keyword, idx)) >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(textLower.charAt(idx - 1));
            int end = idx + keyword.length();
            boolean endOk = end >= textLower.length() || !Character.isLetterOrDigit(textLower.charAt(end));
            if (startOk && endOk) {
                count++;
            }
            idx = end;
        }
        return count;
    }
}
