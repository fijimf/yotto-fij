package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatsCalculationServiceTest extends BaseIntegrationTest {

    @Autowired StatsCalculationService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Conference sec, acc;
    Team teamA, teamB, teamC;

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

        acc = new Conference();
        acc.setName("ACC");
        acc.setEspnId("acc1");
        conferenceRepo.save(acc);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
        teamC = mkTeam("Clemson", "TC");
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

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

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore,
                             boolean neutral, LocalDateTime date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(neutral);
        g.setSeason(season);
        g.setGameDate(date);
        return gameRepo.save(g);
    }

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore, boolean neutral) {
        return mkFinalGame(home, away, homeScore, awayScore, neutral,
                LocalDateTime.of(2025, 1, 15, 20, 0));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void seasonNotFound_skipsGracefully() {
        service.calculateAndUpdateForSeason(9999);
        assertThat(statsRepo.findAll()).isEmpty();
    }

    @Test
    void basicWinLossRecord() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        var statsB = statsRepo.findByTeamIdAndSeasonId(teamB.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcWins()).isEqualTo(1);
        assertThat(statsA.getCalcLosses()).isEqualTo(0);
        assertThat(statsB.getCalcWins()).isEqualTo(0);
        assertThat(statsB.getCalcLosses()).isEqualTo(1);
    }

    @Test
    void pointsAccumulatedCorrectly() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcPointsFor()).isEqualTo(80);
        assertThat(statsA.getCalcPointsAgainst()).isEqualTo(70);
    }

    @Test
    void conferenceGameDetection_sameConference() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcConferenceWins()).isEqualTo(1);
        assertThat(statsA.getCalcConferenceLosses()).isEqualTo(0);
        var statsB = statsRepo.findByTeamIdAndSeasonId(teamB.getId(), season.getId()).orElseThrow();
        assertThat(statsB.getCalcConferenceWins()).isEqualTo(0);
        assertThat(statsB.getCalcConferenceLosses()).isEqualTo(1);
    }

    @Test
    void nonConferenceGame_notCountedInConfRecord() {
        enroll(teamA, sec);
        enroll(teamB, acc);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcConferenceWins()).isEqualTo(0);
        assertThat(statsA.getCalcConferenceLosses()).isEqualTo(0);
    }

    @Test
    void homeRoadSplits_nonNeutral() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        var statsB = statsRepo.findByTeamIdAndSeasonId(teamB.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcHomeWins()).isEqualTo(1);
        assertThat(statsA.getCalcHomeLosses()).isEqualTo(0);
        assertThat(statsB.getCalcRoadWins()).isEqualTo(0);
        assertThat(statsB.getCalcRoadLosses()).isEqualTo(1);
    }

    @Test
    void neutralSite_notCountedInHomeOrRoad() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, true);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        var statsB = statsRepo.findByTeamIdAndSeasonId(teamB.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcHomeWins()).isEqualTo(0);
        assertThat(statsA.getCalcHomeLosses()).isEqualTo(0);
        assertThat(statsB.getCalcRoadWins()).isEqualTo(0);
        assertThat(statsB.getCalcRoadLosses()).isEqualTo(0);
    }

    @Test
    void stddev_nullWithOnlyOneGame() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcStddevPtsFor()).isNull();
        assertThat(statsA.getCalcStddevPtsAgainst()).isNull();
        assertThat(statsA.getCalcStddevMargin()).isNull();
    }

    @Test
    void stddev_computedWithTwoGames() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // teamA scores 80 then 90 → variance = ((80-85)² + (90-85)²) / (2-1) = 50 → stddev = √50
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDateTime.of(2025, 1, 10, 20, 0));
        mkFinalGame(teamA, teamB, 90, 75, false, LocalDateTime.of(2025, 1, 17, 20, 0));

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcStddevPtsFor()).isNotNull();
        assertThat(statsA.getCalcStddevPtsFor()).isCloseTo(Math.sqrt(50), within(0.001));
    }

    @Test
    void winStreak_positiveForConsecutiveWins() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // teamA wins all 3 games — regardless of DB return order, streak = 3
        for (int i = 0; i < 3; i++) {
            mkFinalGame(teamA, teamB, 80, 70, false, LocalDateTime.of(2025, 1, 10 + i, 20, 0));
        }

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcStreak()).isEqualTo(3);
    }

    @Test
    void lossStreak_negativeForConsecutiveLosses() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        // teamA (always away) loses all 3
        for (int i = 0; i < 3; i++) {
            mkFinalGame(teamB, teamA, 80, 70, false, LocalDateTime.of(2025, 1, 10 + i, 20, 0));
        }

        service.calculateAndUpdateForSeason(2025);

        var statsA = statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId()).orElseThrow();
        assertThat(statsA.getCalcStreak()).isEqualTo(-3);
    }

    @Test
    void skipsGamesWithNullScores() {
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

        service.calculateAndUpdateForSeason(2025);

        assertThat(statsRepo.findBySeasonId(season.getId())).isEmpty();
    }

    @Test
    void idempotent_secondCallOverwritesFirstCall() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);
        service.calculateAndUpdateForSeason(2025);

        assertThat(statsRepo.findBySeasonId(season.getId())).hasSize(2);
    }

    @Test
    void teamWithNoConferenceMembership_isSkipped() {
        enroll(teamA, sec);
        // teamB has no membership
        mkFinalGame(teamA, teamB, 80, 70, false);

        service.calculateAndUpdateForSeason(2025);

        assertThat(statsRepo.findBySeasonId(season.getId())).hasSize(1);
        assertThat(statsRepo.findByTeamIdAndSeasonId(teamA.getId(), season.getId())).isPresent();
        assertThat(statsRepo.findByTeamIdAndSeasonId(teamB.getId(), season.getId())).isEmpty();
    }
}
