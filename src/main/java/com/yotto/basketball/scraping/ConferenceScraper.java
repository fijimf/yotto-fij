package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConferenceScraper {

    private static final Logger log = LoggerFactory.getLogger(ConferenceScraper.class);

    private final EspnApiClient espnApiClient;
    private final ConferenceRepository conferenceRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public ConferenceScraper(EspnApiClient espnApiClient, ConferenceRepository conferenceRepository,
                             ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.conferenceRepository = conferenceRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch scrape(int seasonYear) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.CONFERENCES);
        batch = scrapeBatchRepository.save(batch);

        try {
            JsonNode root = espnApiClient.fetchConferences();
            JsonNode conferences = root.path("conferences");

            for (JsonNode conf : conferences) {
                String groupId = conf.path("groupId").asText();

                // Skip the parent "NCAA Division I" group (groupId "50")
                if ("50".equals(groupId)) {
                    continue;
                }

                String name = conf.path("name").asText();
                String shortName = conf.path("shortName").asText();
                String logo = conf.path("logo").asText(null);

                Conference existing = conferenceRepository.findByEspnId(groupId).orElse(null);
                if (existing != null) {
                    existing.setName(name);
                    existing.setAbbreviation(shortName);
                    existing.setDivision("Division I");
                    existing.setLogoUrl(logo);
                    conferenceRepository.save(existing);
                    batch.incrementUpdated();
                } else {
                    Conference conference = new Conference();
                    conference.setEspnId(groupId);
                    conference.setName(name);
                    conference.setAbbreviation(shortName);
                    conference.setDivision("Division I");
                    conference.setLogoUrl(logo);
                    conferenceRepository.save(conference);
                    batch.incrementCreated();
                }
            }

            batch.complete();
            log.info("Conference scrape completed: {} created, {} updated", batch.getRecordsCreated(), batch.getRecordsUpdated());
        } catch (Exception e) {
            log.error("Conference scrape failed", e);
            batch.fail(e.getMessage());
        }

        return scrapeBatchRepository.save(batch);
    }
}
