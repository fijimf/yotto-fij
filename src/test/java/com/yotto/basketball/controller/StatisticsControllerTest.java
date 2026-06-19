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
    @Autowired TeamGameStatsRepository boxRepo;
    @Autowired com.yotto.basketball.service.TeamStatTimeSeriesService teamStatTimeSeriesService;

    Season season;
    Team teamA, teamB;
    Conference sec;

    @BeforeEach
    void setUp() {

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
        addGameOn(LocalDateTime.of(2025, 1, 15, 20, 0));
    }

    private void addGameOn(LocalDateTime when) {
        Game g = new Game();
        g.setHomeTeam(teamA);
        g.setAwayTeam(teamB);
        g.setHomeScore(80);
        g.setAwayScore(70);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(when);
        gameRepo.save(g);
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
        s.setAssists(Math.max(0, fgm - 8));
        s.setSteals(Math.max(0, to - 3));
        s.setBlocks(Math.max(0, orb - 4));
        s.setFouls(15);
        s.setScrapeDate(LocalDateTime.now());
        boxRepo.save(s);
    }

    /** A FINAL game with both box scores, then run the box-score time-series calc. */
    private void seedBoxScoreTrajectory() {
        Game g = new Game();
        g.setHomeTeam(teamA);
        g.setAwayTeam(teamB);
        g.setHomeScore(80);
        g.setAwayScore(70);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 10, 20, 0));
        gameRepo.save(g);
        mkBox(g, teamA, "HOME", 30, 60, 5, 20, 15, 20, 10, 25, 12);
        mkBox(g, teamB, "AWAY", 25, 55, 8, 25, 12, 16, 8, 22, 15);
        teamStatTimeSeriesService.calculateAndStoreForSeason(2025);
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

    // ── GET /api/statistics/team/{teamId}/season/{year}/stat/{statName} ───────

    @Test
    void teamStatTrajectory_afterCalc_returnsOrderedPoints() throws Exception {
        seedBoxScoreTrajectory();

        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}/stat/{statName}",
                        teamA.getId(), 2025, "efg_pct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].snapshotDate").value("2025-01-10"))
                // eFG% for A = (30 + 0.5×5) / 60 = 0.5417
                .andExpect(jsonPath("$[0].value").value(org.hamcrest.Matchers.closeTo(0.5417, 0.001)))
                .andExpect(jsonPath("$[0].rank").value(1));
    }

    @Test
    void teamStatTrajectory_unknownStat_returnsEmptyList() throws Exception {
        seedBoxScoreTrajectory();

        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}/stat/{statName}",
                        teamA.getId(), 2025, "not_a_real_stat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void teamStatTrajectory_unknownSeason_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/statistics/team/{teamId}/season/{year}/stat/{statName}",
                        teamA.getId(), 9999, "efg_pct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
        // Two games on different dates so date filtering is observable.
        addGameOn(LocalDateTime.of(2025, 1, 10, 20, 0));
        addGameOn(LocalDateTime.of(2025, 1, 17, 20, 0));

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        // After date=2025-01-10 each team has played exactly 1 game.
        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025)
                        .param("date", "2025-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].gamesPlayed").value(1))
                .andExpect(jsonPath("$[1].gamesPlayed").value(1));

        // After date=2025-01-17 each team has played 2 games — confirms the
        // earlier query did not just return all snapshots.
        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025)
                        .param("date", "2025-01-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].gamesPlayed").value(2))
                .andExpect(jsonPath("$[1].gamesPlayed").value(2));
    }

    @Test
    void seasonSnapshots_withNoDateParam_usesLatestDate() throws Exception {
        // Two distinct snapshot dates; no-date call must pick the later one.
        addGameOn(LocalDateTime.of(2025, 1, 10, 20, 0));
        addGameOn(LocalDateTime.of(2025, 1, 17, 20, 0));

        mockMvc.perform(post("/api/statistics/recalculate/{year}", 2025))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/statistics/season/{year}/snapshots", 2025))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotDate").value("2025-01-17"))
                .andExpect(jsonPath("$[0].gamesPlayed").value(2));
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
