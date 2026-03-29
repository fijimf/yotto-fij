package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.dto.ComprehensiveRankingRow;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
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
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ComprehensiveRankingsControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Conference sec;
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

        sec = new Conference();
        sec.setName("Southeastern Conference");
        sec.setAbbreviation("SEC");
        sec.setEspnId("sec1");
        conferenceRepo.save(sec);

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

    private void addSeasonStats(Team team) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(sec);
        statsRepo.save(ss);
    }

    private void addStatSnapshot(Team team, int wins, int losses, double winPct,
                                  double ptsFor, double ptsAgainst, double margin, double rpi) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP_DATE);
        s.setGamesPlayed(wins + losses);
        s.setWins(wins);
        s.setLosses(losses);
        s.setWinPct(winPct);
        s.setMeanPtsFor(ptsFor);
        s.setMeanPtsAgainst(ptsAgainst);
        s.setMeanMargin(margin);
        s.setRpi(rpi);
        snapshotRepo.save(s);
    }

    private void addRating(Team team, String modelType, double rating) {
        TeamPowerRatingSnapshot r = new TeamPowerRatingSnapshot();
        r.setTeam(team);
        r.setSeason(season);
        r.setModelType(modelType);
        r.setSnapshotDate(SNAP_DATE);
        r.setRating(rating);
        r.setGamesPlayed(20);
        r.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(r);
    }

    // ── GET /rankings/comprehensive ───────────────────────────────────────────

    @Test
    void noSeasons_returns200WithEmptyState() throws Exception {
        seasonRepo.deleteAll();
        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/comprehensive-rankings"));
    }

    @Test
    void withSeason_noData_hasDataFalse() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", false))
                .andExpect(model().attributeExists("rows"));
    }

    @Test
    void withData_rowsContainBothTeams() throws Exception {
        addSeasonStats(teamA);
        addSeasonStats(teamB);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        addStatSnapshot(teamB, 15, 10, 0.600, 72.0, 68.0,  4.0, 0.540);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);
        addRating(teamB, MasseyRatingService.MODEL_TYPE,  4.0);

        // Verify both teams appear and Alabama (higher Massey) is first
        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true))
                .andExpect(model().attributeExists("rows"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).team().getName()).isEqualTo("Alabama");
    }

    @Test
    void teamWithNoRating_masseyRatingIsNull() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        // no addRating call — teamA has no Massey snapshot

        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).masseyRating()).isNull();
    }

    @Test
    void conferenceAbbrPopulatedFromSeasonStatistics() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);

        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows.get(0).conferenceAbbr()).isEqualTo("SEC");
    }

    // ── GET /rankings/comprehensive/{year}/table ──────────────────────────────

    @Test
    void tableFragment_unknownYear_returns200() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive/9999/table")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", false))
                .andExpect(model().attributeExists("rows"));
    }

    @Test
    void tableFragment_withData_hasDataTrue() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);

        mockMvc.perform(get("/rankings/comprehensive/2025/table")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true));
    }

    // -- GET /rankings/comprehensive/{year}/scatter-matrix ---------------------

    @Test
    void scatterMatrix_unknownYear_hasDataFalse() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive/9999/scatter-matrix")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", false))
                .andExpect(model().attribute("scatterDataJson", "[]"));
    }

    @Test
    void scatterMatrix_withData_hasDataTrueAndJsonContainsTeam() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);

        var result = mockMvc.perform(get("/rankings/comprehensive/2025/scatter-matrix")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true))
                .andReturn();
        String json = (String) result.getModelAndView().getModel().get("scatterDataJson");
        org.assertj.core.api.Assertions.assertThat(json).contains("Alabama");
        org.assertj.core.api.Assertions.assertThat(json).contains("winPct");
        org.assertj.core.api.Assertions.assertThat(json).contains("massey");
    }
}
