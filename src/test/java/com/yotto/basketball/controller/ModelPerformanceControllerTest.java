package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    void performancePage_selectsRequestedYear() throws Exception {
        seedEvaluations();

        mockMvc.perform(get("/predictions/performance").param("year", "2025").param("window", "season"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2025")));
    }

    private void seedEvaluations() {
        Season season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        season = seasonRepo.save(season);

        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");

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

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setAbbreviation(abbr);
        t.setEspnId(abbr + "-test-id");
        t.setActive(true);
        return teamRepo.save(t);
    }
}
