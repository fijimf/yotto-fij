package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP layer for the news module: per-host rate limiting, honest User-Agent,
 * bounded manual redirect following (so callers see the final URL), response
 * size caps, conditional GET for feeds, and an optional robots.txt check
 * (off by default — see docs/NEWS_MODULE.md §5.9 / open question 3).
 *
 * All feed and page fetches go through this class so tests can mock it.
 */
@Component
public class NewsHttpClient {

    private static final Logger log = LoggerFactory.getLogger(NewsHttpClient.class);

    private final NewsProperties properties;
    private final HttpClient httpClient;
    private final Map<String, Long> lastRequestByHost = new ConcurrentHashMap<>();
    private final Map<String, List<String>> robotsDisallowsByHost = new ConcurrentHashMap<>();

    /**
     * @param status       HTTP status of the final response (0 on transport failure)
     * @param finalUrl     URL after redirects; equals the request URL when none occurred
     * @param contentType  lowercased Content-Type header, or null
     * @param body         response bytes; null on 304, robots-denied, transport failure, or size/type rejection
     * @param etag         ETag header of the final response, feeds only
     * @param lastModified Last-Modified header of the final response, feeds only
     */
    public record FetchResult(int status, String finalUrl, String contentType, byte[] body,
                              String etag, String lastModified) {

        public boolean isSuccess() {
            // Strictly 200: bot managers (ESPN/Akamai) answer challenged requests
            // with "202 Accepted" + an HTML page, which must never count as content.
            return status == 200 && body != null;
        }

        public boolean isNotModified() {
            return status == 304;
        }
    }

    public NewsHttpClient(NewsProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    /** Conditional GET of a feed. Pass the stored ETag/Last-Modified (either may be null). */
    public FetchResult fetchFeed(String url, String etag, String lastModified) {
        return fetch(url, etag, lastModified, false);
    }

    /** Fetch an article page, following redirects. Honors the robots switch. */
    public FetchResult fetchPage(String url) {
        return fetch(url, null, null, properties.isRespectRobots());
    }

    /** Fetch raw bytes (image download); same redirect/rate-limit rules, larger caller-checked bodies. */
    public FetchResult fetchBytes(String url, int maxBytes) {
        return doFetch(url, null, null, false, maxBytes, null);
    }

    private FetchResult fetch(String url, String etag, String lastModified, boolean checkRobots) {
        return doFetch(url, etag, lastModified, checkRobots, properties.getMaxResponseBytes(), null);
    }

    private FetchResult doFetch(String url, String etag, String lastModified, boolean checkRobots,
                                int maxBytes, String requiredContentTypePrefix) {
        String current = url;
        for (int hop = 0; hop <= properties.getMaxRedirects(); hop++) {
            URI uri;
            try {
                uri = URI.create(current);
            } catch (IllegalArgumentException e) {
                return failure(current);
            }
            if (uri.getHost() == null) {
                return failure(current);
            }
            // SSRF guard: feed links and redirect targets are third-party content,
            // so every hop must be a public http(s) address — never loopback,
            // link-local (cloud metadata), or private ranges (internal services).
            if (!isSafeTarget(uri)) {
                log.debug("Blocked non-public fetch target {}", current);
                return failure(current);
            }
            if (checkRobots && isDisallowedByRobots(uri)) {
                log.debug("robots.txt disallows {}", current);
                return failure(current);
            }
            rateLimit(uri.getHost());

            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("User-Agent", properties.userAgentFor(uri.getHost()))
                    .header("Accept", "text/html, application/xhtml+xml, application/xml, application/rss+xml, */*")
                    .GET();
            if (etag != null) {
                request.header("If-None-Match", etag);
            }
            if (lastModified != null) {
                request.header("If-Modified-Since", lastModified);
            }

            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                log.debug("Fetch failed for {}: {}", current, e.toString());
                return failure(current);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(current);
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return new FetchResult(status, current, null, null, null, null);
                }
                current = uri.resolve(location).toString();
                continue;
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .map(ct -> ct.toLowerCase(Locale.ROOT)).orElse(null);
            String responseEtag = response.headers().firstValue("ETag").orElse(null);
            String responseLastModified = response.headers().firstValue("Last-Modified").orElse(null);

            if (status == 304) {
                return new FetchResult(304, current, contentType, null, responseEtag, responseLastModified);
            }
            byte[] body = response.body();
            if (body != null && body.length > maxBytes) {
                log.debug("Response too large ({} bytes) for {}", body.length, current);
                return new FetchResult(status, current, contentType, null, responseEtag, responseLastModified);
            }
            return new FetchResult(status, current, contentType, body, responseEtag, responseLastModified);
        }
        log.debug("Too many redirects for {}", url);
        return failure(url);
    }

    private static FetchResult failure(String url) {
        return new FetchResult(0, url, null, null, null, null);
    }

    private boolean isSafeTarget(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (properties.isAllowPrivateAddresses()) {
            return true; // dev/test escape hatch only
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isBlockedAddress(address)) {
                    return false;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
    }

    /** Package-visible for tests: rejects loopback, link-local, private, any-local, and multicast. */
    static boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    /** fc00::/7 (unique-local IPv6) isn't covered by isSiteLocalAddress. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private void rateLimit(String host) {
        long delay = properties.getBaseDelayMs()
                + (properties.getJitterMs() > 0 ? ThreadLocalRandom.current().nextLong(properties.getJitterMs()) : 0);
        Long last = lastRequestByHost.get(host);
        if (last != null) {
            long elapsed = System.currentTimeMillis() - last;
            if (elapsed < delay) {
                try {
                    Thread.sleep(delay - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastRequestByHost.put(host, System.currentTimeMillis());
    }

    /** Minimal robots.txt support: Disallow prefixes from the "User-agent: *" group. Cached per host. */
    private boolean isDisallowedByRobots(URI uri) {
        List<String> disallows = robotsDisallowsByHost.computeIfAbsent(uri.getHost(), host -> {
            try {
                rateLimit(host);
                HttpRequest request = HttpRequest.newBuilder(URI.create(uri.getScheme() + "://" + host + "/robots.txt"))
                        .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                        .header("User-Agent", properties.userAgentFor(host))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return List.of();
                }
                return parseDisallows(response.body());
            } catch (IOException e) {
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        return disallows.stream().anyMatch(path::startsWith);
    }

    static List<String> parseDisallows(String robotsTxt) {
        List<String> disallows = new ArrayList<>();
        boolean inWildcardGroup = false;
        for (String rawLine : robotsTxt.split("\n")) {
            String line = rawLine.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                inWildcardGroup = line.substring("user-agent:".length()).strip().equals("*");
            } else if (inWildcardGroup && lower.startsWith("disallow:")) {
                String path = line.substring("disallow:".length()).strip();
                if (!path.isEmpty()) {
                    disallows.add(path);
                }
            }
        }
        return disallows;
    }
}
