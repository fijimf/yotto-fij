package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.StatisticsTimeSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class StatisticsControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Team teamA, teamB;
    Conference sec;

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

        sec = new Conference();
        sec.setName("SEC");
        sec.setEspnId("sec1");
        conferenceRepo.save(sec);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");

        enroll(teamA);
        enroll(teamB);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void enroll(Team team) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(sec);
        m.setSeason(season);
        membershipRepo.save(m);
    }

    private void addGame() {
        Game g = new Game();
        g.setHomeTeam(teamA);
        g.setAwayTeam(teamB);
        g.setHomeScore(80);
        g.setAwayScore(70);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        gameRepo.save(g);
    }

    // ── GET /api/statistics/team/{teamId}/season/{year} ───────────────────────

    @Test
    void teamTimeSeries_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}",
                        teamA.getId(), 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void teamTimeSeries_noData_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}",
                        teamA.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void teamTimeSeries_afterRecalc_returnsSnapshots() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}",
                        teamA.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gamesPlayed").value(1))
                .andExpect(jsonPath("$[0].wins").value(1))
                .andExpect(jsonPath("$[0].losses").value(0))
                .andExpect(jsonPath("$[0].snapshotDate").exists())
                .andExpect(jsonPath("$[0].teamId").value(teamA.getId()))
                .andExpect(jsonPath("$[0].teamName").value("Alabama"));
    }

    // ── GET /api/statistics/season/{year}/snapshots ───────────────────────────

    @Test
    void seasonSnapshots_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 9999))
                .andExpect(status().isNotFound());
    }

    @Test
    void seasonSnapshots_noData_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void seasonSnapshots_withDate_returnsSnapshotsForThatDate() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025)
                        .param("date", "2025-01-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)); // one per team
    }

    @Test
    void seasonSnapshots_withNoDateParam_usesLatestDate() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /api/statistics/season/{year}/population ──────────────────────────

    @Test
    void seasonPopulation_seasonNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/statistics/season/{year}/population", 9999))
                .andExpect(status().isNotFound());
    }

    @Test
    void seasonPopulation_noData_returnsEmptyMap() throws Exception {
        mockMvc.perform(get("/api/statistics/season/{year}/population", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void seasonPopulation_afterRecalc_returnsMeanAndStddev() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/season/{year}/population", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.win_pct").exists())
                .andExpect(jsonPath("$.win_pct.mean").exists())
                .andExpect(jsonPath("$.win_pct.teamCount").value(2));
    }

    // ── POST /api/statistics/recalculate/{year} ───────────────────────────────

    @Test
    void recalculate_returns200WithStatus() throws Exception {
        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.year").value("2025"));
    }

    // ── SnapshotDto and PopDto fields ─────────────────────────────────────────

    @Test
    void snapshotDto_allFieldsPresent() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}",
                        teamA.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshotDate").exists())
                .andExpect(jsonPath("$[0].gamesPlayed").exists())
                .andExpect(jsonPath("$[0].wins").exists())
                .andExpect(jsonPath("$[0].losses").exists())
                .andExpect(jsonPath("$[0].winPct").exists())
                .andExpect(jsonPath("$[0].meanPtsFor").exists())
                .andExpect(jsonPath("$[0].meanPtsAgainst").exists())
                .andExpect(jsonPath("$[0].teamId").exists())
                .andExpect(jsonPath("$[0].teamName").exists());
    }

    @Test
    void popDto_allFieldsPresent() throws Exception {
        addGame();

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/season/{year}/population", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.win_pct.mean").exists())
                .andExpect(jsonPath("$.win_pct.stddev").exists())
                .andExpect(jsonPath("$.win_pct.min").exists())
                .andExpect(jsonPath("$.win_pct.max").exists())
                .andExpect(jsonPath("$.win_pct.teamCount").exists());
    }
}
