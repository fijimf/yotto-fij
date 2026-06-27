package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.service.BoxScoreStatCalculator.GamePoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scatter relies on {@code perGame} reusing the exact registry formulas. These
 * tests pin a few stats to their hand-computed single-game values.
 */
class BoxScoreStatCalculatorPerGameTest {

    private static final double FTA_W = 0.475;

    private Game game() {
        Team home = new Team();
        home.setId(1L);
        Team away = new Team();
        away.setId(2L);
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(85);
        g.setAwayScore(78);
        return g;
    }

    private TeamGameStats box(int fgm, int fga, int fg3m, int fg3a, int ftm, int fta,
                              int orb, int drb, int ast, int stl, int blk, int to, int pf) {
        TeamGameStats s = new TeamGameStats();
        s.setFgMade(fgm);
        s.setFgAttempted(fga);
        s.setFg3Made(fg3m);
        s.setFg3Attempted(fg3a);
        s.setFtMade(ftm);
        s.setFtAttempted(fta);
        s.setOffensiveReb(orb);
        s.setDefensiveReb(drb);
        s.setAssists(ast);
        s.setSteals(stl);
        s.setBlocks(blk);
        s.setTurnovers(to);
        s.setFouls(pf);
        return s;
    }

    @Test
    void perGameMatchesHandComputedFormulas() {
        TeamGameStats homeBox = box(28, 61, 8, 22, 21, 25, 9, 27, 15, 7, 4, 12, 18);
        TeamGameStats awayBox = box(26, 60, 7, 25, 19, 22, 11, 22, 13, 5, 3, 14, 20);
        Map<String, GamePoint> values = BoxScoreStatCalculator.perGame(game(), homeBox, awayBox);

        // eFG% home = (FGM + 0.5*3PM) / FGA
        assertEquals((28 + 0.5 * 8) / 61.0, values.get("efg_pct").homeValue(), 1e-9);

        // Possessions: FGA - ORB + TO + 0.475*FTA
        double homePoss = 61 - 9 + 12 + FTA_W * 25;
        double awayPoss = 60 - 11 + 14 + FTA_W * 22;
        // Pace averages the two possession estimates
        assertEquals((homePoss + awayPoss) / 2.0, values.get("pace").homeValue(), 1e-9);

        // Offensive efficiency home = 100 * pts / poss
        assertEquals(100.0 * 85 / homePoss, values.get("off_efficiency").homeValue(), 1e-9);

        // ORB% home = ORB / (ORB + opp DRB)
        assertEquals(9.0 / (9 + 22), values.get("orb_pct").homeValue(), 1e-9);

        // Away perspective is symmetric: away eFG% from the away box
        assertEquals((26 + 0.5 * 7) / 60.0, values.get("efg_pct").awayValue(), 1e-9);
    }

    @Test
    void unusableBoxScoreYieldsEmptyMap() {
        TeamGameStats full = box(28, 61, 8, 22, 21, 25, 9, 27, 15, 7, 4, 12, 18);
        TeamGameStats missing = box(26, 60, 7, 25, 19, 22, 11, 22, 13, 5, 3, 14, 20);
        missing.setBlocks(null); // drop one required field

        assertTrue(BoxScoreStatCalculator.perGame(game(), full, missing).isEmpty());
    }

    @Test
    void zeroDenominatorLeavesStatNull() {
        // No three-point attempts -> fg3_pct has a zero denominator and must be null.
        TeamGameStats homeBox = box(20, 50, 0, 0, 10, 12, 8, 24, 12, 6, 3, 10, 16);
        TeamGameStats awayBox = box(22, 55, 5, 18, 12, 15, 10, 20, 11, 4, 2, 13, 19);
        Map<String, GamePoint> values = BoxScoreStatCalculator.perGame(game(), homeBox, awayBox);
        assertNull(values.get("fg3_pct").homeValue());
    }
}
