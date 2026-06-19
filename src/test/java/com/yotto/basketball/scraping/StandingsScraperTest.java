package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StandingsScraperTest extends BaseIntegrationTest {

    @Autowired private StandingsScraper scraper;
    @Autowired private ConferenceRepository conferenceRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private SeasonRepository seasonRepo;
    @Autowired private ConferenceMembershipRepository membershipRepo;
    @Autowired private SeasonStatisticsRepository statsRepo;
    @Autowired private ScrapeBatchRepository batchRepo;

    @Autowired private SeasonPopulationStatRepository popStatRepo;
    @Autowired private TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired private TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired private PowerModelParamSnapshotRepository paramRepo;
    @Autowired private BettingOddsRepository oddsRepo;
    @Autowired private GameRepository gameRepo;

    @MockBean private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {

        Conference acc = new Conference();
        acc.setName("ACC");
        acc.setAbbreviation("ACC");
        acc.setEspnId("2");
        conferenceRepo.save(acc);

        Team alabama = new Team();
        alabama.setEspnId("333");
        alabama.setName("Alabama");
        alabama.setActive(true);
        teamRepo.save(alabama);

        Team auburn = new Team();
        auburn.setEspnId("2");
        auburn.setName("Auburn");
        auburn.setActive(true);
        teamRepo.save(auburn);
    }

    private static final String STANDINGS_JSON = """
            {
              "children": [
                {
                  "id": "2",
                  "name": "Atlantic Coast Conference",
                  "standings": {
                    "entries": [
                      {
                        "team": { "id": "333" },
                        "stats": [
                          { "type": "wins", "value": 20 },
                          { "type": "losses", "value": 5 },
                          { "type": "pointsfor", "value": 2050 },
                          { "type": "pointsagainst", "value": 1700 },
                          { "type": "streak", "value": 4 },
                          { "type": "playoffseed", "value": 1 },
                          { "type": "home_wins", "value": 12 },
                          { "type": "home_losses", "value": 1 },
                          { "type": "road_wins", "value": 7 },
                          { "type": "road_losses", "value": 4 },
                          { "type": "vsconf_wins", "value": 14 },
                          { "type": "vsconf_losses", "value": 4 }
                        ]
                      },
                      {
                        "team": { "id": "2" },
                        "stats": [
                          { "type": "wins", "value": 15 },
                          { "type": "losses", "value": 10 }
                        ]
                      }
                    ]
                  }
                },
                {
                  "id": "99999",
                  "name": "Unknown Conference",
                  "standings": {
                    "entries": [
                      {
                        "team": { "id": "8888" },
                        "stats": [
                          { "type": "wins", "value": 1 },
                          { "type": "losses", "value": 1 }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            """;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void scrape_createsMembershipsAndSeasonStatisticsForKnownConferences() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        ScrapeBatch batch = scraper.scrape(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);

        // Season auto-created
        Season season = seasonRepo.findByYear(2025).orElseThrow();
        assertThat(season.getStartDate()).isEqualTo(LocalDate.of(2024, 11, 1));
        assertThat(season.getEndDate()).isEqualTo(LocalDate.of(2025, 4, 30));

        // Both ACC teams have memberships
        Long alabamaId = teamRepo.findByEspnId("333").orElseThrow().getId();
        Long auburnId  = teamRepo.findByEspnId("2").orElseThrow().getId();
        assertThat(membershipRepo.findByTeamIdAndSeasonId(alabamaId, season.getId())).isPresent();
        assertThat(membershipRepo.findByTeamIdAndSeasonId(auburnId, season.getId())).isPresent();
    }

    @Test
    void scrape_parsesAllSeasonStatisticsFields() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        scraper.scrape(2025);

        Long alabamaId = teamRepo.findByEspnId("333").orElseThrow().getId();
        Long seasonId  = seasonRepo.findByYear(2025).orElseThrow().getId();
        SeasonStatistics stats = statsRepo.findByTeamIdAndSeasonId(alabamaId, seasonId).orElseThrow();

        assertThat(stats.getWins()).isEqualTo(20);
        assertThat(stats.getLosses()).isEqualTo(5);
        assertThat(stats.getPointsFor()).isEqualTo(2050);
        assertThat(stats.getPointsAgainst()).isEqualTo(1700);
        assertThat(stats.getStreak()).isEqualTo(4);
        assertThat(stats.getConferenceStanding()).isEqualTo(1);
        assertThat(stats.getHomeWins()).isEqualTo(12);
        assertThat(stats.getHomeLosses()).isEqualTo(1);
        assertThat(stats.getRoadWins()).isEqualTo(7);
        assertThat(stats.getRoadLosses()).isEqualTo(4);
        assertThat(stats.getConferenceWins()).isEqualTo(14);
        assertThat(stats.getConferenceLosses()).isEqualTo(4);
        // .getId() works on a Hibernate proxy without DB hit; getEspnId() would trigger lazy init.
        Long accId = conferenceRepo.findByEspnId("2").orElseThrow().getId();
        assertThat(stats.getConference().getId()).isEqualTo(accId);
    }

    @Test
    void scrape_skipsUnknownConferences() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        scraper.scrape(2025);

        // Conference "99999" doesn't exist in the DB → its entries are skipped.
        // Team 8888 should never be created (no fetchAndSaveUnknownTeam triggered).
        assertThat(teamRepo.findByEspnId("8888")).isEmpty();
    }

    @Test
    void scrape_recordCounts_includeBothMembershipAndStatsRows() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        ScrapeBatch batch = scraper.scrape(2025);

        // Two known ACC teams → each contributes a membership AND a stats insert → 4 created.
        assertThat(batch.getRecordsCreated()).isEqualTo(4);
        assertThat(batch.getRecordsUpdated()).isEqualTo(0);
    }

    @Test
    void scrape_idempotent_secondCallUpdatesInsteadOfDuplicating() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        scraper.scrape(2025);
        long membershipsAfterFirst = membershipRepo.count();
        long statsAfterFirst       = statsRepo.count();

        ScrapeBatch second = scraper.scrape(2025);

        assertThat(membershipRepo.count()).isEqualTo(membershipsAfterFirst);
        assertThat(statsRepo.count()).isEqualTo(statsAfterFirst);
        assertThat(second.getRecordsCreated()).isEqualTo(0);
        assertThat(second.getRecordsUpdated()).isEqualTo(4);
    }

    @Test
    void scrape_nonConferenceGroup_doesNotClobberRealMembership() throws Exception {
        // Even with a stale Crown conference row present, a Crown standings child (id 104) listing
        // Alabama must NOT re-point Alabama's membership away from the ACC. The Crown child is placed
        // last, so without the guard it would win the (team, season) upsert.
        Conference crown = new Conference();
        crown.setName("College Basketball Crown");
        crown.setAbbreviation("CBC");
        crown.setEspnId("104");
        conferenceRepo.save(crown);

        String json = """
                {
                  "children": [
                    {
                      "id": "2",
                      "name": "Atlantic Coast Conference",
                      "standings": { "entries": [
                        { "team": { "id": "333" }, "stats": [ { "type": "wins", "value": 20 } ] }
                      ] }
                    },
                    {
                      "id": "104",
                      "name": "College Basketball Crown",
                      "standings": { "entries": [
                        { "team": { "id": "333" }, "stats": [ { "type": "wins", "value": 1 } ] }
                      ] }
                    }
                  ]
                }
                """;
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(json));

        scraper.scrape(2025);

        Long alabamaId = teamRepo.findByEspnId("333").orElseThrow().getId();
        Long seasonId  = seasonRepo.findByYear(2025).orElseThrow().getId();
        Long accId     = conferenceRepo.findByEspnId("2").orElseThrow().getId();

        ConferenceMembership m = membershipRepo.findByTeamIdAndSeasonId(alabamaId, seasonId).orElseThrow();
        assertThat(m.getConference().getId()).isEqualTo(accId);
        SeasonStatistics s = statsRepo.findByTeamIdAndSeasonId(alabamaId, seasonId).orElseThrow();
        assertThat(s.getConference().getId()).isEqualTo(accId);
        assertThat(s.getWins()).isEqualTo(20); // ACC value, not the Crown's 1
    }

    @Test
    void scrape_apiFailure_marksBatchFailed() {
        when(espnApiClient.fetchStandings(2025))
                .thenThrow(new RuntimeException("ESPN unreachable"));

        ScrapeBatch batch = scraper.scrape(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.FAILED);
        assertThat(batch.getErrorMessage()).contains("ESPN unreachable");
    }

    @Test
    void scrape_pipelineContextStampedOnBatch() throws Exception {
        when(espnApiClient.fetchStandings(2025)).thenReturn(mapper.readTree(STANDINGS_JSON));

        java.util.UUID pipelineRunId = java.util.UUID.randomUUID();
        PipelineContext ctx = new PipelineContext(pipelineRunId, 3, ScrapeBatch.Source.SCHEDULED);
        ScrapeBatch batch = scraper.scrape(2025, ctx);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getSource()).isEqualTo(ScrapeBatch.Source.SCHEDULED);
        assertThat(batch.getPipelineStepOrder()).isEqualTo(3);
        assertThat(batch.getPipelineRunId()).isEqualTo(pipelineRunId);
    }
}
