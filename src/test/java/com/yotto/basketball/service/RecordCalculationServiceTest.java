package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordCalculationServiceTest extends BaseIntegrationTest {

    @Autowired RecordCalculationService service;
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

    Season season, season2;
    Conference sec, acc;
    Team teamA, teamB;

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
    }

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

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        return gameRepo.save(g);
    }

    @Test
    void calculateRecords_basicWinLoss() {
        mkFinalGame(teamA, teamB, 80, 70);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records.get(teamA.getId()).getWins()).isEqualTo(1);
        assertThat(records.get(teamA.getId()).getLosses()).isEqualTo(0);
        assertThat(records.get(teamB.getId()).getWins()).isEqualTo(0);
        assertThat(records.get(teamB.getId()).getLosses()).isEqualTo(1);
    }

    @Test
    void conferenceGame_detectedByGameFlag() {
        Game g = mkFinalGame(teamA, teamB, 80, 70);
        g.setConferenceGame(true);
        gameRepo.save(g);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records.get(teamA.getId()).getConferenceWins()).isEqualTo(1);
        assertThat(records.get(teamB.getId()).getConferenceLosses()).isEqualTo(1);
    }

    @Test
    void conferenceGame_detectedByMembership() {
        enroll(teamA, sec);
        enroll(teamB, sec);
        mkFinalGame(teamA, teamB, 80, 70);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records.get(teamA.getId()).getConferenceWins()).isEqualTo(1);
        assertThat(records.get(teamB.getId()).getConferenceLosses()).isEqualTo(1);
    }

    @Test
    void nonConferenceGame_notCountedInConfRecord() {
        enroll(teamA, sec);
        enroll(teamB, acc);
        mkFinalGame(teamA, teamB, 80, 70);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records.get(teamA.getId()).getConferenceWins()).isEqualTo(0);
        assertThat(records.get(teamB.getId()).getConferenceLosses()).isEqualTo(0);
    }

    @Test
    void nullScore_gameIsSkipped() {
        Game g = new Game();
        g.setHomeTeam(teamA);
        g.setAwayTeam(teamB);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        gameRepo.save(g);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records).isEmpty();
    }

    @Test
    void teamRecord_getRecordString() {
        mkFinalGame(teamA, teamB, 80, 70);
        mkFinalGame(teamB, teamA, 75, 60);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateRecords(season.getId());

        assertThat(records.get(teamA.getId()).getRecord()).isEqualTo("1-1");
        assertThat(records.get(teamB.getId()).getRecord()).isEqualTo("1-1");
    }

    @Test
    void calculateCurrentSeasonRecords_noSeasons_returnsEmpty() {
        seasonRepo.deleteAll();

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateCurrentSeasonRecords();

        assertThat(records).isEmpty();
    }

    @Test
    void calculateCurrentSeasonRecords_usesLatestSeason() {
        Season older = new Season();
        older.setYear(2024);
        older.setStartDate(LocalDate.of(2023, 11, 1));
        older.setEndDate(LocalDate.of(2024, 4, 30));
        seasonRepo.save(older);

        // game in 2025 season
        mkFinalGame(teamA, teamB, 80, 70);

        Map<Long, RecordCalculationService.TeamRecord> records =
                service.calculateCurrentSeasonRecords();

        // Should pick 2025 (latest) and find the game
        assertThat(records.get(teamA.getId()).getWins()).isEqualTo(1);
    }
}
