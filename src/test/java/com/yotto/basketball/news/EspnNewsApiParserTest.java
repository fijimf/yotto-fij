package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EspnNewsApiParserTest {

    private final EspnNewsApiParser parser = new EspnNewsApiParser();

    @Test
    void parsesRealApiResponse() {
        List<FeedItem> items = parser.parse(FeedPollerTest.fixture("espn-news-api.json"),
                "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/news");

        assertEquals(3, items.size());
        FeedItem first = items.get(0);
        assertEquals("SWAC football, basketball games on ESPN networks through 2030-31", first.title());
        assertTrue(first.link().startsWith("https://www.espn.com/"));
        assertTrue(first.summary().contains("media rights"));
        assertNotNull(first.publishedDate());
        assertTrue(first.imageUrl() == null || first.imageUrl().startsWith("https://a.espncdn.com/"));
    }

    @Test
    void nonJsonThrowsFeedParseException() {
        byte[] html = "<!DOCTYPE html><html>challenge</html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(FeedPoller.FeedParseException.class,
                () -> parser.parse(html, "https://site.api.espn.com/x"));
    }

    @Test
    void jsonWithoutArticlesThrows() {
        byte[] json = "{\"header\": \"nope\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(FeedPoller.FeedParseException.class,
                () -> parser.parse(json, "https://site.api.espn.com/x"));
    }

    @Test
    void skipsArticlesWithoutLinkOrHeadline() {
        byte[] json = """
                {"articles": [
                  {"headline": "No link at all"},
                  {"links": {"web": {"href": "https://www.espn.com/a"}}},
                  {"headline": "Good", "links": {"web": {"href": "https://www.espn.com/good"}},
                   "published": "2026-07-16T22:22:12Z"}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
        List<FeedItem> items = parser.parse(json, "https://site.api.espn.com/x");
        assertEquals(1, items.size());
        assertEquals("Good", items.get(0).title());
    }
}
