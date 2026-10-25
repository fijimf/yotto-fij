package com.yotto.basketball.news;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses ESPN's site-API news JSON
 * (site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/news)
 * into {@link FeedItem}s. Exists because ESPN's classic RSS endpoint serves a
 * bot-challenge page to server-side Java clients (Akamai TLS fingerprinting),
 * while this API — the same family the game scrapers use — stays open.
 * Since ~2026-07-27 the API also 403s unrecognized User-Agents; the fetch UA
 * must stay a recognized product token (see {@code NewsProperties.userAgent}).
 */
@Component
public class EspnNewsApiParser {

    private static final Logger log = LoggerFactory.getLogger(EspnNewsApiParser.class);

    private final ObjectMapper objectMapper = new JsonMapper();

    /**
     * @throws FeedPoller.FeedParseException when the bytes aren't the expected JSON shape
     */
    public List<FeedItem> parse(byte[] jsonBytes, String sourceUrl) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBytes);
        } catch (Exception e) {
            throw new FeedPoller.FeedParseException(
                    "Unparseable ESPN news API response from " + sourceUrl + ": " + e.getMessage(), e);
        }
        JsonNode articles = root.path("articles");
        if (!articles.isArray()) {
            throw new FeedPoller.FeedParseException(
                    "ESPN news API response from " + sourceUrl + " has no articles array", null);
        }

        List<FeedItem> items = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        for (JsonNode article : articles) {
            String link = article.path("links").path("web").path("href").asString(null);
            String headline = article.path("headline").asString(null);
            if (link == null || link.isBlank() || headline == null || headline.isBlank()
                    || !seenLinks.add(link)) {
                continue;
            }
            String description = article.path("description").asString(null);
            String image = firstImageUrl(article.path("images"));
            items.add(new FeedItem(link, headline.strip(), description,
                    parseInstant(article.path("published").asString(null)),
                    parseInstant(article.path("lastModified").asString(null)),
                    image));
        }
        return items;
    }

    private static String firstImageUrl(JsonNode images) {
        if (!images.isArray()) {
            return null;
        }
        for (JsonNode image : images) {
            String url = image.path("url").asString(null);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    private static LocalDateTime parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.debug("Unparseable ESPN API date {}", value);
            return null;
        }
    }
}
