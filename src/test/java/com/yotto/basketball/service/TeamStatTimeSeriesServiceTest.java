package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TeamStatTimeSeriesServiceTest extends BaseIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2025, 1, 10);
    private static final LocalDate D2 = LocalDate.of(2025, 1, 20);

    @Autowired TeamStatTimeSeriesService service;
    @Autowired StatisticsTimeSeriesService legacyTimeSeriesService;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamGameStatsRepository boxRepo;
    @Autowired TeamStatSnapshotRepository statSnapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;

    Season season;
    Conference sec, acc;
    Team a, b, c;
    TeamGameStats mutableBox; // a D2 box-score row corrected between runs

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = mkConference("SEC", "sec1");
        acc = mkConference("ACC", "acc1");

        a = mkTeam("Alabama", "TA");
        b = mkTeam("Auburn", "TB");
        c = mkTeam("Clemson", "TC");

        enroll(a, sec);
        enroll(b, sec);
        enroll(c, acc); // alone in its conference

        // D1: A (home, 80) beats B (70); both boxes present
        Game g1 = mkFinalGame(a, b, 80, 70, D1);
        mkBox(g1, a, "HOME", 30, 60, 5, 20, 15, 20, 10, 25, 12);
        mkBox(g1, b, "AWAY", 25, 55, 8, 25, 12, 16, 8, 22, 15);

        // D2: C (home, 75) beats A (72); both boxes present
        Game g2 = mkFinalGame(c, a, 75, 72, D2);
        mkBox(g2, c, "HOME", 28, 58, 7, 22, 12, 15, 9, 24, 10);
        mutableBox = mkBox(g2, a, "AWAY", 27, 62, 6, 21, 12, 18, 11, 20, 14);
    }

    private Conference mkConference(String name, String espnId) {
        Conference conf = new Conference();
        conf.setName(name);
        conf.setEspnId(espnId);
        return conferenceRepo.save(conf);
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

    private TeamGameStats mkBox(Game game, Team team, String homeAway,
                                int fgm, int fga, int fg3m, int fg3a,
                                int ftm, int fta, int orb, int drb, int to) {
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
        s.setScrapeDate(LocalDateTime.now());
        return boxRepo.save(s);
    }

    // ── Persistence + correctness ─────────────────────────────────────────────

    @Test
    void persistsLongFormatRows_withHandComputedValue() {
        service.calculateAndStoreForSeason(2025);

        // A's eFG% on D1: (30 + 0.5×5) / 60
        List<TeamStatSnapshot> efg = statSnapshotRepo.findByTeamSeasonAndStat(
                a.getId(), season.getId(), "efg_pct");
        assertThat(efg).hasSize(2); // D1 and D2 snapshots
        assertThat(efg.get(0).getSnapshotDate()).isEqualTo(D1);
        assertThat(efg.get(0).getValue()).isCloseTo((30 + 0.5 * 5) / 60.0, within(1e-9));
        assertThat(efg.get(0).getGamesPlayed()).isEqualTo(1);
    }

    @Test
    void ranksRespectStatDirection() {
        service.calculateAndStoreForSeason(2025);

        // off_efficiency: higher is better → rank 1 has the max value
        List<TeamStatSnapshot> off = statSnapshotRepo.findBySeasonStatAndDate(
                season.getId(), "off_efficiency", D2);
        assertThat(off).hasSize(3);
        assertThat(off.get(0).getValue()).isGreaterThanOrEqualTo(off.get(1).getValue());
        assertThat(off.get(1).getValue()).isGreaterThanOrEqualTo(off.get(2).getValue());

        // def_efficiency: lower is better → rank 1 has the min value
        List<TeamStatSnapshot> def = statSnapshotRepo.findBySeasonStatAndDate(
                season.getId(), "def_efficiency", D2);
        assertThat(def.get(0).getValue()).isLessThanOrEqualTo(def.get(1).getValue());
        assertThat(def.get(1).getValue()).isLessThanOrEqualTo(def.get(2).getValue());
    }

    @Test
    void zscores_leaguePresent_singleTeamConferenceNull() {
        service.calculateAndStoreForSeason(2025);

        List<TeamStatSnapshot> rows = statSnapshotRepo.findBySeasonStatAndDate(
                season.getId(), "efg_pct", D2);
        assertThat(rows).hasSize(3);
        for (TeamStatSnapshot row : rows) {
            assertThat(row.getZscore()).isNotNull(); // 3 distinct league values
            if (row.getTeam().getId().equals(c.getId())) {
                // C is alone in the ACC: stddev 0 → no conference z-score
                assertThat(row.getConfZscore()).isNull();
            }
        }
    }

    @Test
    void writesPopulationRows_leagueAndConference() {
        service.calculateAndStoreForSeason(2025);

        List<SeasonPopulationStat> league = popStatRepo.findLeagueWideBySeasonAndDate(season.getId(), D2);
        assertThat(league.stream().map(SeasonPopulationStat::getStatName))
                .contains("efg_pct", "pace", "off_efficiency");

        List<SeasonPopulationStat> secRows = popStatRepo.findBySeasonDateAndConference(
                season.getId(), D2, sec.getId());
        assertThat(secRows.stream().map(SeasonPopulationStat::getStatName)).contains("efg_pct");
        // SEC scope counts only A and B
        assertThat(secRows.stream().filter(p -> p.getStatName().equals("efg_pct")).findFirst()
                .orElseThrow().getTeamCount()).isEqualTo(2);
    }

    @Test
    void coexistsWithLegacyTimeSeries_populationRowsNotClobbered() {
        legacyTimeSeriesService.calculateAndStoreForSeason(2025);
        service.calculateAndStoreForSeason(2025);
        // Re-running the legacy service must not wipe this service's pop rows, and vice versa
        legacyTimeSeriesService.calculateAndStoreForSeason(2025);

        List<SeasonPopulationStat> league = popStatRepo.findLeagueWideBySeasonAndDate(season.getId(), D2);
        Set<String> names = league.stream().map(SeasonPopulationStat::getStatName).collect(Collectors.toSet());
        assertThat(names).contains("win_pct", "mean_margin");      // legacy service's stats
        assertThat(names).contains("efg_pct", "off_efficiency");   // this service's stats
    }

    @Test
    void idempotent_rerunProducesSameRowCount() {
        service.calculateAndStoreForSeason(2025);
        long count = statSnapshotRepo.count();

        service.calculateAndStoreForSeason(2025);

        assertThat(statSnapshotRepo.count()).isEqualTo(count);
    }

    @Test
    void unknownSeason_skipsGracefully() {
        service.calculateAndStoreForSeason(9999);
        assertThat(statSnapshotRepo.count()).isZero();
    }

    // ── Watermark semantics ───────────────────────────────────────────────────

    @Test
    void incrementalEqualsFullRecompute() {
        service.calculateAndStoreForSeason(2025);

        // Correct A's D2 box score, then recompute incrementally from D2
        mutableBox.setFgMade(33);
        mutableBox.setTurnovers(8);
        boxRepo.save(mutableBox);
        service.calculateAndStoreForSeason(2025, D2);
        Map<String, double[]> incremental = capture();

        service.calculateAndStoreForSeason(2025); // fresh full on same data
        Map<String, double[]> full = capture();

        assertThat(incremental.keySet()).isEqualTo(full.keySet());
        for (String key : full.keySet()) {
            double[] inc = incremental.get(key);
            double[] exp = full.get(key);
            assertThat(inc[0]).as("value %s", key).isCloseTo(exp[0], within(1e-9));
            assertThat(inc[1]).as("rank %s", key).isEqualTo(exp[1]);
        }
    }

    @Test
    void incremental_preWatermarkRowsAreNotRewritten() {
        service.calculateAndStoreForSeason(2025);
        Set<Long> d1Ids = statSnapshotRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamStatSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1Ids).isNotEmpty();

        service.calculateAndStoreForSeason(2025, D2);

        Set<Long> d1IdsAfter = statSnapshotRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamStatSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1IdsAfter).isEqualTo(d1Ids);
    }

    /** key team|date|stat -> [value, rank] */
    private Map<String, double[]> capture() {
        Map<String, double[]> result = new HashMap<>();
        for (TeamStatSnapshot s : statSnapshotRepo.findAll()) {
            result.put(s.getTeam().getId() + "|" + s.getSnapshotDate() + "|" + s.getStatName(),
                    new double[]{s.getValue(), s.getRank()});
        }
        return result;
    }
}
