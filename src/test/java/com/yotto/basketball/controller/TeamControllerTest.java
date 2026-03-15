package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TeamControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;

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
    }

    private Team mkTeam(String name) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId("espn-" + name);
        t.setActive(true);
        return teamRepo.save(t);
    }

    // ── GET /api/teams ────────────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsAllTeams() throws Exception {
        mkTeam("Alabama");
        mkTeam("Auburn");

        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── POST /api/teams ───────────────────────────────────────────────────────

    @Test
    void create_returns201WithTeam() throws Exception {
        String body = """
                {"name": "Kentucky", "active": true}
                """;

        mockMvc.perform(post("/api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Kentucky"));
    }

    @Test
    void create_blankName_returns400WithValidationError() throws Exception {
        String body = """
                {"name": ""}
                """;

        mockMvc.perform(post("/api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // ── GET /api/teams/{id} ───────────────────────────────────────────────────

    @Test
    void getById_returnsTeam() throws Exception {
        Team team = mkTeam("Florida");

        mockMvc.perform(get("/api/teams/{id}", team.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(team.getId()))
                .andExpect(jsonPath("$.name").value("Florida"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/teams/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── GET /api/teams/search ─────────────────────────────────────────────────

    @Test
    void searchByName_returnsMatchingTeam() throws Exception {
        mkTeam("Tennessee");
        mkTeam("Texas");

        mockMvc.perform(get("/api/teams/search").param("name", "enne"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Tennessee"));
    }

    @Test
    void searchByName_caseInsensitive() throws Exception {
        mkTeam("Vanderbilt");

        mockMvc.perform(get("/api/teams/search").param("name", "VANDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchByName_noResults_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/teams/search").param("name", "NoSuchTeam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── PUT /api/teams/{id} ───────────────────────────────────────────────────

    @Test
    void update_returnsUpdatedTeam() throws Exception {
        Team team = mkTeam("OldName");

        String body = """
                {"name": "NewName"}
                """;

        mockMvc.perform(put("/api/teams/{id}", team.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewName"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"name": "Anybody"}
                """;

        mockMvc.perform(put("/api/teams/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/teams/{id} ────────────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        // Create a team with no game dependencies
        Team team = mkTeam("DeleteMe");

        mockMvc.perform(delete("/api/teams/{id}", team.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/teams/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
