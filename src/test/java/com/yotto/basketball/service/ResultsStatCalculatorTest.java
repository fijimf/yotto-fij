package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.service.DailyStatCalculator.TeamStatValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultsStatCalculatorTest {

    private ResultsStatCalculator calc;
    private Team a, b, c;
    private long nextGameId = 1;

    @BeforeEach
    void setUp() {
        calc = new ResultsStatCalculator();
        calc.begin(null);
        a = mkTeam(1L);
        b = mkTeam(2L);
        c = mkTeam(3L);
    }

    private Team mkTeam(long id) {
        Team t = new Team();
        t.setId(id);
        return t;
    }

    private void game(Team home, Team away, int homeScore, int awayScore, boolean neutral) {
        Game g = new Game();
        g.setId(nextGameId++);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setNeutralSite(neutral);
        g.setSeason(new Season());
        g.setGameDate(LocalDateTime.of(2025, 1, 10, 19, 0));
        g.setStatus(Game.GameStatus.FINAL);
        calc.onGame(g, null, null);
    }

    private Map<String, Double> valuesFor(long teamId) {
        Map<String, Double> byName = new HashMap<>();
        for (TeamStatValue v : calc.snapshot(LocalDate.of(2025, 1, 10))) {
            if (v.teamId() == teamId) byName.put(v.statName(), v.value());
        }
        return byName;
    }

    @Test
    void basicResultsStats_handComputed() {
        // A beats B 80-70 (A home), B beats A 75-65 (B home), A beats C 90-60 (A home)
        game(a, b, 80, 70, false);
        game(b, a, 75, 65, false);
        game(a, c, 90, 60, false);

        Map<String, Double> va = valuesFor(1L);
        assertEquals(2.0 / 3.0, va.get("wp"), 1e-9);
        assertEquals((80 + 65 + 90) / 3.0, va.get("ppg"), 1e-9);
        assertEquals((70 + 75 + 60) / 3.0, va.get("opp_ppg"), 1e-9);
        assertEquals((80 + 65 + 90 - 70 - 75 - 60) / 3.0, va.get("scoring_margin"), 1e-9);
        // margins: +10, -10, +30 → mean 10, sample stddev sqrt(((0)^2+(-20)^2+(20)^2)/2) = 20
        assertEquals(20.0, va.get("margin_volatility"), 1e-9);

        Map<String, Double> vc = valuesFor(3L);
        assertEquals(0.0, vc.get("wp"), 1e-9);
        // C played 1 game — sample stddev undefined, stat omitted
        assertNull(vc.get("margin_volatility"));
    }

    @Test
    void owpOowp_matchRpiCalculatorExactly() {
        game(a, b, 80, 70, false);
        game(b, c, 75, 65, false);
        game(c, a, 60, 90, true);

        Map<Long, List<RpiCalculator.GameRecord>> expected = new HashMap<>();
        RpiCalculator.addGame(expected, 1L, 2L, false, true);
        RpiCalculator.addGame(expected, 2L, 3L, false, true);
        RpiCalculator.addGame(expected, 3L, 1L, true, false);
        Map<Long, RpiCalculator.RpiComponents> rpi = RpiCalculator.compute(expected);

        for (long teamId : new long[]{1L, 2L, 3L}) {
            Map<String, Double> v = valuesFor(teamId);
            assertEquals(rpi.get(teamId).owp(), v.get("owp"), 1e-12, "owp for team " + teamId);
            assertEquals(rpi.get(teamId).oowp(), v.get("oowp"), 1e-12, "oowp for team " + teamId);
        }
    }

    @Test
    void everyDeclaredStatIsEmittedForTeamsWithEnoughGames() {
        // Three teams in a cycle so OWP/OOWP are defined (a two-team fixture
        // leaves opponents with no games vs third parties → OWP undefined)
        game(a, b, 80, 70, false);
        game(b, c, 75, 65, false);
        game(c, a, 60, 90, false);
        game(a, b, 70, 60, false);

        Map<String, Double> va = valuesFor(1L);
        for (DailyStatCalculator.StatMeta meta : ResultsStatCalculator.statMetas()) {
            assertTrue(va.containsKey(meta.name()), "missing stat: " + meta.name());
        }
    }
}
