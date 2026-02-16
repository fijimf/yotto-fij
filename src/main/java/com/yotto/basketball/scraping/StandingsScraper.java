package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class StandingsScraper {

    private static final Logger log = LoggerFactory.getLogger(StandingsScraper.class);

    private final EspnApiClient espnApiClient;
    private final TeamScraper teamScraper;
    private final ConferenceRepository conferenceRepository;
    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final SeasonStatisticsRepository statisticsRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public StandingsScraper(EspnApiClient espnApiClient, TeamScraper teamScraper,
                            ConferenceRepository conferenceRepository, TeamRepository teamRepository,
                            SeasonRepository seasonRepository, ConferenceMembershipRepository membershipRepository,
                            SeasonStatisticsRepository statisticsRepository, ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.teamScraper = teamScraper;
        this.conferenceRepository = conferenceRepository;
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.membershipRepository = membershipRepository;
        this.statisticsRepository = statisticsRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch scrape(int seasonYear) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.STANDINGS);
        batch = scrapeBatchRepository.save(batch);

        try {
            Season season = ensureSeason(seasonYear);
            JsonNode root = espnApiClient.fetchStandings(seasonYear);
            JsonNode children = root.path("children");

            for (JsonNode confChild : children) {
                String confEspnId = confChild.path("id").asText();
                Conference conference = conferenceRepository.findByEspnId(confEspnId).orElse(null);
                if (conference == null) {
                    log.warn("Unknown conference with ESPN ID: {}, skipping", confEspnId);
                    continue;
                }

                JsonNode entries = confChild.path("standings").path("entries");
                for (JsonNode entry : entries) {
                    processStandingsEntry(entry, conference, season, batch);
                }
            }

            batch.complete();
            log.info("Standings scrape completed for season {}: {} created, {} updated",
                    seasonYear, batch.getRecordsCreated(), batch.getRecordsUpdated());
        } catch (Exception e) {
            log.error("Standings scrape failed for season {}", seasonYear, e);
            batch.fail(e.getMessage());
        }

        return scrapeBatchRepository.save(batch);
    }

    private void processStandingsEntry(JsonNode entry, Conference conference, Season season, ScrapeBatch batch) {
        String teamEspnId = entry.path("team").path("id").asText();

        Team team = teamRepository.findByEspnId(teamEspnId).orElse(null);
        if (team == null) {
            team = teamScraper.fetchAndSaveUnknownTeam(teamEspnId);
        }

        // Upsert conference membership
        ConferenceMembership membership = membershipRepository
                .findByTeamIdAndSeasonId(team.getId(), season.getId()).orElse(null);
        if (membership == null) {
            membership = new ConferenceMembership();
            membership.setTeam(team);
            membership.setSeason(season);
            membership.setConference(conference);
            membershipRepository.save(membership);
            batch.incrementCreated();
        } else {
            membership.setConference(conference);
            membershipRepository.save(membership);
            batch.incrementUpdated();
        }

        // Upsert season statistics
        SeasonStatistics stats = statisticsRepository
                .findByTeamIdAndSeasonId(team.getId(), season.getId()).orElse(null);
        boolean isNew = (stats == null);
        if (isNew) {
            stats = new SeasonStatistics();
            stats.setTeam(team);
            stats.setSeason(season);
        }
        stats.setConference(conference);
        applyStats(stats, entry.path("stats"));
        statisticsRepository.save(stats);

        if (isNew) {
            batch.incrementCreated();
        } else {
            batch.incrementUpdated();
        }
    }

    private void applyStats(SeasonStatistics stats, JsonNode statsArray) {
        if (!statsArray.isArray()) return;

        for (JsonNode stat : statsArray) {
            String type = stat.path("type").asText();
            String name = stat.path("name").asText();
            int value = stat.path("value").asInt(0);

            switch (type) {
                case "total" -> {
                    if ("wins".equals(name)) stats.setWins(value);
                    else if ("losses".equals(name)) stats.setLosses(value);
                    else if ("pointsFor".equals(name)) stats.setPointsFor(value);
                    else if ("pointsAgainst".equals(name)) stats.setPointsAgainst(value);
                    else if ("streak".equals(name)) stats.setStreak(value);
                    else if ("playoffSeed".equals(name)) stats.setConferenceStanding(value);
                }
                case "home" -> {
                    if ("wins".equals(name)) stats.setHomeWins(value);
                    else if ("losses".equals(name)) stats.setHomeLosses(value);
                }
                case "road", "away" -> {
                    if ("wins".equals(name)) stats.setRoadWins(value);
                    else if ("losses".equals(name)) stats.setRoadLosses(value);
                }
                case "vsconf", "vsConf" -> {
                    if ("wins".equals(name)) stats.setConferenceWins(value);
                    else if ("losses".equals(name)) stats.setConferenceLosses(value);
                }
            }
        }
    }

    private Season ensureSeason(int year) {
        return seasonRepository.findByYear(year).orElseGet(() -> {
            Season season = new Season();
            season.setYear(year);
            season.setStartDate(LocalDate.of(year - 1, 11, 1));
            season.setEndDate(LocalDate.of(year, 4, 30));
            season.setDescription(year + " NCAA Men's Basketball Season");
            return seasonRepository.save(season);
        });
    }
}
