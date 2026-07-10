package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

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
    @Autowired TeamStatSnapshotRepository derivedStatRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Long gameId;
    Long homeTeamId;
    Long awayTeamId;
    Long seasonDbId;

    @BeforeEach
    void setUp() {

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

        homeTeamId = home.getId();
        awayTeamId = away.getId();
        seasonDbId = season.getId();
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
                .andExpect(model().attributeExists("lastMeetings"))
                .andExpect(model().attributeExists("homeStats"))
                .andExpect(model().attributeExists("awayStats"))
                .andExpect(model().attributeExists("homeDerived"))
                .andExpect(model().attributeExists("awayDerived"))
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

    // ── Team Comparison: streak (regression — was silently never rendering) ──

    /**
     * Streak cells must render W{n}/L{n} with the win/loss colour class. This guards
     * the BEM double-underscore pitfall: class names with {@code __} must stay in a
     * static {@code class} attribute, never inside a Thymeleaf expression (where
     * {@code __...__} is preprocessing syntax).
     */
    @Test
    void comparisonTable_rendersStreaks() throws Exception {
        setStreaks(4, -2); // home on a 4-game win streak, away on a 2-game skid

        String html = mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("game-detail-comparison-table__streak--win")
                .contains("W4")
                .contains("game-detail-comparison-table__streak--loss")
                .contains("L2");
    }

    // ── Team Comparison: derived box-score stats (shooting / four factors) ────

    @Test
    void comparisonTable_rendersDerivedStats() throws Exception {
        addDerivedStat("fg_pct", 0.512, 0.487); // → FG% 51.2% / 48.7%

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FG%")))
                .andExpect(content().string(containsString("51.2%")))
                .andExpect(content().string(containsString("48.7%")));
    }

    /**
     * The Four-Factors section labels always render even when no derived snapshot
     * exists (each value cell falls back to an em-dash), so the section never crashes.
     */
    @Test
    void comparisonTable_rendersFourFactorsLabels_whenNoDerivedSnapshots() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Four Factors")))
                .andExpect(content().string(containsString("eFG%")));
    }

    // ── Betting section still uses native <details> accordion markup ─────────

    @Test
    void bettingSection_hasAccordionMarkup() throws Exception {
        addOddsWithMoneylines(-150, 130);

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<details")))
                .andExpect(content().string(containsString("<summary")));
    }

    // ── Model Predictions section (predicted vs. actual with signed error) ───

    @Test
    void predictionSection_rendersMasseyRowWithSignedError() throws Exception {
        addRating(homeTeamId, "MASSEY", 10.0);
        addRating(awayTeamId, "MASSEY", 4.0);
        addParam("MASSEY", "hca", 3.0);   // predicted spread = 10 − 4 + 3 = 9; actual margin +7 → error −2.0

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Model Predictions</summary>")))
                .andExpect(content().string(containsString("DUKE -9.0")))
                .andExpect(content().string(containsString("(-2.0)")));
    }

    @Test
    void predictionSection_hiddenWhenNoSnapshotsExist() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Model Predictions</summary>"))));
    }

    // ── Spread cover check uses handicap orientation ─────────────────────────

    @Test
    void bettingSpread_coverCheckUsesHandicapOrientation() throws Exception {
        // Home favored by 10.5 (stored −10.5) but won by only 7 → the AWAY side covered
        Game game = gameRepo.findById(gameId).orElseThrow();
        BettingOdds odds = new BettingOdds();
        odds.setGame(game);
        odds.setSpread(new java.math.BigDecimal("-10.5"));
        oddsRepo.save(odds);

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Away covered")));
    }

    @Test
    void bettingSpread_homeCoversWhenMarginBeatsHandicap() throws Exception {
        // Home favored by 3.5 (stored −3.5) and won by 7 → home covered
        Game game = gameRepo.findById(gameId).orElseThrow();
        BettingOdds odds = new BettingOdds();
        odds.setGame(game);
        odds.setSpread(new java.math.BigDecimal("-3.5"));
        oddsRepo.save(odds);

        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Home covered")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addRating(Long teamId, String modelType, double rating) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(teamRepo.findById(teamId).orElseThrow());
        s.setSeason(seasonRepo.findById(seasonDbId).orElseThrow());
        s.setModelType(modelType);
        s.setSnapshotDate(LocalDate.of(2025, 2, 14)); // day before game
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void addParam(String modelType, String paramName, double value) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(seasonRepo.findById(seasonDbId).orElseThrow());
        p.setModelType(modelType);
        p.setSnapshotDate(LocalDate.of(2025, 2, 14));
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }

    private void setStreaks(int homeStreak, int awayStreak) {
        SeasonStatistics home = statsRepo.findByTeamAndSeasonWithConference(homeTeamId, seasonDbId).orElseThrow();
        home.setCalcStreak(homeStreak);
        statsRepo.save(home);
        SeasonStatistics away = statsRepo.findByTeamAndSeasonWithConference(awayTeamId, seasonDbId).orElseThrow();
        away.setCalcStreak(awayStreak);
        statsRepo.save(away);
    }

    private void addDerivedStat(String statName, double homeValue, double awayValue) {
        Team home = teamRepo.findById(homeTeamId).orElseThrow();
        Team away = teamRepo.findById(awayTeamId).orElseThrow();
        Season season = seasonRepo.findById(seasonDbId).orElseThrow();
        derivedStatRepo.save(mkDerived(home, season, statName, homeValue));
        derivedStatRepo.save(mkDerived(away, season, statName, awayValue));
    }

    private TeamStatSnapshot mkDerived(Team team, Season season, String statName, double value) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(LocalDate.of(2025, 2, 14)); // day before game
        s.setStatName(statName);
        s.setValue(value);
        s.setGamesPlayed(10);
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
