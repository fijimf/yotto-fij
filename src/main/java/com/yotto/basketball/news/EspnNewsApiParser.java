package com.yotto.basketball.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Component
public class EspnNewsApiParser {

    private static final Logger log = LoggerFactory.getLogger(EspnNewsApiParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            String link = article.path("links").path("web").path("href").asText(null);
            String headline = article.path("headline").asText(null);
            if (link == null || link.isBlank() || headline == null || headline.isBlank()
                    || !seenLinks.add(link)) {
                continue;
            }
            String description = article.path("description").asText(null);
            String image = firstImageUrl(article.path("images"));
            items.add(new FeedItem(link, headline.strip(), description,
                    parseInstant(article.path("published").asText(null)),
                    parseInstant(article.path("lastModified").asText(null)),
                    image));
        }
        return items;
    }

    private static String firstImageUrl(JsonNode images) {
        if (!images.isArray()) {
            return null;
        }
        for (JsonNode image : images) {
            String url = image.path("url").asText(null);
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
