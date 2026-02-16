package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConferenceScraperTest extends BaseIntegrationTest {

    @Autowired
    private ConferenceScraper conferenceScraper;

    @Autowired
    private ConferenceRepository conferenceRepository;

    @Autowired
    private ScrapeBatchRepository scrapeBatchRepository;

    @MockBean
    private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        conferenceRepository.deleteAll();
    }

    @Test
    void scrapeConferences_createsNewConferences() throws Exception {
        String json = """
                {
                  "conferences": [
                    { "groupId": "50", "name": "NCAA Division I", "shortName": "NCAA" },
                    { "groupId": "2", "name": "Atlantic Coast Conference", "shortName": "ACC", "logo": "https://example.com/acc.png" },
                    { "groupId": "8", "name": "Big 12 Conference", "shortName": "Big 12" }
                  ]
                }
                """;
        JsonNode root = mapper.readTree(json);
        when(espnApiClient.fetchConferences()).thenReturn(root);

        ScrapeBatch batch = conferenceScraper.scrape(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isEqualTo(2);

        Optional<Conference> acc = conferenceRepository.findByEspnId("2");
        assertThat(acc).isPresent();
        assertThat(acc.get().getName()).isEqualTo("Atlantic Coast Conference");
        assertThat(acc.get().getAbbreviation()).isEqualTo("ACC");
        assertThat(acc.get().getDivision()).isEqualTo("Division I");
    }

    @Test
    void scrapeConferences_updatesExistingConference() throws Exception {
        // Pre-insert a conference
        Conference existing = new Conference();
        existing.setEspnId("2");
        existing.setName("Old ACC Name");
        existing.setAbbreviation("ACC");
        existing.setDivision("Division I");
        conferenceRepository.save(existing);

        String json = """
                {
                  "conferences": [
                    { "groupId": "50", "name": "NCAA Division I", "shortName": "NCAA" },
                    { "groupId": "2", "name": "Atlantic Coast Conference", "shortName": "ACC" }
                  ]
                }
                """;
        when(espnApiClient.fetchConferences()).thenReturn(mapper.readTree(json));

        ScrapeBatch batch = conferenceScraper.scrape(2025);

        assertThat(batch.getRecordsUpdated()).isEqualTo(1);
        assertThat(batch.getRecordsCreated()).isEqualTo(0);

        Conference updated = conferenceRepository.findByEspnId("2").get();
        assertThat(updated.getName()).isEqualTo("Atlantic Coast Conference");
    }
}
