package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class StatsWebControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;

    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Team teamA;

    static final LocalDate SNAP_EARLY = LocalDate.of(2025, 1, 10);
    static final LocalDate SNAP_LATE  = LocalDate.of(2025, 1, 24);

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        teamA = new Team();
        teamA.setName("Alabama");
        teamA.setEspnId("TA");
        teamA.setAbbreviation("ALA");
        teamA.setActive(true);
        teamRepo.save(teamA);
    }

    private TeamSeasonStatSnapshot mkSnapshot(LocalDate date) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(teamA);
        s.setSeason(season);
        s.setSnapshotDate(date);
        s.setGamesPlayed(5);
        s.setWinPct(0.6);
        return snapshotRepo.save(s);
    }

    private SeasonPopulationStat mkPopStat(LocalDate date, String name) {
        SeasonPopulationStat p = new SeasonPopulationStat();
        p.setSeason(season);
        p.setStatDate(date);
        p.setStatName(name);
        p.setPopMean(0.5);
        p.setPopStddev(0.1);
        p.setPopMin(0.0);
        p.setPopMax(1.0);
        p.setTeamCount(10);
        return popStatRepo.save(p);
    }

    // ── GET /seasons/{year}/stats ─────────────────────────────────────────────

    @Test
    void seasonStats_unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/seasons/{year}/stats", 9999))
                .andExpect(status().isNotFound());
    }

    @Test
    void seasonStats_noSnapshots_hasDataIsFalse() throws Exception {
        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/season-stats"))
                .andExpect(model().attribute("hasData", false))
                .andExpect(model().attribute("snapshots", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(model().attribute("selectedDate", (Object) null));
    }

    @Test
    void seasonStats_noDateParam_usesLatestSnapshotDate() throws Exception {
        mkSnapshot(SNAP_EARLY);
        mkSnapshot(SNAP_LATE);
        mkPopStat(SNAP_LATE, "win_pct");

        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true))
                .andExpect(model().attribute("selectedDate", SNAP_LATE))
                .andExpect(model().attribute("latestDate", SNAP_LATE))
                .andExpect(model().attribute("snapshots", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void seasonStats_explicitDate_returnsSnapshotsForThatDate() throws Exception {
        mkSnapshot(SNAP_EARLY);
        mkSnapshot(SNAP_LATE);
        mkPopStat(SNAP_EARLY, "win_pct");

        mockMvc.perform(get("/seasons/{year}/stats", 2025)
                        .param("date", SNAP_EARLY.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedDate", SNAP_EARLY))
                .andExpect(model().attribute("latestDate", SNAP_LATE))
                .andExpect(model().attribute("hasData", true))
                .andExpect(model().attribute("snapshots", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void seasonStats_populationStatsKeyedByStatName() throws Exception {
        mkSnapshot(SNAP_LATE);
        mkPopStat(SNAP_LATE, "win_pct");
        mkPopStat(SNAP_LATE, "mean_pts_for");

        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                .andExpect(model().attribute("popStats",
                        org.hamcrest.Matchers.aMapWithSize(2)))
                .andExpect(model().attribute("popStats",
                        org.hamcrest.Matchers.hasKey("win_pct")))
                .andExpect(model().attribute("popStats",
                        org.hamcrest.Matchers.hasKey("mean_pts_for")));
    }

    @Test
    void seasonStats_allSeasonsSortedDescending() throws Exception {
        Season older = new Season();
        older.setYear(2024);
        older.setStartDate(LocalDate.of(2023, 11, 1));
        older.setEndDate(LocalDate.of(2024, 4, 30));
        seasonRepo.save(older);

        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                .andExpect(model().attribute("allSeasons",
                        org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.hasProperty("year", org.hamcrest.Matchers.equalTo(2025)),
                                org.hamcrest.Matchers.hasProperty("year", org.hamcrest.Matchers.equalTo(2024)))));
    }

    // ── GET /seasons/{year}/stats/table (HTMX fragment) ───────────────────────

    @Test
    void seasonStatsTable_fragment_unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/seasons/{year}/stats/table", 9999))
                .andExpect(status().isNotFound());
    }

    @Test
    void seasonStatsTable_fragment_returnsFragmentView() throws Exception {
        mkSnapshot(SNAP_LATE);

        mockMvc.perform(get("/seasons/{year}/stats/table", 2025))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/season-stats-table :: stats-table"))
                .andExpect(model().attribute("selectedDate", SNAP_LATE))
                .andExpect(model().attribute("snapshots", org.hamcrest.Matchers.hasSize(1)));
    }
}
