package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
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
