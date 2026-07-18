package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ArticleFetcherTest {

    private final NewsHttpClient mockClient = Mockito.mock(NewsHttpClient.class);
    private final ArticleFetcher fetcher = new ArticleFetcher(mockClient);

    private static String fixture(String name) {
        return new String(FeedPollerTest.fixture(name), StandardCharsets.UTF_8);
    }

    @Test
    void extractsMetadataAndBody() {
        ExtractedPage page = fetcher.extract(fixture("article-full.html"),
                "https://syndicator.example.com/kansas-story");

        assertEquals("https://www.espn.com/mens-college-basketball/story/_/id/1001/kansas-tops-iowa-state",
                page.canonicalUrl());
        assertEquals("Kansas tops Iowa State behind Dickinson double-double", page.ogTitle());
        assertTrue(page.ogDescription().startsWith("Hunter Dickinson had 21"));
        // og:image was relative; absolutized against the page URL
        assertEquals("https://syndicator.example.com/images/kansas-isu.jpg", page.ogImage());
        assertEquals(LocalDateTime.of(2026, 2, 14, 3, 15), page.publishedTime());

        String body = page.bodyText();
        assertTrue(body.contains("Dickinson scored 21 points"));
        assertTrue(body.contains("Dajuan Harris added 14 points"));
        // chrome and short paragraphs stripped
        assertFalse(body.contains("Navigation links"));
        assertFalse(body.contains("header paragraph"));
        assertFalse(body.contains("sidebar"));
        assertFalse(body.contains("Copyright notice"));
        assertFalse(body.contains("By Wire Service"));
        assertFalse(body.contains("Share"));
    }

    @Test
    void adArticleBeforeStoryArticleDoesNotWinExtraction() {
        // ESPN pattern: an empty <article class="ad-300"> precedes the real story article
        String html = """
                <html><body>
                <article class="ad-300"><div>ad slot</div></article>
                <article class="article">
                  <p>Hunter Dickinson scored 21 points and grabbed 12 rebounds in the victory.</p>
                  <p>Kansas extended its home winning streak to sixteen games on Saturday night.</p>
                </article>
                </body></html>
                """;
        ExtractedPage page = fetcher.extract(html, "https://www.espn.com/story");
        assertTrue(page.bodyText().contains("Dickinson scored 21 points"));
        assertTrue(page.bodyText().contains("sixteen games"));
    }

    @Test
    void ampPagePointsAtCanonical() {
        ExtractedPage page = fetcher.extract(fixture("article-amp.html"),
                "https://amp.cbssports.com/college-basketball/news/original-story/amp/");
        assertEquals("https://www.cbssports.com/college-basketball/news/original-story/", page.canonicalUrl());
    }

    @Test
    void paywallStubYieldsShortBody() {
        ExtractedPage page = fetcher.extract(fixture("article-paywall-stub.html"),
                "https://premium.example.com/story");
        assertNull(page.canonicalUrl());
        assertTrue(SimHasher.tokenize(page.bodyText()).length < 80,
                "stub body must stay under the simhash guard");
    }

    @Test
    void failedFetchReturnsMetadataOnlyMarker() {
        when(mockClient.fetchPage(anyString())).thenReturn(
                new NewsHttpClient.FetchResult(0, "https://dead.example.com/x", null, null, null, null));
        ExtractedPage page = fetcher.fetchAndExtract("https://dead.example.com/x");
        assertFalse(page.fetchSucceeded());
        assertEquals("https://dead.example.com/x", page.finalUrl());
    }

    @Test
    void rateLimitedResponseFlagged() {
        when(mockClient.fetchPage(anyString())).thenReturn(
                new NewsHttpClient.FetchResult(429, "https://cbs.example.com/x",
                        "text/html", null, null, null));
        ExtractedPage page = fetcher.fetchAndExtract("https://cbs.example.com/x");
        assertFalse(page.fetchSucceeded());
        assertTrue(page.rateLimited());
    }

    @Test
    void nonHtmlContentTypeRejected() {
        when(mockClient.fetchPage(anyString())).thenReturn(
                new NewsHttpClient.FetchResult(200, "https://site.example.com/report.pdf",
                        "application/pdf", new byte[]{1, 2, 3}, null, null));
        ExtractedPage page = fetcher.fetchAndExtract("https://site.example.com/report.pdf");
        assertFalse(page.fetchSucceeded());
    }

    @Test
    void trimSnippetRespectsSentenceBoundary() {
        String twoSentences = "Kansas won the game by thirteen points at home. "
                + "The rest of this text pushes the total length well past the cap so trimming has to happen somewhere sensible.";
        String trimmed = ArticleFetcher.trimSnippet(twoSentences, 60);
        assertEquals("Kansas won the game by thirteen points at home.", trimmed);
    }

    @Test
    void trimSnippetFallsBackToWordBoundary() {
        String noSentenceEnd = "one two three four five six seven eight nine ten eleven twelve";
        String trimmed = ArticleFetcher.trimSnippet(noSentenceEnd, 20);
        assertTrue(trimmed.endsWith("…"));
        assertTrue(trimmed.length() <= 21);
    }

    @Test
    void trimSnippetPassesShortTextThroughAndStripsHtml() {
        assertEquals("Short and sweet.", ArticleFetcher.trimSnippet("<p>Short <b>and</b> sweet.</p>", 300));
        assertNull(ArticleFetcher.trimSnippet("   ", 300));
        assertNull(ArticleFetcher.trimSnippet(null, 300));
    }
}
