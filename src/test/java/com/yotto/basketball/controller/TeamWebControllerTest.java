package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class TeamWebControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;

    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    @Autowired TeamGameStatsRepository boxRepo;
    @Autowired TeamStatSnapshotRepository statSnapshotRepo;
    @Autowired com.yotto.basketball.service.TeamStatTimeSeriesService teamStatTimeSeriesService;

    Season season;
    Conference sec;
    Team teamA, teamB;

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = new Conference();
        sec.setName("SEC");
        sec.setAbbreviation("SEC");
        sec.setEspnId("sec-1");
        conferenceRepo.save(sec);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private SeasonStatistics mkStats(Team team, Conference conf, int wins, int losses,
                                     int confWins, int confLosses) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(conf);
        ss.setWins(wins);
        ss.setLosses(losses);
        ss.setConferenceWins(confWins);
        ss.setConferenceLosses(confLosses);
        ss.setCalcWins(wins);
        ss.setCalcLosses(losses);
        ss.setCalcConferenceWins(confWins);
        ss.setCalcConferenceLosses(confLosses);
        return statsRepo.save(ss);
    }

    private Game mkGame(Team home, Team away, int homeScore, int awayScore,
                        Game.GameStatus status, LocalDate date, boolean neutral) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(status);
        g.setNeutralSite(neutral);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    private void enroll(Team team, Conference conf) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conf);
        m.setSeason(season);
        membershipRepo.save(m);
    }

    private void mkBox(Game game, Team team, String homeAway,
                       int fgm, int fga, int fg3m, int fg3a, int ftm, int fta,
                       int orb, int drb, int to) {
        TeamGameStats s = new TeamGameStats();
        s.setGame(game);
        s.setTeam(team);
        s.setHomeAway(homeAway);
        s.setFgMade(fgm);
        s.setFgAttempted(fga);
        s.setFg3Made(fg3m);
        s.setFg3Attempted(fg3a);
        s.setFtMade(ftm);
        s.setFtAttempted(fta);
        s.setOffensiveReb(orb);
        s.setDefensiveReb(drb);
        s.setTurnovers(to);
        // Full standard ESPN block so the box-score gate passes.
        s.setAssists(Math.max(0, fgm - 8));
        s.setSteals(Math.max(0, to - 3));
        s.setBlocks(Math.max(0, orb - 4));
        s.setFouls(15);
        s.setScrapeDate(LocalDateTime.now());
        boxRepo.save(s);
    }

    /** A final game between A and B with both box scores present, then run the calc. */
    private void seedBoxScoresAndCalc() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        Game g = mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), false);
        mkBox(g, teamA, "HOME", 30, 60, 5, 20, 15, 20, 10, 25, 12);
        mkBox(g, teamB, "AWAY", 25, 55, 8, 25, 12, 16, 8, 22, 15);
        teamStatTimeSeriesService.calculateAndStoreForSeason(2025);
    }

    private static TeamWebController.StatRow findRow(TeamWebController.TeamStatPanel panel, String statName) {
        return panel.groups().stream().flatMap(g -> g.rows().stream())
                .filter(r -> r.statName().equals(statName)).findFirst().orElse(null);
    }

    // ── GET /teams ────────────────────────────────────────────────────────────

    @Test
    void teamsList_groupsTeamsByConference() throws Exception {
        mkStats(teamA, sec, 20, 5, 14, 4);
        mkStats(teamB, sec, 15, 10, 10, 8);

        MvcResult res = mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/teams"))
                .andExpect(model().attribute("currentPage", "teams"))
                .andExpect(model().attribute("teamCount", 2))
                .andExpect(model().attribute("seasonYear", 2025))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>> groups =
                (Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>>)
                        res.getModelAndView().getModel().get("conferenceGroups");
        assertThat(groups).hasSize(1);
        TeamWebController.ConferenceInfo onlyConf = groups.keySet().iterator().next();
        assertThat(onlyConf.name()).isEqualTo("SEC");
        assertThat(groups.get(onlyConf)).hasSize(2);
    }

    @Test
    void teamsList_teamsWithoutStats_groupAsIndependent() throws Exception {
        // Neither team has a SeasonStatistics row → both go to "Independent"
        MvcResult res = mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("teamCount", 2))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>> groups =
                (Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>>)
                        res.getModelAndView().getModel().get("conferenceGroups");
        assertThat(groups).hasSize(1);
        assertThat(groups.keySet().iterator().next().name()).isEqualTo("Independent");
    }

    @Test
    void teamsList_noSeasons_returnsPageWithTeamsAndNullSeasonYear() throws Exception {
        seasonRepo.deleteAll();

        mockMvc.perform(get("/teams"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("teamCount", 2))
                .andExpect(model().attribute("seasonYear", (Object) null));
    }

    @Test
    void teamsList_recordFieldsPreferCalcOverScraped() throws Exception {
        SeasonStatistics ss = mkStats(teamA, sec, 20, 5, 14, 4);
        // Override calc fields to different values; the list should show the calc values.
        ss.setCalcWins(22);
        ss.setCalcLosses(3);
        statsRepo.save(ss);

        MvcResult res = mockMvc.perform(get("/teams")).andReturn();

        @SuppressWarnings("unchecked")
        Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>> groups =
                (Map<TeamWebController.ConferenceInfo, List<TeamWebController.TeamSummary>>)
                        res.getModelAndView().getModel().get("conferenceGroups");
        TeamWebController.TeamSummary alabama = groups.values().stream().flatMap(List::stream)
                .filter(ts -> "Alabama".equals(ts.name())).findFirst().orElseThrow();
        assertThat(alabama.record()).isEqualTo("22-3");
    }

    // ── GET /teams/{id} ───────────────────────────────────────────────────────

    @Test
    void teamDetail_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/teams/{id}", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void teamDetail_returnsPageWithCoreAttributes() throws Exception {
        mkStats(teamA, sec, 20, 5, 14, 4);
        mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), false);

        mockMvc.perform(get("/teams/{id}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/team-detail"))
                .andExpect(model().attribute("teamId", teamA.getId()))
                .andExpect(model().attribute("currentSeasonYear", 2025))
                .andExpect(model().attributeExists("schedule"))
                .andExpect(model().attributeExists("seasons"))
                .andExpect(model().attributeExists("team"))
                .andExpect(model().attributeExists("currentConference"));
    }

    @Test
    void teamDetail_buildsScheduleWithWinLossAndOpponent() throws Exception {
        mkStats(teamA, sec, 20, 5, 14, 4);
        mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), false);
        mkGame(teamB, teamA, 75, 60, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 17), false);

        MvcResult res = mockMvc.perform(get("/teams/{id}", teamA.getId()))
                .andExpect(status().isOk())
                .andReturn();

        TeamWebController.SeasonSchedule schedule = (TeamWebController.SeasonSchedule)
                res.getModelAndView().getModel().get("schedule");
        assertThat(schedule).isNotNull();
        assertThat(schedule.year()).isEqualTo(2025);
        assertThat(schedule.games()).hasSize(2);
        // Home win against teamB → result "W", location "vs"
        TeamWebController.GameRow homeGame = schedule.games().stream()
                .filter(r -> "vs".equals(r.location())).findFirst().orElseThrow();
        assertThat(homeGame.opponentName()).isEqualTo("Auburn");
        assertThat(homeGame.result()).isEqualTo("W");
        assertThat(homeGame.teamScore()).isEqualTo(80);
        assertThat(homeGame.opponentScore()).isEqualTo(70);
        // Away loss → result "L", location "@"
        TeamWebController.GameRow awayGame = schedule.games().stream()
                .filter(r -> "@".equals(r.location())).findFirst().orElseThrow();
        assertThat(awayGame.result()).isEqualTo("L");
        assertThat(awayGame.teamScore()).isEqualTo(60);
        assertThat(awayGame.opponentScore()).isEqualTo(75);
    }

    @Test
    void teamDetail_neutralSiteGame_locationIsN() throws Exception {
        mkStats(teamA, sec, 1, 0, 0, 0);
        mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), true);

        MvcResult res = mockMvc.perform(get("/teams/{id}", teamA.getId())).andReturn();

        TeamWebController.SeasonSchedule schedule = (TeamWebController.SeasonSchedule)
                res.getModelAndView().getModel().get("schedule");
        assertThat(schedule.games()).hasSize(1);
        assertThat(schedule.games().get(0).location()).isEqualTo("N");
    }

    @Test
    void teamDetail_teamWithNoGames_returnsScheduleNull() throws Exception {
        // teamA has no games — currentSeason is null, schedule is null
        MvcResult res = mockMvc.perform(get("/teams/{id}", teamA.getId()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getModelAndView().getModel().get("schedule")).isNull();
        assertThat(res.getModelAndView().getModel().get("currentSeasonYear")).isNull();
    }

    // ── GET /teams/{id}/season/{year} (HTMX fragment) ─────────────────────────

    @Test
    void teamSeasonSchedule_fragment_returnsFragmentView() throws Exception {
        mkStats(teamA, sec, 5, 2, 3, 1);
        mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), false);

        mockMvc.perform(get("/teams/{id}/season/{year}", teamA.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/team-season :: season-panel"))
                .andExpect(model().attributeExists("schedule"));
    }

    @Test
    void teamSeasonSchedule_unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/teams/{id}/season/{year}", teamA.getId(), 9999))
                .andExpect(status().isNotFound());
    }

    // ── Team Profile stat panel ───────────────────────────────────────────────

    @Test
    void teamDetail_noStatData_statPanelNull() throws Exception {
        mkStats(teamA, sec, 1, 0, 0, 0);
        mkGame(teamA, teamB, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 1, 10), false);

        MvcResult res = mockMvc.perform(get("/teams/{id}", teamA.getId()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(res.getModelAndView().getModel().get("statPanel")).isNull();
    }

    @Test
    void teamDetail_includesStatPanel_withGroupsRanksAndValues() throws Exception {
        seedBoxScoresAndCalc();

        MvcResult res = mockMvc.perform(get("/teams/{id}", teamA.getId()))
                .andExpect(status().isOk())
                .andReturn();

        TeamWebController.TeamStatPanel panel = (TeamWebController.TeamStatPanel)
                res.getModelAndView().getModel().get("statPanel");
        assertThat(panel).isNotNull();
        assertThat(panel.year()).isEqualTo(2025);
        assertThat(panel.gamesPlayed()).isEqualTo(1);
        assertThat(panel.groups()).isNotEmpty();

        // eFG% for A = (30 + 0.5×5) / 60 = 0.5417 → "54.2%"; A leads B → rank 1 of 2.
        TeamWebController.StatRow efg = findRow(panel, "efg_pct");
        assertThat(efg).isNotNull();
        assertThat(efg.formattedValue()).isEqualTo("54.2%");
        assertThat(efg.rank()).isEqualTo(1);
        assertThat(efg.fieldSize()).isEqualTo(2);
        assertThat(efg.rankDisplay()).isEqualTo("#1 of 2");
        assertThat(efg.percentile()).isEqualTo(100);
        assertThat(efg.higherIsBetter()).isTrue();

        // Offensive Rtg = 100 × 80 / 71.5 possessions = 111.9 (DECIMAL_1).
        TeamWebController.StatRow offRtg = findRow(panel, "off_efficiency");
        assertThat(offRtg.formattedValue()).isEqualTo("111.9");

        // Groups appear in the panel display order. No TeamSeasonStatSnapshot is
        // seeded here, so the Scoring group is absent and Shooting leads; Shooting
        // precedes Efficiency under the new ordering.
        List<String> headers = panel.groups().stream()
                .map(TeamWebController.StatGroup::header).toList();
        assertThat(headers).doesNotContain("Scoring");
        assertThat(headers.get(0)).isEqualTo("Shooting");
        assertThat(headers.indexOf("Shooting")).isLessThan(headers.indexOf("Efficiency"));
    }

    @Test
    void teamSeasonStatPanel_fragment_returnsPanelView() throws Exception {
        seedBoxScoresAndCalc();

        mockMvc.perform(get("/teams/{id}/season/{year}/stats-panel", teamA.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/team-stat-panel :: panel"))
                .andExpect(model().attributeExists("statPanel"));
    }

    @Test
    void teamSeasonStatPanel_unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/teams/{id}/season/{year}/stats-panel", teamA.getId(), 9999))
                .andExpect(status().isNotFound());
    }
}
