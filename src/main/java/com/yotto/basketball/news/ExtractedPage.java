package com.yotto.basketball.news;

import java.time.LocalDateTime;

/**
 * Everything the pipeline needs from a fetched article page. {@code bodyText}
 * is used transiently for tagging and SimHash and MUST NOT be persisted
 * (docs/NEWS_MODULE.md §1).
 *
 * @param fetchSucceeded false = metadata-only item (fetch failed / non-HTML / too large);
 *                       the feed's title/summary are all we have
 * @param finalUrl       URL after redirects
 * @param canonicalUrl   the page's own rel=canonical (absolutized), or null
 */
public record ExtractedPage(boolean fetchSucceeded, String finalUrl, String canonicalUrl,
                            String ogTitle, String ogDescription, String ogImage,
                            LocalDateTime publishedTime, String bodyText) {

    public static ExtractedPage failed(String url) {
        return new ExtractedPage(false, url, null, null, null, null, null, null);
    }
}
