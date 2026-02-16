package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TeamScraperTest extends BaseIntegrationTest {

    @Autowired
    private TeamScraper teamScraper;

    @Autowired
    private TeamRepository teamRepository;

    @MockBean
    private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void scrapeTeams_createsNewTeams() throws Exception {
        String json = """
                {
                  "sports": [{
                    "leagues": [{
                      "teams": [
                        {
                          "team": {
                            "id": "333",
                            "location": "Alabama",
                            "name": "Crimson Tide",
                            "nickname": "Alabama",
                            "abbreviation": "ALA",
                            "slug": "alabama-crimson-tide",
                            "color": "9e1632",
                            "alternateColor": "ffffff",
                            "isActive": true,
                            "logos": [{ "href": "https://example.com/alabama.png" }]
                          }
                        }
                      ]
                    }]
                  }]
                }
                """;
        when(espnApiClient.fetchTeams()).thenReturn(mapper.readTree(json));

        ScrapeBatch batch = teamScraper.scrape(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isEqualTo(1);

        Optional<Team> team = teamRepository.findByEspnId("333");
        assertThat(team).isPresent();
        assertThat(team.get().getName()).isEqualTo("Alabama");
        assertThat(team.get().getMascot()).isEqualTo("Crimson Tide");
        assertThat(team.get().getAbbreviation()).isEqualTo("ALA");
        assertThat(team.get().getSlug()).isEqualTo("alabama-crimson-tide");
        assertThat(team.get().getActive()).isTrue();
    }

    @Test
    void fetchAndSaveUnknownTeam_createsInactiveTeam() throws Exception {
        String json = """
                {
                  "team": {
                    "id": "999",
                    "location": "Old School",
                    "name": "Warriors",
                    "nickname": "Old School",
                    "abbreviation": "OS",
                    "slug": "old-school-warriors",
                    "color": "000000",
                    "alternateColor": "ffffff",
                    "isActive": false,
                    "logos": []
                  }
                }
                """;
        when(espnApiClient.fetchSingleTeam(eq("999"))).thenReturn(mapper.readTree(json));

        Team team = teamScraper.fetchAndSaveUnknownTeam("999");

        assertThat(team.getEspnId()).isEqualTo("999");
        assertThat(team.getActive()).isFalse();
        assertThat(team.getName()).isEqualTo("Old School");
    }

    @Test
    void scrapeTeams_idempotent() throws Exception {
        String json = """
                {
                  "sports": [{
                    "leagues": [{
                      "teams": [
                        {
                          "team": {
                            "id": "100",
                            "location": "TestU",
                            "name": "Bears",
                            "nickname": "TestU",
                            "abbreviation": "TU",
                            "slug": "testu-bears",
                            "color": "ff0000",
                            "alternateColor": "0000ff",
                            "isActive": true,
                            "logos": []
                          }
                        }
                      ]
                    }]
                  }]
                }
                """;
        when(espnApiClient.fetchTeams()).thenReturn(mapper.readTree(json));

        // First scrape
        teamScraper.scrape(2025);
        long countAfterFirst = teamRepository.findByEspnId("100").stream().count();

        // Second scrape (should update, not duplicate)
        ScrapeBatch batch2 = teamScraper.scrape(2025);
        long countAfterSecond = teamRepository.findByEspnId("100").stream().count();

        assertThat(countAfterFirst).isEqualTo(1);
        assertThat(countAfterSecond).isEqualTo(1);
        assertThat(batch2.getRecordsUpdated()).isEqualTo(1);
        assertThat(batch2.getRecordsCreated()).isEqualTo(0);
    }
}
