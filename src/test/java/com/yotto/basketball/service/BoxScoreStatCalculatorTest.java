package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.service.DailyStatCalculator.TeamStatValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit test — no Spring, no DB. Values are hand-computed from the standard
 * formulas (possessions = FGA − ORB + TO + 0.475×FTA).
 */
class BoxScoreStatCalculatorTest {

    private static final double TOL = 1e-9;
    private static final LocalDate D1 = LocalDate.of(2025, 1, 10);
    private static final LocalDate D2 = LocalDate.of(2025, 1, 20);

    private BoxScoreStatCalculator calculator;
    private Team teamA, teamB;

    @BeforeEach
    void setUp() {
        calculator = new BoxScoreStatCalculator();
        calculator.begin(new SeasonGameData(null, List.of(), Map.of(), Map.of()));
        teamA = mkTeam(1L);
        teamB = mkTeam(2L);
    }

    private Team mkTeam(long id) {
        Team t = new Team();
        t.setId(id);
        return t;
    }

    private Game mkGame(Team home, Team away, int homeScore, int awayScore) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        return g;
    }

    /** A's reference box: 30/60 FG, 5/20 3PT, 15/20 FT, 10 ORB, 25 DRB, 12 TO, 18 AST, 7 STL, 4 BLK, 16 PF. */
    private TeamGameStats boxA() {
        return mkBox(30, 60, 5, 20, 15, 20, 10, 25, 12, 18, 7, 4, 16);
    }

    /** B's reference box: 25/55 FG, 8/25 3PT, 12/16 FT, 8 ORB, 22 DRB, 15 TO, 14 AST, 9 STL, 3 BLK, 19 PF. */
    private TeamGameStats boxB() {
        return mkBox(25, 55, 8, 25, 12, 16, 8, 22, 15, 14, 9, 3, 19);
    }

    private TeamGameStats mkBox(int fgm, int fga, int fg3m, int fg3a, int ftm, int fta,
                                int orb, int drb, int to, int ast, int stl, int blk, int pf) {
        TeamGameStats s = new TeamGameStats();
        s.setFgMade(fgm);
        s.setFgAttempted(fga);
        s.setFg3Made(fg3m);
        s.setFg3Attempted(fg3a);
        s.setFtMade(ftm);
        s.setFtAttempted(fta);
        s.setOffensiveReb(orb);
        s.setDefensiveReb(drb);
        s.setTurnovers(to);
        s.setAssists(ast);
        s.setSteals(stl);
        s.setBlocks(blk);
        s.setFouls(pf);
        return s;
    }

    private Map<String, Double> statsFor(long teamId, LocalDate date) {
        Map<String, Double> result = new HashMap<>();
        for (TeamStatValue v : calculator.snapshot(date)) {
            if (v.teamId() == teamId) {
                result.put(v.statName(), v.value());
            }
        }
        return result;
    }

    @Test
    void registryDefinesAllStats() {
        assertThat(calculator.definitions()).hasSize(22);
        assertThat(calculator.definitions())
                .extracting(DailyStatCalculator.StatMeta::name)
                .containsExactlyInAnyOrder("pace", "off_efficiency", "def_efficiency",
                        "efg_pct", "opp_efg_pct", "tov_rate", "opp_tov_rate",
                        "orb_pct", "drb_pct", "ft_rate", "opp_ft_rate",
                        "ts_pct", "fg_pct", "fg3_pct", "ft_pct", "fg3_rate",
                        "trb_pct", "ast_to_ratio", "assisted_fg_pct",
                        "stl_rate", "blk_pct", "pf_per_game");
    }

    @Test
    void pf_per_game_isLowerIsBetter() {
        assertThat(calculator.definitions())
                .filteredOn(m -> m.name().equals("pf_per_game"))
                .singleElement()
                .matches(m -> !m.higherIsBetter());
    }

    @Test
    void singleGame_handComputedValues() {
        // A possessions: 60 − 10 + 12 + 0.475×20 = 71.5
        // B possessions: 55 − 8 + 15 + 0.475×16 = 69.6
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), boxB());

        Map<String, Double> a = statsFor(1L, D1);
        assertThat(a.get("pace")).isCloseTo((71.5 + 69.6) / 2.0, within(TOL));
        assertThat(a.get("off_efficiency")).isCloseTo(100.0 * 80 / 71.5, within(TOL));
        assertThat(a.get("def_efficiency")).isCloseTo(100.0 * 70 / 69.6, within(TOL));
        assertThat(a.get("efg_pct")).isCloseTo((30 + 0.5 * 5) / 60.0, within(TOL));
        assertThat(a.get("opp_efg_pct")).isCloseTo((25 + 0.5 * 8) / 55.0, within(TOL));
        assertThat(a.get("tov_rate")).isCloseTo(12 / 71.5, within(TOL));
        assertThat(a.get("opp_tov_rate")).isCloseTo(15 / 69.6, within(TOL));
        assertThat(a.get("orb_pct")).isCloseTo(10.0 / (10 + 22), within(TOL));
        assertThat(a.get("drb_pct")).isCloseTo(25.0 / (25 + 8), within(TOL));
        assertThat(a.get("ft_rate")).isCloseTo(15 / 60.0, within(TOL));
        assertThat(a.get("opp_ft_rate")).isCloseTo(12 / 55.0, within(TOL));
        assertThat(a.get("fg_pct")).isCloseTo(0.5, within(TOL));
        assertThat(a.get("fg3_pct")).isCloseTo(0.25, within(TOL));
        assertThat(a.get("ft_pct")).isCloseTo(0.75, within(TOL));
        assertThat(a.get("fg3_rate")).isCloseTo(20 / 60.0, within(TOL));
        // New stats (A & B)
        assertThat(a.get("ts_pct")).isCloseTo(80.0 / (2 * (60 + 0.475 * 20)), within(TOL));
        assertThat(a.get("trb_pct")).isCloseTo((10.0 + 25) / (10 + 25 + 8 + 22), within(TOL));
        assertThat(a.get("ast_to_ratio")).isCloseTo(18.0 / 12, within(TOL));
        assertThat(a.get("assisted_fg_pct")).isCloseTo(18.0 / 30, within(TOL));
        // steal rate is over opponent possessions; block % over opponent 2PT attempts
        assertThat(a.get("stl_rate")).isCloseTo(7.0 / 69.6, within(TOL));
        assertThat(a.get("blk_pct")).isCloseTo(4.0 / (55 - 25), within(TOL));
        assertThat(a.get("pf_per_game")).isCloseTo(16.0, within(TOL));
    }

    @Test
    void missingAssists_skipsGameEntirely() {
        TeamGameStats incomplete = boxB();
        incomplete.setAssists(null);
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), incomplete);

        assertThat(calculator.snapshot(D1)).isEmpty();
    }

    @Test
    void rebondingPercentagesAreComplementaryAcrossOpponents() {
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), boxB());

        Map<String, Double> a = statsFor(1L, D1);
        Map<String, Double> b = statsFor(2L, D1);
        // A's ORB% and B's DRB% partition the same set of A-miss rebounds
        assertThat(a.get("orb_pct") + b.get("drb_pct")).isCloseTo(1.0, within(TOL));
        assertThat(b.get("orb_pct") + a.get("drb_pct")).isCloseTo(1.0, within(TOL));
    }

    @Test
    void cumulativeAcrossTwoGames() {
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), boxB());
        // Same boxes again on D2 (A away this time), different score
        calculator.onGame(mkGame(teamB, teamA, 90, 85), boxB(), boxA());

        Map<String, Double> a = statsFor(1L, D2);
        // A: 165 pts over possessions 2×71.5 = 143
        assertThat(a.get("off_efficiency")).isCloseTo(100.0 * (80 + 85) / 143.0, within(TOL));
        // Shooting ratios of doubled identical sums are unchanged
        assertThat(a.get("fg_pct")).isCloseTo(0.5, within(TOL));
        assertThat(a.get("efg_pct")).isCloseTo((30 + 0.5 * 5) / 60.0, within(TOL));

        // gamesPlayed reflects both games
        assertThat(calculator.snapshot(D2).stream()
                .filter(v -> v.teamId() == 1L)
                .allMatch(v -> v.gamesPlayed() == 2)).isTrue();
    }

    @Test
    void missingBoxScore_skipsGameEntirely() {
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), null);

        assertThat(calculator.snapshot(D1)).isEmpty();
    }

    @Test
    void incompleteBoxScore_skipsGameEntirely() {
        TeamGameStats incomplete = boxB();
        incomplete.setTurnovers(null);
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), incomplete);

        assertThat(calculator.snapshot(D1)).isEmpty();
    }

    @Test
    void gameWithoutBoxScores_doesNotAffectAccumulatedStats() {
        calculator.onGame(mkGame(teamA, teamB, 80, 70), boxA(), boxB());
        calculator.onGame(mkGame(teamB, teamA, 90, 85), null, null); // not yet scraped

        Map<String, Double> a = statsFor(1L, D2);
        // Still single-game values
        assertThat(a.get("off_efficiency")).isCloseTo(100.0 * 80 / 71.5, within(TOL));
        assertThat(calculator.snapshot(D2).stream()
                .filter(v -> v.teamId() == 1L)
                .allMatch(v -> v.gamesPlayed() == 1)).isTrue();
    }
}
