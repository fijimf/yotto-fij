package com.yotto.basketball.news;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 64-bit SimHash over word 2-grams of extracted body text, for near-duplicate
 * detection of republished wire stories (docs/NEWS_MODULE.md §5.5).
 * Deterministic and dependency-free; FNV-1a as the shingle hash.
 */
public final class SimHasher {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private SimHasher() {
    }

    /** Lowercases, strips punctuation, splits on whitespace. */
    public static String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String trimmed = cleaned.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    /** SimHash over word 2-grams. Returns 0 for input with fewer than 2 tokens (callers guard on token count anyway). */
    public static long hash(String text) {
        String[] tokens = tokenize(text);
        if (tokens.length < 2) {
            return 0L;
        }
        int[] vector = new int[64];
        for (int i = 0; i < tokens.length - 1; i++) {
            long shingleHash = fnv1a(tokens[i] + " " + tokens[i + 1]);
            for (int bit = 0; bit < 64; bit++) {
                if (((shingleHash >>> bit) & 1L) == 1L) {
                    vector[bit]++;
                } else {
                    vector[bit]--;
                }
            }
        }
        long result = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (vector[bit] > 0) {
                result |= (1L << bit);
            }
        }
        return result;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long fnv1a(String s) {
        long hash = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
