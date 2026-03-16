package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class PredictionControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Team homeTeam, awayTeam;
    Season season;

    static final LocalDate SNAP_DATE = LocalDate.of(2025, 1, 14);
    static final LocalDate GAME_DATE = LocalDate.of(2025, 1, 20);

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

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        homeTeam = mkTeam("Alabama", "TA");
        awayTeam = mkTeam("Auburn", "TB");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame(Game.GameStatus status, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(homeTeam);
        g.setAwayTeam(awayTeam);
        g.setStatus(status);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    private void addRatings() {
        addRating(homeTeam, MasseyRatingService.MODEL_TYPE, 5.0);
        addRating(awayTeam, MasseyRatingService.MODEL_TYPE, 2.0);
        addParam(MasseyRatingService.MODEL_TYPE, "hca", 2.0);
        addRating(homeTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 75.0);
        addRating(awayTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 70.0);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 0.0);
        addRating(homeTeam, BradleyTerryRatingService.MODEL_TYPE, 1.0);
        addRating(awayTeam, BradleyTerryRatingService.MODEL_TYPE, 0.5);
        addParam(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.0);
        addRating(homeTeam, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 1.0);
        addRating(awayTeam, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 0.5);
        addParam(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", 0.0);
    }

    private void addRating(Team team, String modelType, double rating) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(SNAP_DATE);
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void addParam(String modelType, String paramName, double value) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setSnapshotDate(SNAP_DATE);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }

    // ── GET /api/predictions/game/{gameId} ────────────────────────────────────

    @Test
    void game_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/predictions/game/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void game_scheduledWithRatings_returnsPrediction() throws Exception {
        addRatings();
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        mockMvc.perform(get("/api/predictions/game/{gameId}", game.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(game.getId()))
                .andExpect(jsonPath("$.homeTeam.name").value("Alabama"))
                .andExpect(jsonPath("$.awayTeam.name").value("Auburn"))
                .andExpect(jsonPath("$.massey").exists())
                .andExpect(jsonPath("$.bradleyTerry").exists())
                .andExpect(jsonPath("$.bradleyTerryWeighted").exists());
    }

    @Test
    void game_noRatings_returnsNullPredictions() throws Exception {
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        mockMvc.perform(get("/api/predictions/game/{gameId}", game.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(game.getId()))
                .andExpect(jsonPath("$.massey").doesNotExist());
    }

    @Test
    void game_postponed_returnsNullPredictions() throws Exception {
        Game game = mkGame(Game.GameStatus.POSTPONED, GAME_DATE);

        mockMvc.perform(get("/api/predictions/game/{gameId}", game.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.massey").doesNotExist());
    }

    // ── GET /api/predictions/upcoming ─────────────────────────────────────────

    @Test
    void upcoming_noGames_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/predictions/upcoming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void upcoming_withDaysParam_respectsWindow() throws Exception {
        // Game far in the future — outside 7-day window
        Game futureGame = new Game();
        futureGame.setHomeTeam(homeTeam);
        futureGame.setAwayTeam(awayTeam);
        futureGame.setStatus(Game.GameStatus.SCHEDULED);
        futureGame.setNeutralSite(false);
        futureGame.setSeason(season);
        futureGame.setGameDate(LocalDateTime.now().plusDays(20));
        gameRepo.save(futureGame);

        mockMvc.perform(get("/api/predictions/upcoming").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void upcoming_scheduledGameInWindow_returned() throws Exception {
        Game nearGame = new Game();
        nearGame.setHomeTeam(homeTeam);
        nearGame.setAwayTeam(awayTeam);
        nearGame.setStatus(Game.GameStatus.SCHEDULED);
        nearGame.setNeutralSite(false);
        nearGame.setSeason(season);
        nearGame.setGameDate(LocalDateTime.now().plusDays(2));
        gameRepo.save(nearGame);

        mockMvc.perform(get("/api/predictions/upcoming").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gameId").value(nearGame.getId()));
    }
}
