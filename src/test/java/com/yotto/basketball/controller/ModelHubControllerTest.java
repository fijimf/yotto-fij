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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Models section (spec §7). In tests no ML bundle is loaded, so the public
 * model list is exactly the classical Adjusted Efficiency entry — which also
 * pins that CANDIDATE/unloaded models never surface.
 */
@AutoConfigureMockMvc
class ModelHubControllerTest extends BaseIntegrationTest {

    static final LocalDate GAME_DAY = LocalDate.of(2025, 3, 20);

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired PredictionEvaluationRepository evalRepo;

    Season season;
    Team duke;
    Team unc;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        duke = mkTeam("Duke", "DUKE");
        unc = mkTeam("North Carolina", "UNC");
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(abbr);
        t.setAbbreviation(abbr);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkNcaaFinalGame(int homeScore, int awayScore) {
        Game g = new Game();
        g.setSeason(season);
        g.setHomeTeam(duke);
        g.setAwayTeam(unc);
        g.setGameDate(GAME_DAY.atTime(19, 0));
        g.setStatus(Game.GameStatus.FINAL);
        g.setEspnId("ncaa-1");
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setTournamentType(Game.TournamentType.NCAA_TOURNAMENT);
        g.setTournamentRound("1st Round");
        g.setTournamentRegion("East");
        g.setHomeSeed(1);
        g.setAwaySeed(8);
        return gameRepo.save(g);
    }

    private void mkEval(Game g, String modelType, double spread, double prob) {
        PredictionEvaluation e = new PredictionEvaluation();
        e.setGame(g);
        e.setSeason(season);
        e.setModelType(modelType);
        e.setGameDate(GAME_DAY);
        e.setPredictedSpread(spread);
        e.setPredictedTotal(148.5);
        e.setPredictedHomeWinProb(prob);
        e.setActualMargin(g.getHomeScore() - g.getAwayScore());
        e.setActualTotal(g.getHomeScore() + g.getAwayScore());
        e.setHomeWon(g.getHomeScore() > g.getAwayScore());
        e.setSpreadError(spread - (g.getHomeScore() - g.getAwayScore()));
        e.setTotalError(148.5 - (g.getHomeScore() + g.getAwayScore()));
        e.setEvaluatedAt(LocalDateTime.of(2025, 3, 21, 6, 0));
        evalRepo.save(e);
    }

    // ── Index + About ─────────────────────────────────────────────────────────

    @Test
    void modelsIndex_listsAdjustedEfficiencyOnly() throws Exception {
        mockMvc.perform(get("/models"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/models-index"))
                .andExpect(content().string(containsString("Adjusted Efficiency (classic)")))
                .andExpect(content().string(containsString("/models/adjusted-efficiency")));
    }

    @Test
    void about_rendersEvalMetricsVsBook() throws Exception {
        Game g = mkNcaaFinalGame(78, 70);
        mkEval(g, "ADJ_EFF", 6.5, 0.72);
        mkEval(g, "BOOK", 5.5, 0.70);

        mockMvc.perform(get("/models/adjusted-efficiency"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/model-about"))
                .andExpect(content().string(containsString("Adjusted Efficiency (classic)")))
                .andExpect(content().string(containsString("Spread MAE")))
                .andExpect(content().string(containsString("Book Closing Line")))
                .andExpect(content().string(containsString("chart-calibration")));
    }

    @Test
    void about_unknownSlug_404s() throws Exception {
        mockMvc.perform(get("/models/some-candidate-model"))
                .andExpect(status().isNotFound());
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    @Test
    void schedule_pastDay_rendersEvalRowsWithPickMarksAndSummary() throws Exception {
        Game g = mkNcaaFinalGame(78, 70);
        mkEval(g, "ADJ_EFF", 6.5, 0.72);

        mockMvc.perform(get("/models/adjusted-efficiency/schedule").param("date", GAME_DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/model-schedule"))
                .andExpect(content().string(containsString("1–0")))          // day summary
                .andExpect(content().string(containsString("&#10003;")))      // ✓ pick mark rendered
                .andExpect(content().string(containsString("+6.5")))
                .andExpect(content().string(containsString("70–78")));        // away–home result
    }

    @Test
    void schedule_dayWithoutGames_showsEmptyState() throws Exception {
        mockMvc.perform(get("/models/adjusted-efficiency/schedule").param("date", "2025-03-25"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No games on this date")));
    }

    // ── Bracket overlay ───────────────────────────────────────────────────────

    @Test
    void bracket_rendersVerdictChipAndSummary() throws Exception {
        Game g = mkNcaaFinalGame(78, 70);
        mkEval(g, "ADJ_EFF", 6.5, 0.72);

        mockMvc.perform(get("/models/adjusted-efficiency/bracket/2025"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/model-bracket"))
                .andExpect(content().string(containsString("bracket-game__verdict--right")))
                .andExpect(content().string(containsString("DUKE 72%")))
                .andExpect(content().string(containsString("1–0")))
                .andExpect(content().string(containsString("1st Round")));
    }

    @Test
    void bracket_yearless_redirectsToLatestTournamentYear() throws Exception {
        mkNcaaFinalGame(78, 70);
        mockMvc.perform(get("/models/adjusted-efficiency/bracket"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/models/adjusted-efficiency/bracket/2025")));
    }

    @Test
    void bracket_modelWithoutPredictions_rendersUnannotatedNotice() throws Exception {
        mkNcaaFinalGame(78, 70); // bracket exists, but no eval rows

        mockMvc.perform(get("/models/adjusted-efficiency/bracket/2025"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no recorded predictions")));
    }

    // ── Retirement + matchup ──────────────────────────────────────────────────

    @Test
    void predictions_301sToCompare_whenNoDefaultMlModel() throws Exception {
        mockMvc.perform(get("/predictions"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", containsString("/models/compare")));
    }

    @Test
    void matchup_hasTypeAheadPickers() throws Exception {
        mockMvc.perform(get("/models/matchup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("datalist")))
                .andExpect(content().string(containsString("matchup-team-input")));
    }

    @Test
    void nav_modelsDropdown_listsPublicModels() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("All Models")))
                .andExpect(content().string(containsString("/models/adjusted-efficiency")));
    }
}
