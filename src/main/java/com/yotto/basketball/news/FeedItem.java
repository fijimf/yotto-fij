package com.yotto.basketball.news;

import java.time.LocalDateTime;

/** One entry parsed out of an RSS/Atom feed. Dates are nullable — resolution happens downstream. */
public record FeedItem(String link, String title, String summary,
                       LocalDateTime publishedDate, LocalDateTime updatedDate) {
}
