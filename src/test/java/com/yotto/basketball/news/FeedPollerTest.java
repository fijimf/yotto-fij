package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedPollerTest {

    private final FeedPoller poller = new FeedPoller();

    static byte[] fixture(String name) {
        try (var in = FeedPollerTest.class.getResourceAsStream("/news/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesRssSkippingBadEntries() {
        List<FeedItem> items = poller.parse(fixture("feed-espn.xml"), "https://www.espn.com/rss");

        // 6 entries: 2 good, 1 duplicate link (skipped), 1 relative link (kept, absolutized),
        // 1 empty title (skipped), 1 no link (skipped)
        assertEquals(3, items.size());

        FeedItem first = items.get(0);
        assertEquals("Kansas tops Iowa State behind Dickinson double-double", first.title());
        assertTrue(first.link().contains("id/1001"));
        assertEquals(LocalDateTime.of(2026, 2, 14, 3, 15), first.publishedDate());
        assertTrue(first.summary().contains("Dickinson"));

        FeedItem relative = items.get(2);
        assertEquals("https://www.espn.com/mens-college-basketball/story/_/id/1003/relative-link-story",
                relative.link());
    }

    @Test
    void garbageDatesComeThroughAsNull() {
        List<FeedItem> items = poller.parse(fixture("feed-garbage-dates.xml"), "https://hoopsblog.example.com/feed");
        assertEquals(2, items.size());
        assertNull(items.get(0).publishedDate());
        assertNull(items.get(1).publishedDate());
    }

    @Test
    void parsesAtom() {
        List<FeedItem> items = poller.parse(fixture("feed-atom.xml"), "https://theacc.example.com/feed");
        assertEquals(1, items.size());
        FeedItem item = items.get(0);
        assertEquals("Tournament bracket released", item.title());
        assertEquals("https://theacc.example.com/news/tournament-bracket-released", item.link());
        assertEquals(LocalDateTime.of(2026, 2, 15, 8, 30), item.updatedDate());
        assertTrue(item.summary().contains("seedings"));
    }

    @Test
    void unparseableBytesThrow() {
        byte[] notAFeed = "<html><body>definitely not a feed</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(FeedPoller.FeedParseException.class,
                () -> poller.parse(notAFeed, "https://example.com/feed"));
    }
}
