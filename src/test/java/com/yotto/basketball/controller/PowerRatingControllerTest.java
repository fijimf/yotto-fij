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
        addRating(team, modelType, rating, date, null);
    }

    private void addRating(Team team, String modelType, double rating, LocalDate date, Integer rank) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(date);
        s.setRating(rating);
        s.setRank(rank);
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
    void masseyLeaderboard_withData_orderedByRankAscendingWithFullRowShape() throws Exception {
        // teamA is rank 1 with rating 5.0; teamB is rank 2 with rating 2.0
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE, 1);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE, 2);

        mockMvc.perform(get("/api/power-ratings/2025/massey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Rank-1 row first
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].rating").value(5.0))
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].teamName").value("Alabama"))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE))
                .andExpect(jsonPath("$[0].gamesPlayed").value(10))
                .andExpect(jsonPath("$[0].snapshotDate").value(SNAP_DATE.toString()))
                // Rank-2 row second
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].rating").value(2.0))
                .andExpect(jsonPath("$[1].teamId").value(teamB.getId()));
    }

    @Test
    void masseyLeaderboard_noDateParam_defaultsToLatestSnapshotDate() throws Exception {
        // Older date — should NOT be returned when no date param specified
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE.minusDays(7), 1);
        // Newer date — must be returned
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 6.5, SNAP_DATE, 1);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE, 2);

        mockMvc.perform(get("/api/power-ratings/2025/massey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotDate").value(SNAP_DATE.toString()))
                .andExpect(jsonPath("$[0].rating").value(6.5));
    }

    @Test
    void masseyLeaderboard_withDateParam_filtersToThatDate() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE, 1);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE, 2);
        // Different-date rows that should NOT match
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 7.0, SNAP_DATE.plusDays(7), 1);

        mockMvc.perform(get("/api/power-ratings/2025/massey")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotDate").value(SNAP_DATE.toString()))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE))
                .andExpect(jsonPath("$[0].rating").value(5.0));
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
    void masseyTotalsLeaderboard_withData_orderedByRankAscending() throws Exception {
        addRating(teamA, MasseyRatingService.MODEL_TYPE_TOTALS, 8.0, SNAP_DATE, 1);
        addRating(teamB, MasseyRatingService.MODEL_TYPE_TOTALS, 3.0, SNAP_DATE, 2);

        mockMvc.perform(get("/api/power-ratings/2025/massey-totals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE_TOTALS))
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].rating").value(8.0))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].teamId").value(teamB.getId()))
                .andExpect(jsonPath("$[1].rating").value(3.0))
                .andExpect(jsonPath("$[1].rank").value(2));
    }

    // ── GET /api/power-ratings/{year}/bradley-terry ───────────────────────────

    @Test
    void bradleyTerryLeaderboard_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/bradley-terry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bradleyTerryLeaderboard_withData_orderedByRankAscending() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE, 1.0, SNAP_DATE, 1);
        addRating(teamB, BradleyTerryRatingService.MODEL_TYPE, 0.5, SNAP_DATE, 2);

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(BradleyTerryRatingService.MODEL_TYPE))
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].rating").value(1.0))
                .andExpect(jsonPath("$[1].teamId").value(teamB.getId()))
                .andExpect(jsonPath("$[1].rating").value(0.5));
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
    void bradleyTerryWeightedLeaderboard_withData_orderedByRankAscending() throws Exception {
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 1.2, SNAP_DATE, 1);
        addRating(teamB, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 0.4, SNAP_DATE, 2);

        mockMvc.perform(get("/api/power-ratings/2025/bradley-terry-weighted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelType").value(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED))
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].rating").value(1.2))
                .andExpect(jsonPath("$[1].teamId").value(teamB.getId()))
                .andExpect(jsonPath("$[1].rating").value(0.4));
    }

    // ── GET /api/power-ratings/{year}/massey/team/{teamId} ────────────────────

    @Test
    void masseyTeamTimeSeries_returnedInAscendingDateOrderWithFullRowShape() throws Exception {
        // Insert in non-chronological order to verify the controller sorts.
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 6.0, SNAP_DATE.plusDays(7));
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        // Other team / other model: must not appear in this team's time series
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 9.9, SNAP_DATE);
        addRating(teamA, BradleyTerryRatingService.MODEL_TYPE, 1.0, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/massey/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotDate").value(SNAP_DATE.toString()))
                .andExpect(jsonPath("$[0].rating").value(5.0))
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].modelType").value(MasseyRatingService.MODEL_TYPE))
                .andExpect(jsonPath("$[1].snapshotDate").value(SNAP_DATE.plusDays(7).toString()))
                .andExpect(jsonPath("$[1].rating").value(6.0));
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
    void snapshotDates_returnsDistinctDatesAscending() throws Exception {
        // Two teams on SNAP_DATE plus a later date → endpoint should distinct + sort.
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 5.0, SNAP_DATE);
        addRating(teamB, MasseyRatingService.MODEL_TYPE, 2.0, SNAP_DATE);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 6.0, SNAP_DATE.plusDays(7));

        mockMvc.perform(get("/api/power-ratings/2025/dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value(SNAP_DATE.toString()))
                .andExpect(jsonPath("$[1]").value(SNAP_DATE.plusDays(7).toString()));
    }

    // ── GET /api/power-ratings/{year}/params ──────────────────────────────────

    @Test
    void params_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/power-ratings/9999/params"))
                .andExpect(status().isNotFound());
    }

    @Test
    void params_returnsParamsByModel_withFullValueShape() throws Exception {
        addParam(MasseyRatingService.MODEL_TYPE, "hca", 2.5, SNAP_DATE);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", 140.0, SNAP_DATE);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 1.5, SNAP_DATE);
        addParam(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.1, SNAP_DATE);
        addParam(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", 0.12, SNAP_DATE);

        mockMvc.perform(get("/api/power-ratings/2025/params"))
                .andExpect(status().isOk())
                // Massey: one HCA param with value 2.5 on SNAP_DATE
                .andExpect(jsonPath("$.massey.length()").value(1))
                .andExpect(jsonPath("$.massey[0].paramName").value("hca"))
                .andExpect(jsonPath("$.massey[0].value").value(2.5))
                .andExpect(jsonPath("$.massey[0].date").value(SNAP_DATE.toString()))
                // Massey Totals: both intercept and hca_total persisted with correct values
                .andExpect(jsonPath("$.masseyTotals.length()").value(2))
                .andExpect(jsonPath("$.masseyTotals[?(@.paramName == 'intercept')].value")
                        .value(org.hamcrest.Matchers.contains(140.0)))
                .andExpect(jsonPath("$.masseyTotals[?(@.paramName == 'hca_total')].value")
                        .value(org.hamcrest.Matchers.contains(1.5)))
                // BT and BT-Weighted
                .andExpect(jsonPath("$.bradleyTerry[0].paramName").value("hca"))
                .andExpect(jsonPath("$.bradleyTerry[0].value").value(0.1))
                .andExpect(jsonPath("$.bradleyTerryWeighted[0].value").value(0.12));
    }
}
