package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SeasonControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired TeamRepository teamRepo;
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

    private Season mkSeason(int year) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(LocalDate.of(year - 1, 11, 1));
        s.setEndDate(LocalDate.of(year, 4, 30));
        return seasonRepo.save(s);
    }

    // ── GET /api/seasons ──────────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/seasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsAllSeasons() throws Exception {
        mkSeason(2024);
        mkSeason(2025);

        mockMvc.perform(get("/api/seasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── POST /api/seasons ─────────────────────────────────────────────────────

    @Test
    void create_returns201WithSeason() throws Exception {
        String body = """
                {"year": 2026, "startDate": "2025-11-01", "endDate": "2026-04-30"}
                """;

        mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.year").value(2026));
    }

    @Test
    void create_nullYear_returns400() throws Exception {
        String body = """
                {"startDate": "2025-11-01"}
                """;

        mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ── GET /api/seasons/{id} ─────────────────────────────────────────────────

    @Test
    void getById_returnsSeason() throws Exception {
        Season s = mkSeason(2025);

        mockMvc.perform(get("/api/seasons/{id}", s.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.getId()))
                .andExpect(jsonPath("$.year").value(2025));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/seasons/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /api/seasons/year/{year} ──────────────────────────────────────────

    @Test
    void getByYear_returnsSeason() throws Exception {
        mkSeason(2025);

        mockMvc.perform(get("/api/seasons/year/2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2025));
    }

    @Test
    void getByYear_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/seasons/year/9999"))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/seasons/{id} ─────────────────────────────────────────────────

    @Test
    void update_returnsUpdatedSeason() throws Exception {
        Season s = mkSeason(2025);

        String body = """
                {"year": 2025, "startDate": "2024-10-01", "endDate": "2025-05-31"}
                """;

        mockMvc.perform(put("/api/seasons/{id}", s.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2025));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"year": 2025}
                """;

        mockMvc.perform(put("/api/seasons/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/seasons/{id} ──────────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        Season s = mkSeason(2025);

        mockMvc.perform(delete("/api/seasons/{id}", s.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/seasons/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
