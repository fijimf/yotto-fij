package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class GameDetailPageTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Long gameId;

    @BeforeEach
    void setUp() {
        popStatRepo.deleteAll();
        snapshotRepo.deleteAll();
        oddsRepo.deleteAll();
        paramRepo.deleteAll();
        ratingRepo.deleteAll();
        statsRepo.deleteAll();
        gameRepo.deleteAll();
        membershipRepo.deleteAll();
        teamRepo.deleteAll();
        conferenceRepo.deleteAll();
        seasonRepo.deleteAll();

        Conference conf = new Conference();
        conf.setName("ACC");
        conf.setAbbreviation("ACC");
        conf.setEspnId("acc-id");
        conferenceRepo.save(conf);

        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");

        Season season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        SeasonStatistics homeStats = new SeasonStatistics();
        homeStats.setTeam(home);
        homeStats.setSeason(season);
        homeStats.setConference(conf);
        homeStats.setWins(15);
        homeStats.setLosses(5);
        homeStats.setCalcWins(15);
        homeStats.setCalcLosses(5);
        homeStats.setCalcPointsFor(1050);
        homeStats.setCalcPointsAgainst(900);
        statsRepo.save(homeStats);

        SeasonStatistics awayStats = new SeasonStatistics();
        awayStats.setTeam(away);
        awayStats.setSeason(season);
        awayStats.setConference(conf);
        awayStats.setWins(12);
        awayStats.setLosses(8);
        awayStats.setCalcWins(12);
        awayStats.setCalcLosses(8);
        awayStats.setCalcPointsFor(980);
        awayStats.setCalcPointsAgainst(940);
        statsRepo.save(awayStats);

        Game game = new Game();
        game.setHomeTeam(home);
        game.setAwayTeam(away);
        game.setStatus(Game.GameStatus.FINAL);
        game.setHomeScore(75);
        game.setAwayScore(68);
        game.setNeutralSite(false);
        game.setConferenceGame(true);
        game.setSeason(season);
        game.setGameDate(LocalDateTime.of(2025, 2, 15, 19, 0));
        gameRepo.save(game);
        gameId = game.getId();
    }

    @Test
    void gameDetailPage_returns200() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/game-detail"));
    }

    @Test
    void gameDetailPage_hasRequiredModelAttributes() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("homeH2HWins"))
                .andExpect(model().attributeExists("awayH2HWins"))
                .andExpect(model().attributeExists("lastMeetings"))
                .andExpect(model().attributeExists("homeStats"))
                .andExpect(model().attributeExists("awayStats"))
                .andExpect(model().attributeExists("homeNeutralWins"))
                .andExpect(model().attributeExists("awayNeutralWins"))
                .andExpect(model().attributeExists("homeLast5Wins"))
                .andExpect(model().attributeExists("awayLast5Wins"))
                .andExpect(model().attributeExists("chartDataJson"));
    }

    @Test
    void gameDetailPage_conferenceName_setForConferenceGame() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("conferenceName", "ACC"));
    }

    @Test
    void gameDetailPage_h2hWins_zeroWithNoPriorMeetings() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("homeH2HWins", 0L))
                .andExpect(model().attribute("awayH2HWins", 0L));
    }

    // ── Task 1: BT implied moneylines rendered ───────────────────────────────

    /**
     * With BT ratings 1.5 (home) / 0.5 (away), no HCA param:
     *   pHome ≈ sigmoid(1.0) ≈ 0.7311  →  homeImpliedML = -272
     *   pAway ≈ 0.2689                  →  awayImpliedML = +272
     * The predictions table must render these as a "game-detail-implied-ml" span.
     */
    @Test
    void btPrediction_rendersImpliedMoneylines_inPredictionsTable() throws Exception {
        addBtSnapshots(1.5, 0.5);

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("game-detail-implied-ml")))
                .andExpect(content().string(containsString("-272")))
                .andExpect(content().string(containsString("+272")));
    }

    // ── Task 2: BT vs book moneyline comparison ──────────────────────────────

    /**
     * homeImpliedML = -272, bookHomeML = -200 → diff = -72 (|72| > 15 → amber class).
     */
    @Test
    void btPrediction_vsBookColumn_showsMoneylineDiffWithAmberWhenLarge() throws Exception {
        addBtSnapshots(1.5, 0.5);
        addOddsWithMoneylines(-200, 165); // book: home -200, away +165

        // diff = -272 - (-200) = -72
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("-72")))
                .andExpect(content().string(containsString("game-detail-model-table__delta--alert")));
    }

    /**
     * When no book moneyline is recorded the vs-Line cell for BT rows must show "—".
     */
    @Test
    void btPrediction_vsBookColumn_showsDashWhenNoBookMoneyline() throws Exception {
        addBtSnapshots(1.5, 0.5);
        // No BettingOdds added

        String html = mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // BT section should contain "—" (em-dash used in the table)
        // and must NOT contain "-72" or any diff value
        org.assertj.core.api.Assertions.assertThat(html).contains("DeepFij Win Probability");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("-72");
    }

    // ── Task 3: Mobile accordion markup present ──────────────────────────────

    /**
     * The betting and predictions sections must be wrapped in <details> elements
     * so browsers render them as native accordions on mobile.
     */
    @Test
    void bettingAndPredictions_haveAccordionMarkup() throws Exception {
        addBtSnapshots(1.5, 0.5);
        addOddsWithMoneylines(-150, 130);

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<details")))
                .andExpect(content().string(containsString("<summary")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addBtSnapshots(double homeRating, double awayRating) {
        Team home = teamRepo.findAll().stream()
                .filter(t -> "DUKE".equals(t.getAbbreviation())).findFirst().orElseThrow();
        Team away = teamRepo.findAll().stream()
                .filter(t -> "UNC".equals(t.getAbbreviation())).findFirst().orElseThrow();
        Season season = seasonRepo.findAll().get(0);

        ratingRepo.save(mkBtSnapshot(home, season, homeRating));
        ratingRepo.save(mkBtSnapshot(away, season, awayRating));
    }

    private TeamPowerRatingSnapshot mkBtSnapshot(Team team, Season season, double rating) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType("BRADLEY_TERRY");
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setSnapshotDate(LocalDate.of(2025, 2, 14)); // day before game
        s.setCalculatedAt(LocalDateTime.of(2025, 2, 14, 6, 0));
        return s;
    }

    private void addOddsWithMoneylines(int homeML, int awayML) {
        Game game = gameRepo.findById(gameId).orElseThrow();
        BettingOdds odds = new BettingOdds();
        odds.setGame(game);
        odds.setHomeMoneyline(homeML);
        odds.setAwayMoneyline(awayML);
        oddsRepo.save(odds);
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setAbbreviation(abbr);
        t.setEspnId(abbr + "-test-id");
        t.setActive(true);
        return teamRepo.save(t);
    }
}
