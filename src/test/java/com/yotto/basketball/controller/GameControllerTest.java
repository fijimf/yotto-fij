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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class GameControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Team home, away;
    Season season;

    @BeforeEach
    void setUp() {

        home = mkTeam("Alabama", "TA");
        away = mkTeam("Auburn", "TB");

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame(Game.GameStatus status) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(status);
        g.setNeutralSite(false);
        g.setConferenceGame(true);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        g.setVenue("Coleman Coliseum");
        g.setEspnId("401-test");
        return gameRepo.save(g);
    }

    // ── GET /api/games ────────────────────────────────────────────────────────

    @Test
    void getAll_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsGames() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── POST /api/games ───────────────────────────────────────────────────────

    @Test
    void create_returns201() throws Exception {
        String body = """
                {
                  "gameDate": "2025-01-20T20:00:00",
                  "status": "SCHEDULED",
                  "neutralSite": false,
                  "homeTeam": {"id": %d},
                  "awayTeam": {"id": %d}
                }
                """.formatted(home.getId(), away.getId());

        mockMvc.perform(post("/api/games")
                        .param("homeTeamId", home.getId().toString())
                        .param("awayTeamId", away.getId().toString())
                        .param("seasonId", season.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    // ── GET /api/games/{id} ───────────────────────────────────────────────────

    @Test
    void getById_returnsFullGameShapeIncludingTeamsAndSeason() throws Exception {
        Game game = mkGame(Game.GameStatus.FINAL);
        game.setHomeScore(85);
        game.setAwayScore(78);
        gameRepo.save(game);

        mockMvc.perform(get("/api/games/{id}", game.getId()))
                .andExpect(status().isOk())
                // Scalar fields
                .andExpect(jsonPath("$.id").value(game.getId()))
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.homeScore").value(85))
                .andExpect(jsonPath("$.awayScore").value(78))
                .andExpect(jsonPath("$.venue").value("Coleman Coliseum"))
                .andExpect(jsonPath("$.espnId").value("401-test"))
                .andExpect(jsonPath("$.neutralSite").value(false))
                .andExpect(jsonPath("$.conferenceGame").value(true))
                .andExpect(jsonPath("$.gameDate").value("2025-01-15T20:00:00"))
                // Nested associations populated via JOIN FETCH in GameRepository.findByIdWithDetails
                .andExpect(jsonPath("$.homeTeam.id").value(home.getId()))
                .andExpect(jsonPath("$.homeTeam.name").value("Alabama"))
                .andExpect(jsonPath("$.awayTeam.id").value(away.getId()))
                .andExpect(jsonPath("$.awayTeam.name").value("Auburn"))
                .andExpect(jsonPath("$.season.id").value(season.getId()))
                .andExpect(jsonPath("$.season.year").value(2025));
    }

    @Test
    void getById_notFound_returns404WithErrorBody() throws Exception {
        mockMvc.perform(get("/api/games/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── GET /api/games/season/{seasonId} ─────────────────────────────────────

    @Test
    void getBySeason_returnsSeasonsGames() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games/season/{id}", season.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/games/team/{teamId} ──────────────────────────────────────────

    @Test
    void getByTeam_returnsTeamsGames() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games/team/{id}", home.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/games/date-range ─────────────────────────────────────────────

    @Test
    void getByDateRange_returnsMatchingGames() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games/date-range")
                        .param("start", "2025-01-01T00:00:00")
                        .param("end", "2025-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getByDateRange_excludesOutsideRange() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games/date-range")
                        .param("start", "2025-02-01T00:00:00")
                        .param("end", "2025-02-28T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/games/status/{status} ────────────────────────────────────────

    @Test
    void getByStatus_returnsMatchingGames() throws Exception {
        mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(get("/api/games/status/SCHEDULED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── PUT /api/games/{id}/score ─────────────────────────────────────────────

    @Test
    void updateScore_persistsScoresAndFlipsStatusToFinal() throws Exception {
        Game game = mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(put("/api/games/{id}/score", game.getId())
                        .param("homeScore", "85")
                        .param("awayScore", "78"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeScore").value(85))
                .andExpect(jsonPath("$.awayScore").value(78))
                .andExpect(jsonPath("$.status").value("FINAL"));

        Game reloaded = gameRepo.findById(game.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getHomeScore()).isEqualTo(85);
        org.assertj.core.api.Assertions.assertThat(reloaded.getAwayScore()).isEqualTo(78);
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo(Game.GameStatus.FINAL);
    }

    // ── PUT /api/games/{id}/status ────────────────────────────────────────────

    @Test
    void updateStatus_persistsStatusChange() throws Exception {
        Game game = mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(put("/api/games/{id}/status", game.getId())
                        .param("status", "POSTPONED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTPONED"));

        org.assertj.core.api.Assertions.assertThat(gameRepo.findById(game.getId()).orElseThrow().getStatus())
                .isEqualTo(Game.GameStatus.POSTPONED);
    }

    // ── DELETE /api/games/{id} ────────────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        Game game = mkGame(Game.GameStatus.SCHEDULED);

        mockMvc.perform(delete("/api/games/{id}", game.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/games/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── Validation error via GlobalExceptionHandler ────────────────────────────

    @Test
    void create_missingGameDate_returns400WithValidationErrors() throws Exception {
        // gameDate is @NotNull on Game entity — omitting it triggers MethodArgumentNotValidException
        String body = """
                {
                  "status": "SCHEDULED",
                  "homeTeam": {"id": %d},
                  "awayTeam": {"id": %d}
                }
                """.formatted(home.getId(), away.getId());

        mockMvc.perform(post("/api/games")
                        .param("homeTeamId", home.getId().toString())
                        .param("awayTeamId", away.getId().toString())
                        .param("seasonId", season.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.errors").exists());
    }
}
