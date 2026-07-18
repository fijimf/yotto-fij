package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlCanonicalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // scheme upgrade + www strip + lowercase host
            "http://WWW.ESPN.com/story|https://espn.com/story",
            // trailing slash stripped
            "https://espn.com/story/|https://espn.com/story",
            // bare root keeps slash
            "https://espn.com/|https://espn.com/",
            "https://espn.com|https://espn.com/",
            // fragment stripped
            "https://espn.com/story#comments|https://espn.com/story",
            // default ports stripped, exotic port kept
            "https://espn.com:443/story|https://espn.com/story",
            "http://espn.com:80/story|https://espn.com/story",
            "https://espn.com:8443/story|https://espn.com:8443/story",
            // utm_* and click ids stripped
            "https://espn.com/story?utm_source=x&utm_medium=y|https://espn.com/story",
            "https://espn.com/story?fbclid=abc123|https://espn.com/story",
            "https://espn.com/story?gclid=1&ref=rss&src=feed|https://espn.com/story",
            // real params survive, sorted
            "https://site.com/article?id=12345|https://site.com/article?id=12345",
            "https://site.com/a?z=1&a=2|https://site.com/a?a=2&z=1",
            // mixed: tracking stripped, real kept
            "https://site.com/a?utm_campaign=x&id=9|https://site.com/a?id=9",
            // path case preserved (slugs can be case-sensitive)
            "https://site.com/Article/Slug|https://site.com/Article/Slug",
    })
    void canonicalizes(String input, String expected) {
        assertEquals(expected, UrlCanonicalizer.canonicalize(input));
    }

    @Test
    void sameStoryDifferentTrackingCollapses() {
        String a = UrlCanonicalizer.canonicalize(
                "https://www.cbssports.com/college-basketball/news/story-x/?utm_source=twitter&fbclid=z");
        String b = UrlCanonicalizer.canonicalize(
                "http://cbssports.com/college-basketball/news/story-x?utm_medium=feed");
        assertEquals(a, b);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "ftp://site.com/x", "mailto:a@b.com", "/relative/path"})
    void rejectsNonHttpUrls(String input) {
        assertThrows(IllegalArgumentException.class, () -> UrlCanonicalizer.canonicalize(input));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> UrlCanonicalizer.canonicalize(null));
    }
}
