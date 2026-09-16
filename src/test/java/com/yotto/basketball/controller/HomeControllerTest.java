package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.SeasonPhaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Full-render tests: the panel templates are exercised through Thymeleaf, not just the model. */
@AutoConfigureMockMvc
class HomeControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonPhaseService seasonPhaseService;
    @Autowired com.yotto.basketball.service.SeasonWrapService seasonWrapService;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired com.yotto.basketball.repository.PredictionEvaluationRepository evaluationRepo;

    @AfterEach
    void clearOverride() {
        seasonPhaseService.clearOverride();
    }

    @Test
    void home_rendersEmptyDatabaseWithoutError() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/home"))
                .andExpect(model().attributeExists("homePage"));
    }

    @Test
    void home_inSeason_rendersResultsAndSlatePanels() throws Exception {
        Season season = mkSeason();
        Team a = mkTeam("Alabama", "ALA");
        Team b = mkTeam("Auburn", "AUB");
        mkGame(season, a, b, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 11, 3));
        mkGame(season, a, b, 71, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 1, 15));
        mkGame(season, b, a, null, null, Game.GameStatus.SCHEDULED, LocalDate.of(2026, 1, 16));
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 1, 16));

        MvcResult res = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String html = res.getResponse().getContentAsString();
        assertThat(html).contains("Last Night");
        assertThat(html).contains("Tonight");
        assertThat(html).contains("Alabama");
        assertThat(html).doesNotContain("Latest News"); // no news seeded → no empty shell
    }

    @Test
    void home_offseason_rendersHistoryPanel() throws Exception {
        Season season = mkSeason();
        Team a = mkTeam("Alabama", "ALA");
        Team b = mkTeam("Auburn", "AUB");
        Game g = mkGame(season, a, b, 78, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 1, 10));
        mkEval(g, season, "MASSEY", 7.5);
        mkEval(g, season, "BOOK", -3.0);
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));

        MvcResult res = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getContentAsString())
                .contains("Hits and Misses")
                .contains("home-history__kind is-hit")
                .contains("Spot on: the model had Alabama by 7.5; the book had Auburn by 3.0. Alabama won by 8.");
    }

    @Test
    void home_postseason_rendersTourneyPanels() throws Exception {
        Season season = mkSeason();
        Team a = mkTeam("Alabama", "ALA");
        Team b = mkTeam("Auburn", "AUB");
        mkGame(season, a, b, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 8));
        mkTourney(season, a, b, 85, 60, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 19), "1st Round", 3, 14);
        mkTourney(season, b, a, null, null, Game.GameStatus.SCHEDULED, LocalDate.of(2026, 3, 21), "2nd Round", 14, 3);
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 3, 20));

        MvcResult res = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        String html = res.getResponse().getContentAsString();
        assertThat(html).contains("Tournament Scores").contains("Next Round").contains("(3)");
    }

    @Test
    void home_epilogue_rendersWrapAndChampionship() throws Exception {
        Season season = mkSeason();
        Team a = mkTeam("Alabama", "ALA");
        Team b = mkTeam("Auburn", "AUB");
        mkGame(season, a, b, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 11, 3));
        mkTourney(season, a, b, 78, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 4, 6),
                "National Championship", 1, 2);
        seasonWrapService.clearCache();
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 4, 10));

        MvcResult res = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        String html = res.getResponse().getContentAsString();
        assertThat(html).contains("Season, Wrapped").contains("National Champions")
                .contains("National Championship");
    }

    @Test
    void about_rendersCounts() throws Exception {
        mkSeason();
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/about"))
                .andExpect(model().attribute("seasonCount", 1L));
    }

    // ── fixtures ──

    private Season mkSeason() {
        Season s = new Season();
        s.setYear(2026);
        s.setStartDate(LocalDate.of(2025, 11, 1));
        s.setEndDate(LocalDate.of(2026, 4, 30));
        return seasonRepo.save(s);
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(abbr);
        t.setAbbreviation(abbr);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkTourney(Season s, Team home, Team away, Integer hs, Integer as,
                           Game.GameStatus status, LocalDate easternDate, String round,
                           Integer homeSeed, Integer awaySeed) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(status);
        g.setSeason(s);
        g.setGameDate(easternDate.atTime(14, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        g.setTournamentType(Game.TournamentType.NCAA_TOURNAMENT);
        g.setTournamentRound(round);
        g.setTournamentName("NCAA Tournament");
        g.setHomeSeed(homeSeed);
        g.setAwaySeed(awaySeed);
        gameRepo.save(g);
    }

    private Game mkGame(Season s, Team home, Team away, Integer hs, Integer as,
                        Game.GameStatus status, LocalDate easternDate) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(status);
        g.setSeason(s);
        g.setGameDate(easternDate.atTime(14, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        return gameRepo.save(g);
    }

    private void mkEval(Game g, Season season, String modelType, double predictedSpread) {
        int margin = g.getHomeScore() - g.getAwayScore();
        var pe = new com.yotto.basketball.entity.PredictionEvaluation();
        pe.setGame(g);
        pe.setSeason(season);
        pe.setModelType(modelType);
        pe.setGameDate(g.getGameDate().toLocalDate());
        pe.setPredictedSpread(predictedSpread);
        pe.setSpreadError(margin - predictedSpread);
        pe.setActualMargin(margin);
        pe.setActualTotal(g.getHomeScore() + g.getAwayScore());
        pe.setHomeWon(margin > 0);
        pe.setEvaluatedAt(java.time.LocalDateTime.of(2026, 4, 30, 12, 0));
        evaluationRepo.save(pe);
    }
}
