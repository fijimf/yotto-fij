package com.yotto.basketball.news;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses RSS/Atom feed bytes into {@link FeedItem}s. Pure parsing — fetching,
 * age filtering, and per-source caps live in the polling service.
 */
@Component
public class FeedPoller {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    /**
     * @throws FeedParseException when the bytes are not a parseable feed
     */
    public List<FeedItem> parse(byte[] feedBytes, String feedUrl) {
        String head = new String(feedBytes, 0, Math.min(feedBytes.length, 200),
                java.nio.charset.StandardCharsets.UTF_8).stripLeading().toLowerCase();
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw new FeedParseException("Feed URL " + feedUrl
                    + " returned an HTML page, not a feed — the site may be blocking server-side requests", null);
        }
        SyndFeed feed;
        try {
            feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(feedBytes)));
        } catch (Exception e) {
            throw new FeedParseException("Unparseable feed " + feedUrl + ": " + e.getMessage(), e);
        }

        String baseUrl = feed.getLink() != null && !feed.getLink().isBlank() ? feed.getLink() : feedUrl;
        List<FeedItem> items = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        for (SyndEntry entry : feed.getEntries()) {
            String link = entry.getLink();
            if (link == null || link.isBlank()) {
                continue;
            }
            link = absolutize(link.trim(), baseUrl);
            if (link == null || !seenLinks.add(link)) {
                continue;
            }
            String title = entry.getTitle() != null ? entry.getTitle().strip() : null;
            if (title == null || title.isEmpty()) {
                continue;
            }
            String summary = entry.getDescription() != null ? entry.getDescription().getValue() : null;
            items.add(new FeedItem(link, title, summary,
                    toLocalDateTime(entry.getPublishedDate()),
                    toLocalDateTime(entry.getUpdatedDate())));
        }
        return items;
    }

    private static String absolutize(String link, String baseUrl) {
        try {
            URI uri = URI.create(link);
            if (uri.isAbsolute()) {
                return link;
            }
            return URI.create(baseUrl).resolve(uri).toString();
        } catch (IllegalArgumentException e) {
            log.debug("Skipping malformed feed link {}", link);
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.of("UTC"));
    }

    public static class FeedParseException extends RuntimeException {
        public FeedParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
