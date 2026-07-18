package com.yotto.basketball.news;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsHttpClientTest {

    @Test
    void parsesWildcardGroupOnly() {
        String robots = """
                User-agent: Googlebot
                Disallow: /google-only/

                User-agent: *
                Disallow: /admin/
                Disallow: /private/
                Allow: /admin/public

                User-agent: OtherBot
                Disallow: /other/
                """;
        List<String> disallows = NewsHttpClient.parseDisallows(robots);
        assertTrue(disallows.contains("/admin/"));
        assertTrue(disallows.stream().anyMatch(d -> d.startsWith("/private/")));
        assertTrue(disallows.stream().noneMatch(d -> d.contains("google-only")));
        assertTrue(disallows.stream().noneMatch(d -> d.contains("/other/")));
    }

    @Test
    void emptyDisallowMeansAllowAll() {
        String robots = """
                User-agent: *
                Disallow:
                """;
        assertEquals(List.of(), NewsHttpClient.parseDisallows(robots));
    }

    @Test
    void status202IsNotSuccess() {
        // Akamai bot challenges answer with 202 + HTML; that must read as failure
        var challenged = new NewsHttpClient.FetchResult(202, "https://espn.com/x",
                "text/html", "<!DOCTYPE html>".getBytes(), null, null);
        assertFalse(challenged.isSuccess());
        var ok = new NewsHttpClient.FetchResult(200, "https://espn.com/x",
                "text/xml", "<rss/>".getBytes(), null, null);
        assertTrue(ok.isSuccess());
    }

    @Test
    void ssrfGuardBlocksInternalAddresses() throws Exception {
        // loopback
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("127.0.0.1")));
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("::1")));
        // link-local — includes the cloud metadata endpoint
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("169.254.169.254")));
        // private ranges (compose-internal services live here)
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("10.0.0.1")));
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("172.16.0.1")));
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("192.168.1.1")));
        // any-local and IPv6 unique-local
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("0.0.0.0")));
        assertTrue(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("fd12:3456::1")));
        // ordinary public addresses pass
        assertFalse(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("8.8.8.8")));
        assertFalse(NewsHttpClient.isBlockedAddress(java.net.InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void commentsAndBlankLinesIgnored() {
        String robots = """
                # full line comment
                User-agent: * # trailing comment
                Disallow: /x/ # another
                """;
        List<String> disallows = NewsHttpClient.parseDisallows(robots);
        assertEquals(List.of("/x/"), disallows);
    }
}
