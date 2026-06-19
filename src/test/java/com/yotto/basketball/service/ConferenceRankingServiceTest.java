package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.ConferenceRankingService.ConferenceAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConferenceRankingServiceTest extends BaseIntegrationTest {

    @Autowired ConferenceRankingService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Conference sec, big;
    Team a, b, c, d;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = mkConf("SEC", "sec-1");
        big = mkConf("Big Ten", "big-1");

        a = mkTeam("Alabama", "a");
        b = mkTeam("Auburn", "b");
        c = mkTeam("Purdue", "c");
        d = mkTeam("Iowa", "d");

        enroll(a, sec); enroll(b, sec);
        enroll(c, big); enroll(d, big);

        // Combined records
        mkStats(a, sec, 20, 5); mkStats(b, sec, 10, 15);
        mkStats(c, big, 25, 5); mkStats(d, big, 8, 20);

        // Ratings: SEC avg = (20+10)/2 = 15 ; Big Ten avg = (30+5)/2 = 17.5 → Big Ten rank 1
        rate(a, 20.0, 3); rate(b, 10.0, 12);
        rate(c, 30.0, 1); rate(d, 5.0, 30);
    }

    @Test
    void aggregate_computesAverageRatingAndDenseRank() {
        Map<Long, ConferenceAggregate> agg = service.aggregateBySeason(season);

        ConferenceAggregate secAgg = agg.get(sec.getId());
        ConferenceAggregate bigAgg = agg.get(big.getId());

        assertThat(secAgg.avgMasseyRating()).isEqualTo(15.0);
        assertThat(bigAgg.avgMasseyRating()).isEqualTo(17.5);
        assertThat(bigAgg.conferenceRank()).isEqualTo(1);
        assertThat(secAgg.conferenceRank()).isEqualTo(2);
        assertThat(secAgg.teamCount()).isEqualTo(2);
    }

    @Test
    void aggregate_combinedRecordSumsMembers() {
        ConferenceAggregate secAgg = service.aggregateBySeason(season).get(sec.getId());
        assertThat(secAgg.wins()).isEqualTo(30);   // 20 + 10
        assertThat(secAgg.losses()).isEqualTo(20); // 5 + 15
    }

    @Test
    void aggregate_nonConferenceRecordCountsOnlyNonConfFinalGames() {
        // Non-conf: Alabama (SEC) beats Purdue (Big Ten) → SEC +1W, Big Ten +1L
        mkGame(a, c, 80, 70, false, null);
        // Conference game should NOT count toward non-conf
        Game conf = mkGame(a, b, 90, 60, true, null);
        // A non-final non-conf game should not count either
        Game pending = mkGame(b, d, 0, 0, false, null);
        pending.setStatus(Game.GameStatus.SCHEDULED);
        gameRepo.save(pending);

        Map<Long, ConferenceAggregate> agg = service.aggregateBySeason(season);
        ConferenceAggregate secAgg = agg.get(sec.getId());
        ConferenceAggregate bigAgg = agg.get(big.getId());

        assertThat(secAgg.nonConfWins()).isEqualTo(1);
        assertThat(secAgg.nonConfLosses()).isEqualTo(0);
        assertThat(bigAgg.nonConfWins()).isEqualTo(0);
        assertThat(bigAgg.nonConfLosses()).isEqualTo(1);
    }

    // ── helpers ──

    private Conference mkConf(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setAbbreviation(name);
        c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
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

    private void mkStats(Team team, Conference conf, int wins, int losses) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(conf);
        ss.setCalcWins(wins);
        ss.setCalcLosses(losses);
        statsRepo.save(ss);
    }

    private void rate(Team team, double rating, int rank) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(MasseyRatingService.MODEL_TYPE);
        s.setSnapshotDate(LocalDate.of(2025, 3, 1));
        s.setRating(rating);
        s.setRank(rank);
        s.setGamesPlayed(20);
        s.setCalculatedAt(java.time.LocalDateTime.now());
        ratingRepo.save(s);
    }

    private Game mkGame(Team home, Team away, int hs, int as, boolean confGame, Game.TournamentType type) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(Game.GameStatus.FINAL);
        g.setConferenceGame(confGame);
        g.setTournamentType(type);
        g.setSeason(season);
        g.setGameDate(LocalDate.of(2025, 1, 10).atTime(20, 0));
        return gameRepo.save(g);
    }
}
