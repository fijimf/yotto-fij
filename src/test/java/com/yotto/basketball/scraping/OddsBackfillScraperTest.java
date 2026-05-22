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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OddsBackfillScraperTest extends BaseIntegrationTest {

    @Autowired private OddsBackfillScraper scraper;
    @Autowired private GameRepository gameRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private SeasonRepository seasonRepo;
    @Autowired private BettingOddsRepository oddsRepo;
    @Autowired private ScrapeBatchRepository batchRepo;

    @Autowired private SeasonPopulationStatRepository popStatRepo;
    @Autowired private TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired private TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired private PowerModelParamSnapshotRepository paramRepo;
    @Autowired private SeasonStatisticsRepository statsRepo;
    @Autowired private ConferenceMembershipRepository membershipRepo;
    @Autowired private ConferenceRepository conferenceRepo;

    @MockBean private EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private Season season;
    private Team home;
    private Team away;

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        season = seasonRepo.save(season);

        home = new Team();
        home.setEspnId("333");
        home.setName("Alabama");
        home.setActive(true);
        home = teamRepo.save(home);

        away = new Team();
        away.setEspnId("2");
        away.setName("Auburn");
        away.setActive(true);
        away = teamRepo.save(away);
    }

    private static final String ODDS_JSON = """
            {
              "items": [
                {
                  "spread": -3.5,
                  "overUnder": 145.5,
                  "homeTeamOdds": { "moneyLine": "-180" },
                  "awayTeamOdds": { "moneyLine": "150" },
                  "pointSpread": { "home": { "open": { "line": "-4" } } },
                  "total":       { "over": { "open": { "line": "o146.5" } } },
                  "provider":    { "name": "Bet Provider" }
                }
              ]
            }
            """;

    private Game mkFinalGame(String espnId) {
        Game g = new Game();
        g.setEspnId(espnId);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.FINAL);
        g.setHomeScore(80);
        g.setAwayScore(75);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        return gameRepo.save(g);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void backfill_seasonNotFound_marksBatchFailed() {
        ScrapeBatch batch = scraper.backfill(9999);
        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.FAILED);
        assertThat(batch.getErrorMessage()).contains("9999");
    }

    @Test
    void backfill_createsOddsForFinalGameMissingThem() throws Exception {
        Game game = mkFinalGame("401111");
        when(espnApiClient.fetchGameOdds("401111")).thenReturn(mapper.readTree(ODDS_JSON));

        ScrapeBatch batch = scraper.backfill(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isEqualTo(1);
        assertThat(batch.getDatesSucceeded()).isEqualTo(1);

        BettingOdds saved = oddsRepo.findByGameId(game.getId()).orElseThrow();
        assertThat(saved.getSpread()).isEqualByComparingTo(new BigDecimal("-3.5"));
        assertThat(saved.getOverUnder()).isEqualByComparingTo(new BigDecimal("145.5"));
        assertThat(saved.getHomeMoneyline()).isEqualTo(-180);
        assertThat(saved.getAwayMoneyline()).isEqualTo(150);
        assertThat(saved.getOpeningSpread()).isEqualByComparingTo(new BigDecimal("-4"));
        // Opening O/U should have its "o" prefix stripped
        assertThat(saved.getOpeningOverUnder()).isEqualByComparingTo(new BigDecimal("146.5"));
        assertThat(saved.getSource()).isEqualTo("Bet Provider");
    }

    @Test
    void backfill_skipsGamesThatAlreadyHaveOdds() throws Exception {
        Game game = mkFinalGame("401222");
        BettingOdds existing = new BettingOdds();
        existing.setGame(game);
        existing.setSpread(new BigDecimal("-6.5"));
        oddsRepo.save(existing);

        // Even though we wire up a mock, the game shouldn't appear in the query result.
        when(espnApiClient.fetchGameOdds("401222")).thenReturn(mapper.readTree(ODDS_JSON));

        ScrapeBatch batch = scraper.backfill(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isEqualTo(0);
        assertThat(batch.getDatesSucceeded()).isEqualTo(0);
        assertThat(batch.getDatesFailed()).isEqualTo(0);

        // Pre-existing odds untouched
        BettingOdds reloaded = oddsRepo.findByGameId(game.getId()).orElseThrow();
        assertThat(reloaded.getSpread()).isEqualByComparingTo(new BigDecimal("-6.5"));
    }

    @Test
    void backfill_individualFailure_doesNotAbortBatch() throws Exception {
        Game g1 = mkFinalGame("401333");
        Game g2 = mkFinalGame("401334");

        when(espnApiClient.fetchGameOdds("401333"))
                .thenThrow(new RuntimeException("ESPN 503"));
        when(espnApiClient.fetchGameOdds("401334"))
                .thenReturn(mapper.readTree(ODDS_JSON));

        ScrapeBatch batch = scraper.backfill(2025);

        // Mixed success/failure → batch status is PARTIAL (per ScrapeBatch.complete()).
        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.PARTIAL);
        assertThat(batch.getDatesFailed()).isEqualTo(1);
        assertThat(batch.getDatesSucceeded()).isEqualTo(1);
        assertThat(batch.getRecordsCreated()).isEqualTo(1);
        assertThat(oddsRepo.findByGameId(g1.getId())).isEmpty();
        assertThat(oddsRepo.findByGameId(g2.getId())).isPresent();
    }

    @Test
    void backfill_emptyItemsList_returnsFalseAndDoesNotCreateRow() throws Exception {
        Game game = mkFinalGame("401444");
        String emptyJson = "{\"items\": []}";
        when(espnApiClient.fetchGameOdds("401444")).thenReturn(mapper.readTree(emptyJson));

        ScrapeBatch batch = scraper.backfill(2025);

        assertThat(batch.getStatus()).isEqualTo(ScrapeBatch.ScrapeStatus.COMPLETED);
        assertThat(batch.getRecordsCreated()).isEqualTo(0);
        // dates-succeeded is incremented even when no row is created (caller didn't throw)
        assertThat(batch.getDatesSucceeded()).isEqualTo(1);
        assertThat(oddsRepo.findByGameId(game.getId())).isEmpty();
    }

    @Test
    void backfill_progressTotalEqualsGameCount() throws Exception {
        mkFinalGame("401555");
        mkFinalGame("401556");
        mkFinalGame("401557");
        when(espnApiClient.fetchGameOdds(anyString())).thenReturn(mapper.readTree(ODDS_JSON));

        ScrapeBatch batch = scraper.backfill(2025);

        assertThat(batch.getProgressTotal()).isEqualTo(3);
        assertThat(batch.getRecordsCreated()).isEqualTo(3);
    }

    @Test
    void backfill_moneylineOFF_parsedAsNull() throws Exception {
        Game game = mkFinalGame("401666");
        String oddsWithOff = """
                {
                  "items": [{
                    "spread": -1.5,
                    "overUnder": 130,
                    "homeTeamOdds": { "moneyLine": "-110" },
                    "awayTeamOdds": { "moneyLine": "OFF" }
                  }]
                }
                """;
        when(espnApiClient.fetchGameOdds("401666")).thenReturn(mapper.readTree(oddsWithOff));

        scraper.backfill(2025);

        BettingOdds saved = oddsRepo.findByGameId(game.getId()).orElseThrow();
        assertThat(saved.getHomeMoneyline()).isEqualTo(-110);
        assertThat(saved.getAwayMoneyline()).isNull();
    }
}
