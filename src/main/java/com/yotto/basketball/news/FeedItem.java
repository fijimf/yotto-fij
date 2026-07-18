package com.yotto.basketball.news;

import java.time.LocalDateTime;

/**
 * One entry parsed out of a source (RSS/Atom feed or the ESPN news API).
 * Dates are nullable — resolution happens downstream. {@code imageUrl} is only
 * populated by API sources; RSS items get their image from page extraction.
 */
public record FeedItem(String link, String title, String summary,
                       LocalDateTime publishedDate, LocalDateTime updatedDate,
                       String imageUrl) {

    public FeedItem(String link, String title, String summary,
                    LocalDateTime publishedDate, LocalDateTime updatedDate) {
        this(link, title, summary, publishedDate, updatedDate, null);
    }
}
