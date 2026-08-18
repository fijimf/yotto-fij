package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.MlModel;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.BettingOddsRepository;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.MlModelRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class ModelPerformanceControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired PredictionEvaluationRepository evaluationRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired MlModelRepository mlModelRepo;
    @Autowired BettingOddsRepository oddsRepo;

    @Test
    void performancePage_emptyState_rendersWithoutData() throws Exception {
        mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/model-performance"))
                .andExpect(content().string(containsString("No prediction evaluations yet")));
    }

    @Test
    void performancePage_rendersMetricsTablesAndBenchmark() throws Exception {
        seedEvaluations();

        mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Massey")))
                .andExpect(content().string(containsString("Bradley-Terry")))
                .andExpect(content().string(containsString("Book Closing Line")))
                .andExpect(content().string(containsString("Winner")))
                .andExpect(content().string(containsString("Log Loss")))
                .andExpect(content().string(containsString("Brier")))
                .andExpect(content().string(not(containsString("No prediction evaluations yet"))));
    }

    @Test
    void performancePage_rendersMonthlyTrendWithOneEntryPerMonth() throws Exception {
        seedEvaluations();

        mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Month by Month")))
                // the inline chart data carries one point per model per month
                .andExpect(content().string(containsString("2025-01")))
                .andExpect(content().string(containsString("2025-02")));
    }

    @Test
    void performancePage_selectsRequestedYear() throws Exception {
        seedEvaluations();

        mockMvc.perform(get("/predictions/performance").param("year", "2025").param("window", "season"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2025")));
    }

    @Test
    void performancePage_ncaaSegment_filtersToTournamentGames() throws Exception {
        seedEvaluations();

        // Only the February game is NCAA_TOURNAMENT; Massey missed it by exactly 1.0
        MvcResult res = mockMvc.perform(get("/predictions/performance").param("segment", "ncaa"))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<PredictionEvaluationRepository.SpreadMetrics> rows =
                (List<PredictionEvaluationRepository.SpreadMetrics>) res.getModelAndView().getModel().get("spreadRows");
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r.getN()).isEqualTo(1L));
        PredictionEvaluationRepository.SpreadMetrics massey = rows.stream()
                .filter(r -> r.getModelType().equals("MASSEY")).findFirst().orElseThrow();
        assertThat(massey.getMae()).isEqualTo(1.0);
    }

    @Test
    void performancePage_regularSegment_excludesTournamentGames() throws Exception {
        seedEvaluations();

        // Only the January game is regular season; Massey missed it by exactly 4.0
        MvcResult res = mockMvc.perform(get("/predictions/performance").param("segment", "regular"))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<PredictionEvaluationRepository.SpreadMetrics> rows =
                (List<PredictionEvaluationRepository.SpreadMetrics>) res.getModelAndView().getModel().get("spreadRows");
        PredictionEvaluationRepository.SpreadMetrics massey = rows.stream()
                .filter(r -> r.getModelType().equals("MASSEY")).findFirst().orElseThrow();
        assertThat(massey.getN()).isEqualTo(1L);
        assertThat(massey.getMae()).isEqualTo(4.0);
    }

    @Test
    void performancePage_byConference_pairsModelWithBookOncePerGame() throws Exception {
        seedEvaluations();

        MvcResult res = mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("By Conference")))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ModelPerformanceController.ConferenceRow> rows =
                (List<ModelPerformanceController.ConferenceRow>) res.getModelAndView().getModel().get("conferenceRows");
        // No ML bundles in this context → default comparison model is Massey
        assertThat(res.getModelAndView().getModel().get("confModel")).isEqualTo("MASSEY");
        // Both teams are in the ACC: each game must count once, not once per membership
        assertThat(rows).hasSize(1);
        ModelPerformanceController.ConferenceRow acc = rows.get(0);
        assertThat(acc.name()).isEqualTo("ACC");
        assertThat(acc.n()).isEqualTo(2L);
        // Massey errors 4.0 and 1.0 → MAE 2.5; book errors 1.5 and 0.5 → MAE 1.0; Δ +1.5
        assertThat(acc.modelMae()).isCloseTo(2.5, within(1e-6));
        assertThat(acc.bookMae()).isCloseTo(1.0, within(1e-6));
        assertThat(acc.delta()).isCloseTo(1.5, within(1e-6));
    }

    @Test
    void performancePage_badgesInSampleMlModels() throws Exception {
        seedEvaluations();
        // "test" trained on 2025 (the seeded season) → badged; "other" trained on 2024 → not
        mkMlModel("test", "2024,2025");
        mkMlModel("other", "2024");
        Game game = gameRepo.findAll().get(0);
        Season season = seasonRepo.findAll().get(0);
        save(game, season, "ML:test", 7.0, null, null);
        save(game, season, "ML:other", 7.0, null, null);

        MvcResult res = mockMvc.perform(get("/predictions/performance").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("insample-badge")))
                .andReturn();
        @SuppressWarnings("unchecked")
        var badges = (java.util.Map<String, String>) res.getModelAndView().getModel().get("inSampleBadges");
        assertThat(badges).containsKey("ML:test");
        assertThat(badges).doesNotContainKey("ML:other");
        assertThat(badges).doesNotContainKey("BOOK");
        assertThat(badges.get("ML:test")).contains("2025");
    }

    @Test
    void performancePage_allSeasonsView_badgesEveryTrainedModelWithItsYears() throws Exception {
        seedEvaluations();
        mkMlModel("test", "2024,2025");

        MvcResult res = mockMvc.perform(get("/predictions/performance").param("year", "ALL"))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        var badges = (java.util.Map<String, String>) res.getModelAndView().getModel().get("inSampleBadges");
        assertThat(badges).containsKey("ML:test");
        assertThat(badges.get("ML:test")).contains("2024, 2025");
    }

    @Test
    void performancePage_outOfSampleSeason_showsNoBadges() throws Exception {
        seedEvaluations();
        mkMlModel("test", "2024");

        MvcResult res = mockMvc.perform(get("/predictions/performance").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("insample-badge"))))
                .andReturn();
        @SuppressWarnings("unchecked")
        var badges = (java.util.Map<String, String>) res.getModelAndView().getModel().get("inSampleBadges");
        assertThat(badges).isEmpty();
    }

    @Test
    void performancePage_allSeasonsOption_rendersAggregatedData() throws Exception {
        seedEvaluations();

        MvcResult res = mockMvc.perform(get("/predictions/performance").param("year", "ALL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Massey")))
                .andExpect(content().string(not(containsString("No prediction evaluations yet"))))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<PredictionEvaluationRepository.SpreadMetrics> rows =
                (List<PredictionEvaluationRepository.SpreadMetrics>) res.getModelAndView().getModel().get("spreadRows");
        PredictionEvaluationRepository.SpreadMetrics massey = rows.stream()
                .filter(r -> r.getModelType().equals("MASSEY")).findFirst().orElseThrow();
        assertThat(massey.getN()).isEqualTo(2L);
    }

    /**
     * Hand-computed vs-book scenario (home-margin orientation; odds rows are handicap
     * orientation, negative = home favored):
     *
     * <pre>
     * Game 1  margin 5, total 155.  ML 9.0/145.0, BOOK 6.5/150.5, open −4.5 close −6.5.
     *         ATS: home pick, loss.  O/U: under pick, loss.  CLV: 4.5→6.5 toward model, win.
     * Game 2  margin 3, total 137.  ML 2.0/139.0, BOOK 4.0/140.5, open −5.0 close −4.0.
     *         ATS: away pick, win.   O/U: under pick, win.   CLV: 5.0→4.0 toward model, win.
     * Game 3  margin 5.            ML 7.0/—,     BOOK 5.0/—,   no odds row.
     *         ATS: push (excluded). O/U: no totals.          CLV: no odds (excluded).
     * </pre>
     */
    @Test
    void performancePage_vsBookCard_computesPairedAtsOuAndClv() throws Exception {
        Season season = mkSeason(2025);
        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");

        Game g1 = mkGame(home, away, season, LocalDateTime.of(2025, 1, 10, 19, 0), 80, 75);
        Game g2 = mkGame(home, away, season, LocalDateTime.of(2025, 1, 20, 19, 0), 70, 67);
        Game g3 = mkGame(home, away, season, LocalDateTime.of(2025, 1, 30, 19, 0), 75, 70);

        saveEval(g1, season, "ML:test", 9.0, 145.0, null, 5, 155);
        saveEval(g1, season, "BOOK",    6.5, 150.5, null, 5, 155);
        saveEval(g2, season, "ML:test", 2.0, 139.0, null, 3, 137);
        saveEval(g2, season, "BOOK",    4.0, 140.5, null, 3, 137);
        saveEval(g3, season, "ML:test", 7.0, null,  null, 5, 145);
        saveEval(g3, season, "BOOK",    5.0, null,  null, 5, 145);
        // Probability-only model must be dropped from the card (no spread/total overlap)
        saveEval(g1, season, "BRADLEY_TERRY", null, null, 0.6, 5, 155);

        mkOdds(g1, "-6.5", "-4.5", "150.5");
        mkOdds(g2, "-4.0", "-5.0", "140.5");

        MvcResult res = mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                // subtitle only renders when the card itself does (the section comment always survives)
                .andExpect(content().string(containsString("identical games only")))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ModelPerformanceController.VsBookRow> rows =
                (List<ModelPerformanceController.VsBookRow>) res.getModelAndView().getModel().get("vsBookRows");
        assertThat(rows).hasSize(1);
        ModelPerformanceController.VsBookRow r = rows.get(0);
        assertThat(r.modelType()).isEqualTo("ML:test");
        assertThat(r.spreadN()).isEqualTo(3L);
        assertThat(r.modelMae()).isCloseTo((4.0 + 1.0 + 2.0) / 3, within(1e-6));
        assertThat(r.bookMae()).isCloseTo((1.5 + 1.0 + 0.0) / 3, within(1e-6));
        assertThat(r.delta()).isCloseTo(1.5, within(1e-6));
        assertThat(r.atsN()).isEqualTo(2L);
        assertThat(r.atsRate()).isCloseTo(0.5, within(1e-6));
        assertThat(r.ouN()).isEqualTo(2L);
        assertThat(r.ouRate()).isCloseTo(0.5, within(1e-6));
        // Both moved lines moved toward the model — this assertion is the sign-convention
        // guard on the handicap → home-margin conversion (−opening_spread / −spread)
        assertThat(r.clvN()).isEqualTo(2L);
        assertThat(r.clvRate()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void performancePage_vsBookCard_hiddenWithoutOverlappingGames() throws Exception {
        Season season = mkSeason(2025);
        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");
        Game g1 = mkGame(home, away, season, LocalDateTime.of(2025, 1, 10, 19, 0), 80, 75);
        // Model rows without any BOOK row for the game → empty card
        saveEval(g1, season, "ML:test", 9.0, 145.0, null, 5, 155);

        MvcResult res = mockMvc.perform(get("/predictions/performance"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("identical games only"))))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ModelPerformanceController.VsBookRow> rows =
                (List<ModelPerformanceController.VsBookRow>) res.getModelAndView().getModel().get("vsBookRows");
        assertThat(rows).isEmpty();
    }

    private void seedEvaluations() {
        Season season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        season = seasonRepo.save(season);

        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");

        Conference acc = new Conference();
        acc.setName("ACC");
        acc.setAbbreviation("ACC");
        acc.setEspnId("acc-test-id");
        acc = conferenceRepo.save(acc);
        mkMembership(home, acc, season);
        mkMembership(away, acc, season);

        Game game = new Game();
        game.setHomeTeam(home);
        game.setAwayTeam(away);
        game.setStatus(Game.GameStatus.FINAL);
        game.setHomeScore(80);
        game.setAwayScore(75);
        game.setSeason(season);
        game.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        game = gameRepo.save(game);

        save(game, season, "MASSEY", 9.0, null, null);
        save(game, season, "MASSEY_TOTALS", null, 148.0, null);
        save(game, season, "BRADLEY_TERRY", null, null, 0.65);
        save(game, season, "BOOK", 6.5, 150.5, 0.70);

        Game febGame = new Game();
        febGame.setHomeTeam(home);
        febGame.setAwayTeam(away);
        febGame.setStatus(Game.GameStatus.FINAL);
        febGame.setHomeScore(70);
        febGame.setAwayScore(65);
        febGame.setSeason(season);
        febGame.setGameDate(LocalDateTime.of(2025, 2, 10, 19, 0));
        febGame.setTournamentType(Game.TournamentType.NCAA_TOURNAMENT);
        febGame = gameRepo.save(febGame);

        save(febGame, season, "MASSEY", 4.0, null, null);
        save(febGame, season, "BOOK", 5.5, null, 0.62);
    }

    private void save(Game game, Season season, String modelType,
                      Double spread, Double total, Double prob) {
        PredictionEvaluation e = new PredictionEvaluation();
        e.setGame(game);
        e.setSeason(season);
        e.setModelType(modelType);
        e.setGameDate(game.getGameDate().toLocalDate());
        e.setPredictedSpread(spread);
        e.setPredictedTotal(total);
        e.setPredictedHomeWinProb(prob);
        e.setActualMargin(5);
        e.setActualTotal(155);
        e.setHomeWon(true);
        e.setSpreadError(spread != null ? 5 - spread : null);
        e.setTotalError(total != null ? 155 - total : null);
        e.setEvaluatedAt(LocalDateTime.now());
        evaluationRepo.save(e);
    }

    private Season mkSeason(int year) {
        Season season = new Season();
        season.setYear(year);
        season.setStartDate(LocalDate.of(year - 1, 11, 1));
        season.setEndDate(LocalDate.of(year, 4, 30));
        return seasonRepo.save(season);
    }

    private Game mkGame(Team home, Team away, Season season, LocalDateTime date,
                        int homeScore, int awayScore) {
        Game game = new Game();
        game.setHomeTeam(home);
        game.setAwayTeam(away);
        game.setStatus(Game.GameStatus.FINAL);
        game.setHomeScore(homeScore);
        game.setAwayScore(awayScore);
        game.setSeason(season);
        game.setGameDate(date);
        return gameRepo.save(game);
    }

    private void saveEval(Game game, Season season, String modelType,
                          Double spread, Double total, Double prob,
                          int actualMargin, int actualTotal) {
        PredictionEvaluation e = new PredictionEvaluation();
        e.setGame(game);
        e.setSeason(season);
        e.setModelType(modelType);
        e.setGameDate(game.getGameDate().toLocalDate());
        e.setPredictedSpread(spread);
        e.setPredictedTotal(total);
        e.setPredictedHomeWinProb(prob);
        e.setActualMargin(actualMargin);
        e.setActualTotal(actualTotal);
        e.setHomeWon(actualMargin > 0);
        e.setSpreadError(spread != null ? actualMargin - spread : null);
        e.setTotalError(total != null ? actualTotal - total : null);
        e.setEvaluatedAt(LocalDateTime.now());
        evaluationRepo.save(e);
    }

    /** Odds in handicap orientation (negative = home favored), matching betting_odds. */
    private void mkOdds(Game game, String closingSpread, String openingSpread, String overUnder) {
        BettingOdds odds = new BettingOdds();
        odds.setGame(game);
        if (closingSpread != null) odds.setSpread(new java.math.BigDecimal(closingSpread));
        if (openingSpread != null) odds.setOpeningSpread(new java.math.BigDecimal(openingSpread));
        if (overUnder != null) odds.setOverUnder(new java.math.BigDecimal(overUnder));
        odds.setLastUpdated(LocalDateTime.now());
        oddsRepo.save(odds);
    }

    private void mkMlModel(String slug, String trainSeasons) {
        MlModel m = new MlModel();
        m.setSlug(slug);
        m.setDisplayName(slug);
        m.setStatus(MlModel.Status.ACTIVE);
        m.setIsDefault(false);
        m.setTrainSeasons(trainSeasons);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        mlModelRepo.save(m);
    }

    private void mkMembership(Team team, Conference conference, Season season) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conference);
        m.setSeason(season);
        membershipRepo.save(m);
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
