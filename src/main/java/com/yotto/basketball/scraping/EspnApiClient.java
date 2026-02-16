package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.config.ScrapingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EspnApiClient {

    private static final Logger log = LoggerFactory.getLogger(EspnApiClient.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String TEAMS_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=500";
    private static final String SINGLE_TEAM_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams/{espnTeamId}";
    private static final String CONFERENCES_URL = "https://site.web.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard/conferences";
    private static final String STANDINGS_URL = "https://site.api.espn.com/apis/v2/sports/basketball/mens-college-basketball/standings?season={year}";
    private static final String SCOREBOARD_URL = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=basketball&league=mens-college-basketball&limit=200&groups=50&dates={date}";
    private static final String ODDS_URL = "https://sports.core.api.espn.com/v2/sports/basketball/leagues/mens-college-basketball/events/{gameId}/competitions/{gameId}/odds";

    private final RestClient restClient;
    private final ScrapingProperties properties;

    public EspnApiClient(ScrapingProperties properties) {
        this.restClient = RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .build();
        this.properties = properties;
    }

    public JsonNode fetchTeams() {
        return fetchWithRateLimit(TEAMS_URL);
    }

    public JsonNode fetchSingleTeam(String espnTeamId) {
        String url = SINGLE_TEAM_URL.replace("{espnTeamId}", espnTeamId);
        return fetchWithRateLimit(url);
    }

    public JsonNode fetchConferences() {
        return fetchWithRateLimit(CONFERENCES_URL);
    }

    public JsonNode fetchStandings(int seasonYear) {
        String url = STANDINGS_URL.replace("{year}", String.valueOf(seasonYear));
        return fetchWithRateLimit(url);
    }

    public JsonNode fetchScoreboard(LocalDate date) {
        String url = SCOREBOARD_URL.replace("{date}", date.format(DATE_FORMAT));
        return fetchWithRateLimit(url);
    }

    public JsonNode fetchGameOdds(String espnGameId) {
        String url = ODDS_URL.replace("{gameId}", espnGameId);
        return fetchWithRateLimit(url);
    }

    private JsonNode fetchWithRateLimit(String url) {
        log.debug("Fetching: {}", url);
        try {
            JsonNode response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);
            log.debug("Response received for: {}", url);
            return response;
        } finally {
            applyRateLimit();
        }
    }

    private void applyRateLimit() {
        int delay = properties.getBaseDelayMs();
        if (properties.getJitterMs() > 0) {
            delay += ThreadLocalRandom.current().nextInt(properties.getJitterMs());
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
