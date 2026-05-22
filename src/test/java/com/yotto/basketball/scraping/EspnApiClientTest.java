package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.config.ScrapingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit test — exercises URL construction, JSON deserialization, error
 * propagation, and rate-limiting using MockRestServiceServer bound to a
 * test-owned RestClient.Builder. No Spring context required.
 */
class EspnApiClientTest {

    private MockRestServiceServer mockServer;
    private EspnApiClient client;
    private ScrapingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ScrapingProperties();
        properties.setBaseDelayMs(0);
        properties.setJitterMs(0);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new EspnApiClient(properties, builder);
    }

    // ── URL construction ──────────────────────────────────────────────────────

    @Test
    void fetchTeams_targetsTeamsUrlAndReturnsParsedJson() {
        mockServer.expect(requestTo(
                "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=500"))
                .andRespond(withSuccess("{\"sports\":[]}", MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchTeams();

        assertThat(result.has("sports")).isTrue();
        mockServer.verify();
    }

    @Test
    void fetchSingleTeam_substitutesTeamId() {
        mockServer.expect(requestTo(
                "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams/333"))
                .andRespond(withSuccess("{\"team\":{\"id\":\"333\"}}", MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchSingleTeam("333");

        assertThat(result.path("team").path("id").asText()).isEqualTo("333");
        mockServer.verify();
    }

    @Test
    void fetchConferences_targetsConferencesUrl() {
        mockServer.expect(requestTo(
                "https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences"))
                .andRespond(withSuccess("{\"conferences\":[]}", MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchConferences();

        assertThat(result.has("conferences")).isTrue();
        mockServer.verify();
    }

    @Test
    void fetchStandings_substitutesYearIntoQueryString() {
        mockServer.expect(requestTo(
                "https://site.api.espn.com/apis/v2/sports/basketball/mens-college-basketball/standings?season=2025"))
                .andRespond(withSuccess("{\"children\":[]}", MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchStandings(2025);

        assertThat(result.has("children")).isTrue();
        mockServer.verify();
    }

    @Test
    void fetchScoreboard_formatsDateAsYYYYMMDD() {
        mockServer.expect(requestTo(
                "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=basketball" +
                "&league=mens-college-basketball&limit=200&groups=50&dates=20250215"))
                .andRespond(withSuccess("{\"sports\":[]}", MediaType.APPLICATION_JSON));

        client.fetchScoreboard(LocalDate.of(2025, 2, 15));

        mockServer.verify();
    }

    @Test
    void fetchGameOdds_substitutesGameIdInBothPathSegments() {
        mockServer.expect(requestTo(
                "https://sports.core.api.espn.com/v2/sports/basketball/leagues/" +
                "mens-college-basketball/events/401999999/competitions/401999999/odds"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        client.fetchGameOdds("401999999");

        mockServer.verify();
    }

    @Test
    void fetchGameSummary_substitutesGameId() {
        mockServer.expect(requestTo(
                "https://site.api.espn.com/apis/site/v2/sports/basketball/" +
                "mens-college-basketball/summary?event=401999999"))
                .andRespond(withSuccess("{\"boxscore\":{}}", MediaType.APPLICATION_JSON));

        client.fetchGameSummary("401999999");

        mockServer.verify();
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void httpError_propagatesException() {
        mockServer.expect(requestTo(
                "https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchConferences())
                .isInstanceOf(RestClientResponseException.class);
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    void rateLimit_delaysAtLeastBaseDelayBetweenCalls() {
        properties.setBaseDelayMs(60);
        properties.setJitterMs(0);

        mockServer.expect(requestTo(
                "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=500"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        long start = System.nanoTime();
        client.fetchTeams();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Allow plenty of slack for jitter/CI noise; just ensure the sleep actually happened.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void rateLimit_appliedEvenWhenRequestThrows() {
        properties.setBaseDelayMs(60);

        mockServer.expect(requestTo(
                "https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences"))
                .andRespond(withServerError());

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.fetchConferences())
                .isInstanceOf(RestClientResponseException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }
}
