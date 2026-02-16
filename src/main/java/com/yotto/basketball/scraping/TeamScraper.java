package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamScraper {

    private static final Logger log = LoggerFactory.getLogger(TeamScraper.class);

    private final EspnApiClient espnApiClient;
    private final TeamRepository teamRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public TeamScraper(EspnApiClient espnApiClient, TeamRepository teamRepository,
                       ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.teamRepository = teamRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch scrape(int seasonYear) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.TEAMS);
        batch = scrapeBatchRepository.save(batch);

        try {
            JsonNode root = espnApiClient.fetchTeams();
            JsonNode teams = root.path("sports").path(0).path("leagues").path(0).path("teams");

            for (JsonNode wrapper : teams) {
                JsonNode teamNode = wrapper.path("team");
                upsertTeam(teamNode, true, batch);
            }

            batch.complete();
            log.info("Team scrape completed: {} created, {} updated", batch.getRecordsCreated(), batch.getRecordsUpdated());
        } catch (Exception e) {
            log.error("Team scrape failed", e);
            batch.fail(e.getMessage());
        }

        return scrapeBatchRepository.save(batch);
    }

    @Transactional
    public Team fetchAndSaveUnknownTeam(String espnId) {
        Team existing = teamRepository.findByEspnId(espnId).orElse(null);
        if (existing != null) {
            return existing;
        }

        log.info("Fetching unknown team with ESPN ID: {}", espnId);
        JsonNode root = espnApiClient.fetchSingleTeam(espnId);
        JsonNode teamNode = root.path("team");

        // Single-team endpoint may have team data at root level
        if (teamNode.isMissingNode()) {
            teamNode = root;
        }

        Team team = new Team();
        applyTeamFields(team, teamNode);
        team.setActive(false);
        return teamRepository.save(team);
    }

    private void upsertTeam(JsonNode teamNode, boolean isActive, ScrapeBatch batch) {
        String espnId = teamNode.path("id").asText();

        Team existing = teamRepository.findByEspnId(espnId).orElse(null);
        if (existing != null) {
            applyTeamFields(existing, teamNode);
            existing.setActive(isActive);
            teamRepository.save(existing);
            if (batch != null) batch.incrementUpdated();
        } else {
            Team team = new Team();
            applyTeamFields(team, teamNode);
            team.setActive(isActive);
            teamRepository.save(team);
            if (batch != null) batch.incrementCreated();
        }
    }

    private void applyTeamFields(Team team, JsonNode node) {
        team.setEspnId(node.path("id").asText());
        team.setName(node.path("location").asText(node.path("displayName").asText("Unknown")));
        team.setMascot(node.path("name").asText(null));
        team.setNickname(node.path("nickname").asText(null));
        team.setAbbreviation(node.path("abbreviation").asText(null));
        team.setSlug(node.path("slug").asText(null));
        team.setColor(node.path("color").asText(null));
        team.setAlternateColor(node.path("alternateColor").asText(null));

        JsonNode logos = node.path("logos");
        if (logos.isArray() && !logos.isEmpty()) {
            team.setLogoUrl(logos.path(0).path("href").asText(null));
        }
    }
}
