package com.yotto.basketball.news;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Canonicalizes article URLs so the same story discovered twice maps to one
 * string (docs/NEWS_MODULE.md §5.2). Redirect following and rel=canonical
 * preference happen in the fetch layer — this class is pure string logic over
 * the URL it is handed.
 */
public final class UrlCanonicalizer {

    /** Tracking params stripped by prefix match ("utm_" catches utm_source etc.). */
    private static final Set<String> TRACKING_PREFIXES = Set.of("utm_");

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "msclkid", "twclid", "igshid", "mc_cid", "mc_eid",
            "ref", "src", "source", "partner", "cmpid", "ex_cid", "cid", "ncid",
            "sr_share", "smid", "ftag", "rss", "output");

    private UrlCanonicalizer() {
    }

    /**
     * @throws IllegalArgumentException when the input is not an absolute http(s) URL
     */
    public static String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is blank");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unparseable URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Not an http(s) URL: " + url);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }

        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        int port = uri.getPort();
        String portPart = "";
        if (port != -1 && port != 80 && port != 443) {
            portPart = ":" + port;
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // Strip trailing slash except for the bare root
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = "/";
        }

        String query = normalizeQuery(uri.getRawQuery());

        return "https://" + host + portPart + path + (query.isEmpty() ? "" : "?" + query);
    }

    /** Strips tracking params, sorts the rest for stable ordering. */
    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (isTracking(decodedKey)) {
                continue;
            }
            kept.add(pair);
        }
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    private static boolean isTracking(String key) {
        if (TRACKING_PARAMS.contains(key)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
