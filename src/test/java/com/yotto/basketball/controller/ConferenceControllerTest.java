package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ConferenceControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;

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

    private Conference mkConference(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    // ── GET /api/conferences ──────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/conferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsAllConferences() throws Exception {
        mkConference("SEC", "sec1");
        mkConference("ACC", "acc1");

        mockMvc.perform(get("/api/conferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── POST /api/conferences ─────────────────────────────────────────────────

    @Test
    void create_returns201WithConference() throws Exception {
        String body = """
                {"name": "Big Ten", "espnId": "bt1"}
                """;

        mockMvc.perform(post("/api/conferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Big Ten"));
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String body = """
                {"name": ""}
                """;

        mockMvc.perform(post("/api/conferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // ── GET /api/conferences/{id} ─────────────────────────────────────────────

    @Test
    void getById_returnsConference() throws Exception {
        Conference c = mkConference("SEC", "sec1");

        mockMvc.perform(get("/api/conferences/{id}", c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(c.getId()))
                .andExpect(jsonPath("$.name").value("SEC"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/conferences/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /api/conferences/name/{name} ──────────────────────────────────────

    @Test
    void getByName_returnsConference() throws Exception {
        mkConference("ACC", "acc1");

        mockMvc.perform(get("/api/conferences/name/ACC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ACC"));
    }

    @Test
    void getByName_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/conferences/name/NoSuchConf"))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/conferences/{id} ─────────────────────────────────────────────

    @Test
    void update_returnsUpdatedConference() throws Exception {
        Conference c = mkConference("OldName", "espn1");

        String body = """
                {"name": "NewName"}
                """;

        mockMvc.perform(put("/api/conferences/{id}", c.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewName"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"name": "Whatever"}
                """;

        mockMvc.perform(put("/api/conferences/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/conferences/{id} ──────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        Conference c = mkConference("ToDelete", "del1");

        mockMvc.perform(delete("/api/conferences/{id}", c.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/conferences/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
