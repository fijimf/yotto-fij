package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Phase 2 quality gate: an incremental run (rewrite only dates >= watermark)
 * must produce exactly the same persisted state as a full recompute, and must not
 * touch rows before the watermark.
 *
 * <p>Bradley-Terry ratings are compared within a small tolerance: the incremental
 * path reaches the first rewritten date from a cold Newton-Raphson start, which
 * converges to the same optimum within the 1e-6 gradient criterion.
 */
class IncrementalRecalcEquivalenceTest extends BaseIntegrationTest {

    private static final double TOL = 1e-4;

    private static final LocalDate D1 = LocalDate.of(2025, 1, 10);
    private static final LocalDate D2 = LocalDate.of(2025, 1, 20);
    private static final LocalDate D3 = LocalDate.of(2025, 1, 30);

    @Autowired StatisticsTimeSeriesService timeSeriesService;
    @Autowired MasseyRatingService masseyService;
    @Autowired BradleyTerryRatingService bradleyTerryService;

    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

    Season season;
    Team a, b, c, d;
    Game mutableGame; // the D2 game whose score gets corrected

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        Conference sec = mkConference("SEC", "sec1");
        Conference acc = mkConference("ACC", "acc1");

        a = mkTeam("Alabama", "TA");
        b = mkTeam("Auburn", "TB");
        c = mkTeam("Clemson", "TC");
        d = mkTeam("Duke", "TD");

        enroll(a, sec);
        enroll(b, sec);
        enroll(c, acc);
        enroll(d, acc);

        mkFinalGame(b, a, 70, 80, D1);
        mkFinalGame(c, d, 90, 60, D1);
        mutableGame = mkFinalGame(a, c, 75, 72, D2);
        mkFinalGame(b, d, 88, 77, D2);
        mkFinalGame(d, a, 65, 95, D3);
        mkFinalGame(c, b, 81, 79, D3);
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

    /** Full run, score correction on D2, incremental from D2. */
    private void runFullMutateThenIncremental(Runnable fullRun, Runnable incrementalRun) {
        fullRun.run();
        mutableGame.setHomeScore(60);
        mutableGame.setAwayScore(92); // result flips: away team now wins
        gameRepo.save(mutableGame);
        incrementalRun.run();
    }

    // ── Time-series snapshots ─────────────────────────────────────────────────

    @Test
    void timeSeries_incrementalEqualsFullRecompute() {
        runFullMutateThenIncremental(
                () -> timeSeriesService.calculateAndStoreForSeason(2025),
                () -> timeSeriesService.calculateAndStoreForSeason(2025, D2));
        Map<String, List<Double>> incremental = snapshotValues();
        Map<String, List<Double>> incrementalPop = popValues();

        timeSeriesService.calculateAndStoreForSeason(2025); // fresh full on same data
        Map<String, List<Double>> full = snapshotValues();
        Map<String, List<Double>> fullPop = popValues();

        assertValuesEqual(incremental, full);
        assertValuesEqual(incrementalPop, fullPop);
    }

    @Test
    void timeSeries_preWatermarkRowsAreNotRewritten() {
        timeSeriesService.calculateAndStoreForSeason(2025);
        Set<Long> d1RowIds = snapshotRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamSeasonStatSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1RowIds).isNotEmpty();

        timeSeriesService.calculateAndStoreForSeason(2025, D2);

