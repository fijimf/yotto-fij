package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatisticsTimeSeriesServiceTest extends BaseIntegrationTest {

    @Autowired StatisticsTimeSeriesService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;

    Season season;
    Conference sec, acc;
    Team teamA, teamB, teamC, teamD;

    @BeforeEach
    void setUp() {
        popStatRepo.deleteAll();
        snapshotRepo.deleteAll();
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

        acc = new Conference();
        acc.setName("ACC");
        acc.setEspnId("acc1");
        conferenceRepo.save(acc);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
        teamC = mkTeam("Clemson", "TC");
        teamD = mkTeam("Duke", "TD");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void enroll(Team team, Conference conf) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conf);
        m.setSeason(season);
        membershipRepo.save(m);
    }

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void seasonNotFound_skipsGracefully() {
        service.calculateAndStoreForSeason(9999);
        assertThat(snapshotRepo.findAll()).isEmpty();
        assertThat(popStatRepo.findAll()).isEmpty();
    }

    @Test
    void singleGame_producesTwoSnapshots() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 15));

        service.calculateAndStoreForSeason(2025);

        List<TeamSeasonStatSnapshot> snaps = snapshotRepo.findAll();
        assertThat(snaps).hasSize(2);
    }

    @Test
    void winPct_calculatedCorrectly() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        enroll(teamC, acc);
        // teamA: 2 wins, 1 loss → win_pct = 2/3
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamC, 75, 65, LocalDate.of(2025, 1, 12));
        mkFinalGame(teamB, teamA, 85, 80, LocalDate.of(2025, 1, 14));

        service.calculateAndStoreForSeason(2025);

        // Get teamA's most recent snapshot
        var teamASnaps = snapshotRepo.findByTeamAndSeason(teamA.getId(), season.getId());
        var latest = teamASnaps.get(teamASnaps.size() - 1);
        assertThat(latest.getWins()).isEqualTo(2);
        assertThat(latest.getLosses()).isEqualTo(1);
        assertThat(latest.getWinPct()).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void snapshotsGrowIncrementallyByDate() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // 3 games on 3 different dates
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 75, 65, LocalDate.of(2025, 1, 15));
        mkFinalGame(teamA, teamB, 90, 85, LocalDate.of(2025, 1, 20));

        service.calculateAndStoreForSeason(2025);

        // teamA gets one snapshot per game date
        var teamASnaps = snapshotRepo.findByTeamAndSeason(teamA.getId(), season.getId());
        assertThat(teamASnaps).hasSize(3);
        assertThat(teamASnaps.get(0).getGamesPlayed()).isEqualTo(1);
        assertThat(teamASnaps.get(1).getGamesPlayed()).isEqualTo(2);
        assertThat(teamASnaps.get(2).getGamesPlayed()).isEqualTo(3);
    }

    @Test
    void meanPtsFor_calculatedCorrectly() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // teamA scores 80 and 90 → mean = 85
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 90, 75, LocalDate.of(2025, 1, 15));

        service.calculateAndStoreForSeason(2025);

        var teamASnaps = snapshotRepo.findByTeamAndSeason(teamA.getId(), season.getId());
        var latest = teamASnaps.get(teamASnaps.size() - 1);
        assertThat(latest.getMeanPtsFor()).isCloseTo(85.0, within(0.001));
        assertThat(latest.getStddevPtsFor()).isCloseTo(Math.sqrt(50), within(0.001));
    }

    @Test
    void rollingWindow_cappedAtTen() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // 11 games: teamA wins first 5, loses last 6
        for (int i = 0; i < 5; i++) {
            mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 1 + i));
        }
        for (int i = 0; i < 6; i++) {
            mkFinalGame(teamB, teamA, 80, 70, LocalDate.of(2025, 1, 10 + i));
        }

        service.calculateAndStoreForSeason(2025);

        var teamASnaps = snapshotRepo.findByTeamAndSeason(teamA.getId(), season.getId());
        var latest = teamASnaps.get(teamASnaps.size() - 1);
        // Total games in rolling window capped at 10
        assertThat(latest.getRollingWins() + latest.getRollingLosses()).isEqualTo(10);
        // All 6 losses are within the window, 4 wins from before
        assertThat(latest.getRollingLosses()).isEqualTo(6);
        assertThat(latest.getRollingWins()).isEqualTo(4);
    }

    @Test
    void rpiFieldsPopulated_withEnoughGames() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        enroll(teamC, acc);
        enroll(teamD, acc);
        // Linear chain A→B→C→D gives enough depth for RPI
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamC, 75, 65, LocalDate.of(2025, 1, 11));
        mkFinalGame(teamC, teamD, 70, 60, LocalDate.of(2025, 1, 12));

        service.calculateAndStoreForSeason(2025);

        // teamA should have a computable RPI after all three dates
        var teamASnaps = snapshotRepo.findByTeamAndSeason(teamA.getId(), season.getId());
        // After date Jan 12, the chain has enough teams for A's OWP and OOWP
        var latest = teamASnaps.get(teamASnaps.size() - 1);
        assertThat(latest.getRpiWp()).isNotNull();
        assertThat(latest.getRpiOwp()).isNotNull();
        assertThat(latest.getRpi()).isNotNull();
        // RPI formula: 0.25*wp + 0.50*owp + 0.25*oowp
        double expectedRpi = 0.25 * latest.getRpiWp()
                + 0.50 * latest.getRpiOwp()
                + 0.25 * latest.getRpiOowp();
        assertThat(latest.getRpi()).isCloseTo(expectedRpi, within(0.0001));
    }

    @Test
    void populationStats_createdForLeague() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 15));

        service.calculateAndStoreForSeason(2025);

        var popStats = popStatRepo.findAll();
        // League-wide stats (conference IS NULL) for each stat name
        var leaguePop = popStats.stream().filter(p -> p.getConference() == null).toList();
        assertThat(leaguePop).isNotEmpty();
        // win_pct should be present after at least 1 game
        assertThat(leaguePop.stream().anyMatch(p -> "win_pct".equals(p.getStatName()))).isTrue();
    }

    @Test
    void leagueZscores_appliedToSnapshots() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // Need at least 2 teams with different values for non-zero stddev
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 90, 75, LocalDate.of(2025, 1, 15));

        service.calculateAndStoreForSeason(2025);

        var snaps = snapshotRepo.findAll();
        // At least 2 snapshots per date exist; on the second date both teams have a win_pct,
        // so population stddev > 0 and z-scores are set
        var withZscore = snaps.stream()
                .filter(s -> s.getZscoreMeanPtsFor() != null)
                .toList();
        assertThat(withZscore).isNotEmpty();
    }

    @Test
    void confZscores_appliedSeparately() {
        // SEC: teamA, teamB; ACC: teamC, teamD
        enroll(teamA, sec);
        enroll(teamB, sec);
        enroll(teamC, acc);
        enroll(teamD, acc);
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamC, teamD, 90, 75, LocalDate.of(2025, 1, 10));

        service.calculateAndStoreForSeason(2025);

        // Conference pop stats for both conferences should exist
        var confPop = popStatRepo.findAll().stream()
                .filter(p -> p.getConference() != null)
                .toList();
        assertThat(confPop).isNotEmpty();

        // Snapshots for teams with a second team in their conf should have conf z-scores
        var snapsWithConf = snapshotRepo.findAll().stream()
                .filter(s -> s.getConfZscoreWinPct() != null)
                .toList();
        assertThat(snapsWithConf).isNotEmpty();
    }

    @Test
    void idempotent_secondCallProducesSameCount() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, LocalDate.of(2025, 1, 15));

        service.calculateAndStoreForSeason(2025);
        long firstCount = snapshotRepo.count();

        service.calculateAndStoreForSeason(2025);
        long secondCount = snapshotRepo.count();

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void gamesWithNullScores_skipped() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        Game g = new Game();
        g.setHomeTeam(teamA);
        g.setAwayTeam(teamB);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        gameRepo.save(g);

        service.calculateAndStoreForSeason(2025);

        assertThat(snapshotRepo.findAll()).isEmpty();
    }
}
