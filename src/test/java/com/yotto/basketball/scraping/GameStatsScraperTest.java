package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.repository.BettingOddsRepository;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameStatsScraperTest extends BaseIntegrationTest {

    @Autowired private GameStatsScraper scraper;
    @Autowired private GameRepository gameRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private TeamGameStatsRepository teamGameStatsRepository;
    @Autowired private SeasonPopulationStatRepository popStatRepo;
    @Autowired private TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired private BettingOddsRepository oddsRepo;
    @Autowired private PowerModelParamSnapshotRepository paramRepo;
    @Autowired private TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired private SeasonStatisticsRepository statsRepo;
    @Autowired private ConferenceMembershipRepository membershipRepo;
    @Autowired private ConferenceRepository conferenceRepo;

    @MockBean private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private Team home;
    private Team away;
    private Season season;
    private Game finalGame;

    @BeforeEach
    void setUp() {
        // FK-safe delete order — see project_test_cleanup_order memory.

        home = new Team();
        home.setEspnId("333");
        home.setName("Alabama");
        home.setActive(true);
        home = teamRepository.save(home);

        away = new Team();
        away.setEspnId("2");
        away.setName("Auburn");
        away.setActive(true);
        away = teamRepository.save(away);

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        season = seasonRepository.save(season);

        finalGame = new Game();
        finalGame.setEspnId("401999999");
        finalGame.setHomeTeam(home);
        finalGame.setAwayTeam(away);
        finalGame.setGameDate(LocalDateTime.of(2025, 2, 15, 21, 0));
        finalGame.setStatus(Game.GameStatus.FINAL);
        finalGame.setHomeScore(85);
        finalGame.setAwayScore(78);
        finalGame.setSeason(season);
        finalGame = gameRepository.save(finalGame);
    }

    private static final String SUMMARY_JSON = """
            {
              "boxscore": {
                "teams": [
                  {
                    "team": { "id": "333" },
                    "homeAway": "home",
                    "statistics": [
                      { "name": "fieldGoalsMade-fieldGoalsAttempted", "displayValue": "28-61" },
                      { "name": "threePointFieldGoalsMade-threePointFieldGoalsAttempted", "displayValue": "8-22" },
                      { "name": "freeThrowsMade-freeThrowsAttempted", "displayValue": "21-25" },
                      { "name": "totalRebounds", "displayValue": "36" },
                      { "name": "offensiveRebounds", "displayValue": "9" },
                      { "name": "defensiveRebounds", "displayValue": "27" },
                      { "name": "assists", "displayValue": "15" },
                      { "name": "steals", "displayValue": "7" },
                      { "name": "blocks", "displayValue": "4" },
                      { "name": "turnovers", "displayValue": "12" },
                      { "name": "fouls", "displayValue": "18" },
                      { "name": "technicalFouls", "displayValue": "0" },
                      { "name": "flagrantFouls", "displayValue": "0" },
                      { "name": "largestLead", "displayValue": "14" },
                      { "name": "pointsInPaint", "displayValue": "32" },
                      { "name": "fastBreakPoints", "displayValue": "10" },
                      { "name": "turnoverPoints", "displayValue": "16" }
                    ]
                  },
                  {
                    "team": { "id": "2" },
                    "homeAway": "away",
                    "statistics": [
                      { "name": "fieldGoalsMade-fieldGoalsAttempted", "displayValue": "26-60" },
                      { "name": "threePointFieldGoalsMade-threePointFieldGoalsAttempted", "displayValue": "7-25" },
                      { "name": "freeThrowsMade-freeThrowsAttempted", "displayValue": "19-22" },
                      { "name": "totalRebounds", "displayValue": "33" },
                      { "name": "offensiveRebounds", "displayValue": "11" },
                      { "name": "defensiveRebounds", "displayValue": "22" },
                      { "name": "assists", "displayValue": "13" },
                      { "name": "steals", "displayValue": "5" },
                      { "name": "blocks", "displayValue": "3" },
                      { "name": "turnovers", "displayValue": "14" },
                      { "name": "fouls", "displayValue": "20" }
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    void scrapeForGame_writesOneRowPerTeamWithParsedStats() throws Exception {
        when(espnApiClient.fetchGameSummary("401999999"))
                .thenReturn(mapper.readTree(SUMMARY_JSON));

        int rows = scraper.scrapeForGame(finalGame);
        assertThat(rows).isEqualTo(2);

        List<TeamGameStats> all = teamGameStatsRepository.findByGameId(finalGame.getId());
        assertThat(all).hasSize(2);

        TeamGameStats homeStats = teamGameStatsRepository
                .findByGameIdAndTeamId(finalGame.getId(), home.getId()).orElseThrow();
        assertThat(homeStats.getHomeAway()).isEqualTo("home");
        assertThat(homeStats.getFgMade()).isEqualTo(28);
        assertThat(homeStats.getFgAttempted()).isEqualTo(61);
        assertThat(homeStats.getFg3Made()).isEqualTo(8);
        assertThat(homeStats.getFg3Attempted()).isEqualTo(22);
        assertThat(homeStats.getFtMade()).isEqualTo(21);
        assertThat(homeStats.getFtAttempted()).isEqualTo(25);
        assertThat(homeStats.getTotalReb()).isEqualTo(36);
        assertThat(homeStats.getOffensiveReb()).isEqualTo(9);
        assertThat(homeStats.getDefensiveReb()).isEqualTo(27);
        assertThat(homeStats.getAssists()).isEqualTo(15);
        assertThat(homeStats.getSteals()).isEqualTo(7);
        assertThat(homeStats.getBlocks()).isEqualTo(4);
        assertThat(homeStats.getTurnovers()).isEqualTo(12);
        assertThat(homeStats.getFouls()).isEqualTo(18);
        assertThat(homeStats.getLargestLead()).isEqualTo(14);
        assertThat(homeStats.getPointsInPaint()).isEqualTo(32);
        assertThat(homeStats.getFastBreakPts()).isEqualTo(10);
        assertThat(homeStats.getTurnoverPts()).isEqualTo(16);

        TeamGameStats awayStats = teamGameStatsRepository
                .findByGameIdAndTeamId(finalGame.getId(), away.getId()).orElseThrow();
        assertThat(awayStats.getHomeAway()).isEqualTo("away");
        assertThat(awayStats.getFgMade()).isEqualTo(26);
        assertThat(awayStats.getFgAttempted()).isEqualTo(60);
        // Stats absent from JSON should remain null
        assertThat(awayStats.getLargestLead()).isNull();
        assertThat(awayStats.getPointsInPaint()).isNull();
    }

    @Test
    void scrapeForGame_isIdempotent() throws Exception {
        when(espnApiClient.fetchGameSummary("401999999"))
                .thenReturn(mapper.readTree(SUMMARY_JSON));

        scraper.scrapeForGame(finalGame);
        scraper.scrapeForGame(finalGame);

        List<TeamGameStats> rows = teamGameStatsRepository.findByGameId(finalGame.getId());
        assertThat(rows).hasSize(2);
    }

    @Test
    void backfill_skipsGamesThatAlreadyHaveStats() throws Exception {
        when(espnApiClient.fetchGameSummary("401999999"))
                .thenReturn(mapper.readTree(SUMMARY_JSON));

        ScrapeBatch first = scraper.backfill(2025);
        assertThat(first.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(first.getRecordsCreated()).isEqualTo(2);
        assertThat(first.getDatesSucceeded()).isEqualTo(1);

        // Second backfill: the game already has stats, so it should be filtered out
        ScrapeBatch second = scraper.backfill(2025);
        assertThat(second.getDatesSucceeded()).isEqualTo(0);
        assertThat(second.getDatesFailed()).isEqualTo(0);
        assertThat(second.getRecordsCreated()).isEqualTo(0);
    }

    @Test
    void backfill_failsCleanlyWhenSeasonMissing() {
        ScrapeBatch batch = scraper.backfill(1999);
        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.FAILED);
        assertThat(batch.getErrorMessage()).contains("1999");
    }

    @Test
    void parseMadeAttempted_handlesEdgeCases() {
        Integer[] holder = new Integer[2];
        GameStatsScraper.parseMadeAttempted("28-61", v -> holder[0] = v, v -> holder[1] = v);
        assertThat(holder[0]).isEqualTo(28);
        assertThat(holder[1]).isEqualTo(61);

        holder[0] = null;
        holder[1] = null;
        GameStatsScraper.parseMadeAttempted("0-0", v -> holder[0] = v, v -> holder[1] = v);
        assertThat(holder[0]).isEqualTo(0);
        assertThat(holder[1]).isEqualTo(0);

        holder[0] = null;
        holder[1] = null;
        GameStatsScraper.parseMadeAttempted("garbage", v -> holder[0] = v, v -> holder[1] = v);
        assertThat(holder[0]).isNull();
        assertThat(holder[1]).isNull();
    }
}