        Set<Long> d1RowIdsAfter = snapshotRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamSeasonStatSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1RowIdsAfter).isEqualTo(d1RowIds); // same physical rows survived
    }

    @Test
    void timeSeries_watermarkBeforeAllDates_equalsFull() {
        timeSeriesService.calculateAndStoreForSeason(2025, LocalDate.of(2024, 11, 1));
        Map<String, List<Double>> incremental = snapshotValues();

        timeSeriesService.calculateAndStoreForSeason(2025);
        assertValuesEqual(incremental, snapshotValues());
    }

    // ── Massey ────────────────────────────────────────────────────────────────

    @Test
    void massey_incrementalEqualsFullRecompute() {
        runFullMutateThenIncremental(
                () -> masseyService.calculateAndStoreForSeason(2025),
                () -> masseyService.calculateAndStoreForSeason(2025, D2));
        Map<String, double[]> incremental = ratingValues();
        Map<String, Double> incrementalParams = paramValues();

        masseyService.calculateAndStoreForSeason(2025);
        assertRatingsEqual(incremental, ratingValues());
        assertParamsEqual(incrementalParams, paramValues());
    }

    // ── Bradley-Terry ─────────────────────────────────────────────────────────

    @Test
    void bradleyTerry_incrementalEqualsFullRecompute() {
        runFullMutateThenIncremental(
                () -> bradleyTerryService.calculateAndStoreForSeason(2025),
                () -> bradleyTerryService.calculateAndStoreForSeason(2025, D2));
        Map<String, double[]> incremental = ratingValues();
        Map<String, Double> incrementalParams = paramValues();

        bradleyTerryService.calculateAndStoreForSeason(2025);
        assertRatingsEqual(incremental, ratingValues());
        assertParamsEqual(incrementalParams, paramValues());
    }

    @Test
    void powerRatings_preWatermarkRowsAreNotRewritten() {
        masseyService.calculateAndStoreForSeason(2025);
        Set<Long> d1RowIds = ratingRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamPowerRatingSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1RowIds).isNotEmpty();

        masseyService.calculateAndStoreForSeason(2025, D2);

        Set<Long> d1RowIdsAfter = ratingRepo.findAll().stream()
                .filter(s -> s.getSnapshotDate().equals(D1))
                .map(TeamPowerRatingSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(d1RowIdsAfter).isEqualTo(d1RowIds);
    }

    // ── Capture helpers ───────────────────────────────────────────────────────

    private Map<String, List<Double>> snapshotValues() {
        Map<String, List<Double>> result = new HashMap<>();
        for (TeamSeasonStatSnapshot s : snapshotRepo.findAll()) {
            String key = s.getTeam().getId() + "|" + s.getSnapshotDate();
            result.put(key, java.util.Arrays.asList(
                    (double) s.getGamesPlayed(), (double) s.getWins(), (double) s.getLosses(),
                    s.getWinPct(), s.getMeanPtsFor(), s.getStddevPtsFor(),
                    s.getMeanPtsAgainst(), s.getStddevPtsAgainst(), s.getCorrelationPts(),
                    s.getMeanMargin(), s.getStddevMargin(),
                    s.getRollingWins() != null ? s.getRollingWins().doubleValue() : null,
                    s.getRollingMeanPtsFor(),
                    s.getZscoreWinPct(), s.getZscoreMeanMargin(),
                    s.getConfZscoreWinPct(), s.getConfZscoreMeanMargin(),
                    s.getRpi(), s.getRpiWp(), s.getRpiOwp(), s.getRpiOowp()));
        }
        return result;
    }

    private Map<String, List<Double>> popValues() {
        Map<String, List<Double>> result = new HashMap<>();
        for (SeasonPopulationStat p : popStatRepo.findAll()) {
            String conf = p.getConference() != null ? p.getConference().getId().toString() : "L";
            String key = conf + "|" + p.getStatDate() + "|" + p.getStatName();
            result.put(key, java.util.Arrays.asList(
                    p.getPopMean(), p.getPopStddev(), p.getPopMin(), p.getPopMax(),
                    (double) p.getTeamCount()));
        }
        return result;
    }

    /** key -> [rating, rank, gamesPlayed] */
    private Map<String, double[]> ratingValues() {
        Map<String, double[]> result = new HashMap<>();
        for (TeamPowerRatingSnapshot s : ratingRepo.findAll()) {
            String key = s.getTeam().getId() + "|" + s.getModelType() + "|" + s.getSnapshotDate();
            result.put(key, new double[]{s.getRating(), s.getRank(), s.getGamesPlayed()});
        }
        return result;
    }

    private Map<String, Double> paramValues() {
        Map<String, Double> result = new HashMap<>();
        for (PowerModelParamSnapshot p : paramRepo.findAll()) {
            result.put(p.getModelType() + "|" + p.getSnapshotDate() + "|" + p.getParamName(),
                    p.getParamValue());
        }
        return result;
    }

    // ── Comparison helpers ────────────────────────────────────────────────────

    private void assertValuesEqual(Map<String, List<Double>> actual, Map<String, List<Double>> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            List<Double> act = actual.get(key);
            List<Double> exp = expected.get(key);
            assertThat(act).as("row %s", key).hasSameSizeAs(exp);
            for (int i = 0; i < exp.size(); i++) {
                if (exp.get(i) == null) {
                    assertThat(act.get(i)).as("row %s field %d", key, i).isNull();
                } else {
                    assertThat(act.get(i)).as("row %s field %d", key, i)
                            .isCloseTo(exp.get(i), within(TOL));
                }
            }
        }
    }

    private void assertRatingsEqual(Map<String, double[]> actual, Map<String, double[]> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            double[] act = actual.get(key);
            double[] exp = expected.get(key);
            assertThat(act[0]).as("rating %s", key).isCloseTo(exp[0], within(TOL));
            assertThat(act[1]).as("rank %s", key).isEqualTo(exp[1]);
            assertThat(act[2]).as("gamesPlayed %s", key).isEqualTo(exp[2]);
        }
    }

    private void assertParamsEqual(Map<String, Double> actual, Map<String, Double> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            assertThat(actual.get(key)).as("param %s", key)
                    .isCloseTo(expected.get(key), within(TOL));
        }
    }
}
