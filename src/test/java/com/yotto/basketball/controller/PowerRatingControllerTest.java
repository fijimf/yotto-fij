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
class PowerRatingControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Season season;
    Team teamA, teamB;
    static final LocalDate SNAP_DATE = LocalDate.of(2025, 1, 14);

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

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void addRating(Team team, String modelType, double rating, LocalDate date) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(date);
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void addParam(String modelType, String paramName, double value, LocalDate date) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setSnapshotDate(date);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }

    // ── GET /api/power-ratings/{year}/massey ──────────────────────────────────

    @Test
    void masseyLeaderboard_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/massey"))
                .andExpect(status().isNotFound());
    }

    @Test
    void masseyLeaderboard_noData_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/power-ratings/2025/massey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void masseyLeaderboard_withData_returnsRatings() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/massey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void masseyLeaderboard_withDateParam_filtersToDate() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/massey")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE));
    }

    // ── GET /api/power-ratings/{year}/massey-totals ───────────────────────────

    @Test
    void masseyTotalsLeaderboard_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/massey-totals"))
                .andExpect(status().isNotFound());
    }

    @Test
    void masseyTotalsLeaderboard_noData_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/power-ratings/2025/massey-totals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void masseyTotalsLeaderboard_withData_returnsRatings() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE_TOTALS, 8.0, SNAP_DATE);
        addRating(teamB, MasseyRatingService.MODEL_TYPE_TOTALS, 3.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/massey-totals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE_TOTALS));
    }

    // ── GET /api/power-ratings/{year}/bradley-terry ───────────────────────────

    @Test
    void bradleyTerryLeaderboard_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/bradley-terry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bradleyTerryLeaderboard_withData_returnsRatings() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE, 1.0, SNAP_DATE);
        addRating(teamB, BradleyTerryRatingService.MODEL_TYPE, 0.5, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /api/power-ratings/{year}/bradley-terry-weighted ─────────────────

    @Test
    void bradleyTerryWeightedLeaderboard_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/bradley-terry-weighted"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bradleyTerryWeightedLeaderboard_noData_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry-weighted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void bradleyTerryWeightedLeaderboard_withData_returnsRatings() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 1.2, SNAP_DATE);
        addRating(teamB, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 0.4, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry-weighted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED));
    }

    // ── GET /api/power-ratings/{year}/massey/team/{teamId} ────────────────────

    @Test
    void masseyTeamTimeSeries_returnsTimeSeries() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 6.0, SNAP_DATE.plusDays(7));

        mockMvc.perform(get("/api/power-ratings/2025/massey/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void masseyTeamTimeSeries_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/massey/team/{teamId}", teamA.getId()))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/power-ratings/{year}/massey-totals/team/{teamId} ────────────

    @Test
    void masseyTotalsTeamTimeSeries_returnsTimeSeries() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE_TOTALS, 8.0, SNAP_DATE);
        addRating(teamA, MasseyRatingService.MODEL_TYPE_TOTALS, 9.0, SNAP_DATE.plusDays(7));

        mockMvc.perform(get("/api/power-ratings/2025/massey-totals/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /api/power-ratings/{year}/bradley-terry/team/{teamId} ────────────

    @Test
    void bradleyTerryTeamTimeSeries_returnsTimeSeries() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE, 1.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/power-ratings/{year}/bradley-terry-weighted/team/{teamId} ───

    @Test
    void bradleyTerryWeightedTeamTimeSeries_returnsTimeSeries() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 1.2, SNAP_DATE);
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 1.4, SNAP_DATE.plusDays(7));

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry-weighted/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /api/power-ratings/{year}/dates ───────────────────────────────────

    @Test
    void snapshotDates_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/dates"))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotDates_returnsDistinctDates() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/power-ratings/{year}/params ──────────────────────────────────

    @Test
    void params_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/params"))
                .andExpect(status().isNotFound());
    }

    @Test
    void params_returnsParamsByModel() throws Exception {
        addParam(MasseyRatingService.MODEL_TYPE, "hca", 2.5, SNAP_DATE);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", 140.0, SNAP_DATE);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 1.5, SNAP_DATE);
        addParam(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.1, SNAP_DATE);
        addParam(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", 0.12, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.massey").isArray())
                .andExpect(jsonPath("$.massey.length()").value(1))
                .andExpect(jsonPath("$.masseyTotals").isArray())
                .andExpect(jsonPath("$.masseyTotals.length()").value(2))
                .andExpect(jsonPath("$.bradleyTerry").isArray())
                .andExpect(jsonPath("$.bradleyTerry.length()").value(1))
                .andExpect(jsonPath("$.bradleyTerryWeighted").isArray())
                .andExpect(jsonPath("$.bradleyTerryWeighted.length()").value(1));
    }
}
