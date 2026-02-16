package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameScraperTest extends BaseIntegrationTest {

    @Autowired
    private GameScraper gameScraper;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private SeasonRepository seasonRepository;

    @MockBean
    private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        if (teamRepository.findByEspnId("333").isEmpty()) {
            Team home = new Team();
            home.setEspnId("333");
            home.setName("Alabama");
            home.setActive(true);
            teamRepository.save(home);
        }

        if (teamRepository.findByEspnId("2").isEmpty()) {
            Team away = new Team();
            away.setEspnId("2");
            away.setName("Auburn");
            away.setActive(true);
            teamRepository.save(away);
        }

        if (seasonRepository.findByYear(2025).isEmpty()) {
            Season season = new Season();
            season.setYear(2025);
            season.setStartDate(LocalDate.of(2024, 11, 1));
            season.setEndDate(LocalDate.of(2025, 4, 30));
            seasonRepository.save(season);
        }
    }

    @Test
    void scrapeGame_createsGameFromScoreboard() throws Exception {
        String validJson = """
                {
                  "sports": [{
                    "leagues": [{
                      "events": [
                        {
                          "id": "401708390",
                          "date": "2025-02-15T21:00:00Z",
                          "location": "Coleman Coliseum",
                          "neutralSite": false,
                          "season": 2025,
                          "competitors": [
                            { "id": "333", "homeAway": "home", "score": "85" },
                            { "id": "2", "homeAway": "away", "score": "78" }
                          ],
                          "fullStatus": {
                            "type": { "name": "STATUS_FINAL", "state": "post" }
                          }
                        }
                      ]
                    }]
                  }]
                }
                """;

        String emptyJson = """
                { "sports": [{ "leagues": [{ "events": [] }] }] }
                """;

        when(espnApiClient.fetchScoreboard(any(LocalDate.class)))
                .thenReturn(mapper.readTree(emptyJson));
        when(espnApiClient.fetchScoreboard(LocalDate.of(2025, 2, 15)))
                .thenReturn(mapper.readTree(validJson));

        ScrapeBatch batch = gameScraper.scrapeFullSeason(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isGreaterThanOrEqualTo(1);

        Optional<Game> game = gameRepository.findByEspnId("401708390");
        assertThat(game).isPresent();
        assertThat(game.get().getHomeScore()).isEqualTo(85);
        assertThat(game.get().getAwayScore()).isEqualTo(78);
        assertThat(game.get().getStatus()).isEqualTo(Game.GameStatus.FINAL);
        assertThat(game.get().getVenue()).isEqualTo("Coleman Coliseum");
    }

    @Test
    void scrapeGame_upsertsOnRescrape() throws Exception {
        String scheduledJson = """
                {
                  "sports": [{
                    "leagues": [{
                      "events": [
                        {
                          "id": "401708391",
                          "date": "2025-02-16T19:00:00Z",
                          "location": "Arena",
                          "neutralSite": false,
                          "season": 2025,
                          "competitors": [
                            { "id": "333", "homeAway": "home", "score": "" },
                            { "id": "2", "homeAway": "away", "score": "" }
                          ],
                          "fullStatus": {
                            "type": { "name": "STATUS_SCHEDULED", "state": "pre" }
                          }
                        }
                      ]
                    }]
                  }]
                }
                """;

        String finalJson = """
                {
                  "sports": [{
                    "leagues": [{
                      "events": [
                        {
                          "id": "401708391",
                          "date": "2025-02-16T19:00:00Z",
                          "location": "Arena",
                          "neutralSite": false,
                          "season": 2025,
                          "competitors": [
                            { "id": "333", "homeAway": "home", "score": "72" },
                            { "id": "2", "homeAway": "away", "score": "68" }
                          ],
                          "fullStatus": {
                            "type": { "name": "STATUS_FINAL", "state": "post" }
                          }
                        }
                      ]
                    }]
                  }]
                }
                """;

        String emptyJson = """
                { "sports": [{ "leagues": [{ "events": [] }] }] }
                """;

        // First scrape: game is scheduled
        when(espnApiClient.fetchScoreboard(any(LocalDate.class)))
                .thenReturn(mapper.readTree(emptyJson));
        when(espnApiClient.fetchScoreboard(LocalDate.of(2025, 2, 16)))
                .thenReturn(mapper.readTree(scheduledJson));

        gameScraper.scrapeFullSeason(2025);
        Game scheduled = gameRepository.findByEspnId("401708391").get();
        assertThat(scheduled.getStatus()).isEqualTo(Game.GameStatus.SCHEDULED);

        // Second scrape: game is final (upsert, not duplicate)
        when(espnApiClient.fetchScoreboard(LocalDate.of(2025, 2, 16)))
                .thenReturn(mapper.readTree(finalJson));

        gameScraper.scrapeFullSeason(2025);
        Game finalGame = gameRepository.findByEspnId("401708391").get();
        assertThat(finalGame.getStatus()).isEqualTo(Game.GameStatus.FINAL);
        assertThat(finalGame.getHomeScore()).isEqualTo(72);

        // Verify no duplicates
        long count = gameRepository.findAll().stream()
                .filter(g -> "401708391".equals(g.getEspnId()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
