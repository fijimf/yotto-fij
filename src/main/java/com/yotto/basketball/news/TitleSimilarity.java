package com.yotto.basketball.news;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Token-set similarity over headlines, the second dedup layer
 * (docs/NEWS_MODULE.md §5.5): same-story rewrites from different outlets share
 * most meaningful title tokens ("Todd Golden gets another contract extension")
 * but hash far apart on body simhash, which only catches verbatim republishes.
 */
public final class TitleSimilarity {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "in", "on", "to", "as",
            "at", "with", "after", "before", "over", "under", "vs", "by", "is",
            "are", "was", "be", "his", "her", "their", "its", "from", "into");

    private TitleSimilarity() {
    }

    /** Lowercased word tokens minus stopwords and single characters. */
    public static Set<String> tokens(String title) {
        Set<String> tokens = new HashSet<>();
        if (title == null) {
            return tokens;
        }
        for (String raw : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 2 && !STOPWORDS.contains(raw)) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    public static int sharedCount(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int shared = 0;
        for (String token : smaller) {
            if (larger.contains(token)) {
                shared++;
            }
        }
        return shared;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int shared = sharedCount(a, b);
        return (double) shared / (a.size() + b.size() - shared);
    }
}
