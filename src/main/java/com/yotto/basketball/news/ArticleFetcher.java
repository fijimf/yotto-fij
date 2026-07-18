package com.yotto.basketball.news;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Fetches an article page and extracts metadata + transient body text.
 * Extraction is deliberately heuristic (readability-lite): the body feeds
 * tagging and SimHash, so "clean enough" beats "perfect".
 */
@Component
public class ArticleFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);

    private final NewsHttpClient httpClient;

    public ArticleFetcher(NewsHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Never throws — a failed fetch returns {@link ExtractedPage#failed}. */
    public ExtractedPage fetchAndExtract(String url) {
        NewsHttpClient.FetchResult result = httpClient.fetchPage(url);
        if (result.status() == 429) {
            return ExtractedPage.rateLimited(result.finalUrl());
        }
        if (!result.isSuccess()) {
            return ExtractedPage.failed(result.finalUrl());
        }
        if (result.contentType() != null && !result.contentType().contains("html")) {
            log.debug("Non-HTML content-type {} for {}", result.contentType(), url);
            return ExtractedPage.failed(result.finalUrl());
        }
        try {
            return extract(new String(result.body(), charsetOf(result)), result.finalUrl());
        } catch (Exception e) {
            log.debug("Extraction failed for {}: {}", url, e.toString());
            return ExtractedPage.failed(result.finalUrl());
        }
    }

    private static java.nio.charset.Charset charsetOf(NewsHttpClient.FetchResult result) {
        String contentType = result.contentType();
        if (contentType != null && contentType.contains("charset=")) {
            try {
                String name = contentType.substring(contentType.indexOf("charset=") + 8)
                        .split("[;\\s]")[0].replace("\"", "");
                return java.nio.charset.Charset.forName(name);
            } catch (Exception ignored) {
                // fall through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Package-visible so tests can drive extraction from fixture HTML without HTTP. */
    ExtractedPage extract(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);

        String canonical = absolutize(firstAttr(doc, "link[rel=canonical]", "href"), pageUrl);
        String ogTitle = firstAttr(doc, "meta[property=og:title]", "content");
        String ogDescription = firstAttr(doc, "meta[property=og:description]", "content");
        if (ogDescription == null) {
            ogDescription = firstAttr(doc, "meta[name=description]", "content");
        }
        String ogImage = absolutize(firstAttr(doc, "meta[property=og:image]", "content"), pageUrl);
        LocalDateTime publishedTime = parsePublishedTime(
                firstAttr(doc, "meta[property=article:published_time]", "content"));

        return new ExtractedPage(true, false, pageUrl, canonical, ogTitle, ogDescription, ogImage,
                publishedTime, extractBodyText(doc));
    }

    /**
     * Readability-lite: paragraph text from the RICHEST article element (ESPN
     * puts an empty ad {@code <article>} before the story one, so "first
     * article" is a trap), falling back to main, then body. Obvious chrome
     * containers are removed first.
     */
    static String extractBodyText(Document doc) {
        Document copy = doc.clone();
        copy.select("nav, header, footer, aside, script, style, noscript, form, figure figcaption, [role=navigation], [role=banner], [role=contentinfo]").remove();

        String best = "";
        for (Element article : copy.select("article")) {
            String text = paragraphText(article);
            if (text.length() > best.length()) {
                best = text;
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        Element main = copy.selectFirst("main");
        if (main != null) {
            String text = paragraphText(main);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return copy.body() != null ? paragraphText(copy.body()) : "";
    }

    private static String paragraphText(Element container) {
        StringBuilder sb = new StringBuilder();
        for (Element p : container.select("p")) {
            String text = p.text().strip();
            if (text.length() < 30) {
                continue; // bylines, timestamps, share prompts
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /** Trims a snippet at a sentence boundary within maxChars (§ open question 10). */
    public static String trimSnippet(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String stripped = Jsoup.parse(text).text().strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() <= maxChars) {
            return stripped;
        }
        String head = stripped.substring(0, maxChars);
        int lastSentenceEnd = Math.max(head.lastIndexOf(". "),
                Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (lastSentenceEnd > maxChars / 3) {
            return head.substring(0, lastSentenceEnd + 1);
        }
        int lastSpace = head.lastIndexOf(' ');
        return (lastSpace > 0 ? head.substring(0, lastSpace) : head) + "…";
    }

    private static String firstAttr(Document doc, String css, String attr) {
        Element el = doc.selectFirst(css);
        if (el == null) {
            return null;
        }
        String value = el.attr(attr).strip();
        return value.isEmpty() ? null : value;
    }

    private static String absolutize(String url, String pageUrl) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.isAbsolute()) {
                return url;
            }
            return URI.create(pageUrl).resolve(uri).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDateTime parsePublishedTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
