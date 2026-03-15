package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BettingOddsControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Team home, away;
    Season season;
    Game game;

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

        home = mkTeam("Alabama", "TA");
        away = mkTeam("Auburn", "TB");

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        game = mkGame();
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame() {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.SCHEDULED);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        return gameRepo.save(g);
    }

    private BettingOdds mkOdds(Game g, BigDecimal spread, BigDecimal ou) {
        BettingOdds o = new BettingOdds();
        o.setGame(g);
        o.setSpread(spread);
        o.setOverUnder(ou);
        return oddsRepo.save(o);
    }

    // ── GET /api/betting-odds ─────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/betting-odds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsOdds() throws Exception {
        mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        mockMvc.perform(get("/api/betting-odds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── POST /api/betting-odds ────────────────────────────────────────────────

    @Test
    void create_returns201() throws Exception {
        String body = """
                {"spread": -3.5, "overUnder": 145.5}
                """;

        mockMvc.perform(post("/api/betting-odds")
                        .param("gameId", game.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.spread").value(-3.5));
    }

    // ── GET /api/betting-odds/{id} ────────────────────────────────────────────

    @Test
    void getById_returnsOdds() throws Exception {
        BettingOdds o = mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        mockMvc.perform(get("/api/betting-odds/{id}", o.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(o.getId()));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/betting-odds/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /api/betting-odds/game/{gameId} ───────────────────────────────────

    @Test
    void getByGame_returnsOdds() throws Exception {
        mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        mockMvc.perform(get("/api/betting-odds/game/{gameId}", game.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spread").value(-3.5));
    }

    @Test
    void getByGame_noOdds_returns404() throws Exception {
        mockMvc.perform(get("/api/betting-odds/game/{gameId}", game.getId()))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/betting-odds/season/{seasonId} ───────────────────────────────

    @Test
    void getBySeason_returnsOdds() throws Exception {
        mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        mockMvc.perform(get("/api/betting-odds/season/{seasonId}", season.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── PUT /api/betting-odds/{id} ────────────────────────────────────────────

    @Test
    void update_returnsUpdatedOdds() throws Exception {
        BettingOdds o = mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        String body = """
                {"spread": -6.5, "overUnder": 150.0}
                """;

        mockMvc.perform(put("/api/betting-odds/{id}", o.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spread").value(-6.5));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"spread": -3.5}
                """;

        mockMvc.perform(put("/api/betting-odds/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/betting-odds/{id} ─────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        BettingOdds o = mkOdds(game, new BigDecimal("-3.5"), new BigDecimal("145.5"));

        mockMvc.perform(delete("/api/betting-odds/{id}", o.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/betting-odds/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
