package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.TeamGameStats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Derives possession-based and shooting stats from per-game box scores
 * ({@link TeamGameStats}). All values are cumulative through the snapshot date.
 *
 * <p>Possessions are estimated per game as {@code FGA − ORB + TO + 0.475×FTA}
 * (the standard estimator); pace averages the two teams' estimates. Games missing
 * a box score on either side are skipped entirely — emitting a stat from half a
 * game would bias the opponent-facing rates.
 *
 * <p>The gate ({@link #isUsable}) requires the full standard ESPN stats block —
 * shooting, rebounds, turnovers, assists, steals, blocks, fouls — which ESPN
 * always returns together. The scoring-distribution fields (points in paint,
 * fast-break points, turnover points) are sparse and handled null-aware: their
 * share stats only accumulate over the games where ESPN supplied them.
 *
 * <p>Adding a stat is one entry in {@link #REGISTRY} — no schema change.
 */
public class BoxScoreStatCalculator implements DailyStatCalculator {

    /** FTA coefficient in the possession estimate. */
    private static final double FTA_POSS_WEIGHT = 0.475;
    /** FTA coefficient in the true-shooting attempts denominator. */
    private static final double TS_FTA_WEIGHT = 0.475;

    private record StatDef(String name, boolean higherIsBetter, Function<TeamAcc, Double> extractor) {}

    private static final List<StatDef> REGISTRY = List.of(
            new StatDef("pace",           true,  TeamAcc::pace),
            new StatDef("off_efficiency", true,  a -> a.per100(a.pts, a.poss())),
            new StatDef("def_efficiency", false, a -> a.per100(a.oppPts, a.oppPoss())),
            // Four factors (own + opponent)
            new StatDef("efg_pct",        true,  a -> a.ratio(a.fgm + 0.5 * a.fg3m, a.fga)),
            new StatDef("opp_efg_pct",    false, a -> a.ratio(a.oppFgm + 0.5 * a.oppFg3m, a.oppFga)),
            new StatDef("tov_rate",       false, a -> a.ratio(a.to, a.poss())),
            new StatDef("opp_tov_rate",   true,  a -> a.ratio(a.oppTo, a.oppPoss())),
            new StatDef("orb_pct",        true,  a -> a.ratio(a.orb, a.orb + a.oppDrb)),
            new StatDef("drb_pct",        true,  a -> a.ratio(a.drb, a.drb + a.oppOrb)),
            new StatDef("ft_rate",        true,  a -> a.ratio(a.ftm, a.fga)),
            new StatDef("opp_ft_rate",    false, a -> a.ratio(a.oppFtm, a.oppFga)),
            // Shooting splits
            new StatDef("ts_pct",         true,  a -> a.ratio(a.pts, 2 * (a.fga + TS_FTA_WEIGHT * a.fta))),
            new StatDef("fg_pct",         true,  a -> a.ratio(a.fgm, a.fga)),
            new StatDef("fg3_pct",        true,  a -> a.ratio(a.fg3m, a.fg3a)),
            new StatDef("ft_pct",         true,  a -> a.ratio(a.ftm, a.fta)),
            new StatDef("fg3_rate",       true,  a -> a.ratio(a.fg3a, a.fga)),
            // Rebounding (combined)
            new StatDef("trb_pct",        true,  a -> a.ratio(a.orb + a.drb,
                                                              a.orb + a.drb + a.oppOrb + a.oppDrb)),
            // Ball movement
            new StatDef("ast_to_ratio",     true, a -> a.ratio(a.ast, a.to)),
            new StatDef("assisted_fg_pct",  true, a -> a.ratio(a.ast, a.fgm)),
            // Defensive playmaking (own steals/blocks are defensive events)
            new StatDef("stl_rate",       true,  a -> a.ratio(a.stl, a.oppPoss())),
            new StatDef("blk_pct",        true,  a -> a.ratio(a.blk, a.oppFga - a.oppFg3a)),
            new StatDef("pf_per_game",    false, a -> a.games > 0 ? a.pf / a.games : null),
            // Scoring distribution (sparse — only over games where ESPN supplied the field)
            new StatDef("paint_pts_share",      true, a -> a.ratio(a.paintPts, a.paintBasePts)),
            new StatDef("fast_break_pts_share", true, a -> a.ratio(a.fastBreakPts, a.fastBreakBasePts)),
            new StatDef("turnover_pts_share",   true, a -> a.ratio(a.turnoverPts, a.turnoverBasePts))
    );

    /** Stat names owned by this calculator (used for population-row deletes). */
    public static List<StatMeta> statMetas() {
        return REGISTRY.stream().map(d -> new StatMeta(d.name(), d.higherIsBetter())).toList();
    }

    private final Map<Long, TeamAcc> accByTeamId = new HashMap<>();

    @Override
    public List<StatMeta> definitions() {
        return statMetas();
    }

    @Override
    public void begin(SeasonGameData data) {
        accByTeamId.clear();
    }

    @Override
    public void onGame(Game game, TeamGameStats homeStats, TeamGameStats awayStats) {
        if (!isUsable(homeStats) || !isUsable(awayStats)) {
            return;
        }
        long homeId = game.getHomeTeam().getId();
        long awayId = game.getAwayTeam().getId();
        accByTeamId.computeIfAbsent(homeId, k -> new TeamAcc())
                .addGame(game.getHomeScore(), game.getAwayScore(), homeStats, awayStats);
        accByTeamId.computeIfAbsent(awayId, k -> new TeamAcc())
                .addGame(game.getAwayScore(), game.getHomeScore(), awayStats, homeStats);
    }

    @Override
    public List<TeamStatValue> snapshot(LocalDate date) {
        List<TeamStatValue> values = new ArrayList<>();
        for (Map.Entry<Long, TeamAcc> entry : accByTeamId.entrySet()) {
            TeamAcc acc = entry.getValue();
            for (StatDef def : REGISTRY) {
                Double value = def.extractor().apply(acc);
                if (value != null) {
                    values.add(new TeamStatValue(entry.getKey(), def.name(), value, acc.games));
                }
            }
        }
        return values;
    }

    /**
     * A box score is usable when the full standard ESPN stats block is present.
     * These fields ship together in one stats array, so requiring them all does
     * not selectively drop games. The sparse scoring-distribution fields are NOT
     * gated here — they are accumulated null-aware in {@link TeamAcc#addGame}.
     */
    private static boolean isUsable(TeamGameStats s) {
        return s != null
                && s.getFgMade() != null && s.getFgAttempted() != null
                && s.getFg3Made() != null && s.getFg3Attempted() != null
                && s.getFtMade() != null && s.getFtAttempted() != null
                && s.getOffensiveReb() != null && s.getDefensiveReb() != null
                && s.getTurnovers() != null && s.getAssists() != null
                && s.getSteals() != null && s.getBlocks() != null && s.getFouls() != null;
    }

    /** Cumulative own + opponent box-score sums for one team. */
    private static class TeamAcc {
        int games;
        double pts, oppPts;
        double fgm, fga, fg3m, fg3a, ftm, fta, orb, drb, to, ast, stl, blk, pf;
        double oppFgm, oppFga, oppFg3m, oppFg3a, oppFtm, oppFta, oppOrb, oppDrb, oppTo;
        // Sparse scoring-distribution fields: each paired with the points scored in
        // the games where ESPN supplied it, so the share is over a consistent base.
        double paintPts, paintBasePts;
        double fastBreakPts, fastBreakBasePts;
        double turnoverPts, turnoverBasePts;

        void addGame(int ownScore, int oppScore, TeamGameStats own, TeamGameStats opp) {
            games++;
            pts += ownScore;
            oppPts += oppScore;
            fgm  += own.getFgMade();       fga  += own.getFgAttempted();
            fg3m += own.getFg3Made();      fg3a += own.getFg3Attempted();
            ftm  += own.getFtMade();       fta  += own.getFtAttempted();
            orb  += own.getOffensiveReb(); drb  += own.getDefensiveReb();
            to   += own.getTurnovers();
            ast  += own.getAssists();      stl  += own.getSteals();
            blk  += own.getBlocks();       pf   += own.getFouls();
            oppFgm  += opp.getFgMade();       oppFga  += opp.getFgAttempted();
            oppFg3m += opp.getFg3Made();      oppFg3a += opp.getFg3Attempted();
            oppFtm  += opp.getFtMade();       oppFta  += opp.getFtAttempted();
            oppOrb  += opp.getOffensiveReb(); oppDrb  += opp.getDefensiveReb();
            oppTo   += opp.getTurnovers();

            if (own.getPointsInPaint() != null) {
                paintPts += own.getPointsInPaint();
                paintBasePts += ownScore;
            }
            if (own.getFastBreakPts() != null) {
                fastBreakPts += own.getFastBreakPts();
                fastBreakBasePts += ownScore;
            }
            if (own.getTurnoverPts() != null) {
                turnoverPts += own.getTurnoverPts();
                turnoverBasePts += ownScore;
            }
        }

        double poss()    { return fga - orb + to + FTA_POSS_WEIGHT * fta; }
        double oppPoss() { return oppFga - oppOrb + oppTo + FTA_POSS_WEIGHT * oppFta; }

        Double pace() {
            return games > 0 ? (poss() + oppPoss()) / (2 * games) : null;
        }

        Double per100(double points, double possessions) {
            return possessions > 0 ? 100.0 * points / possessions : null;
        }

        Double ratio(double numerator, double denominator) {
            return denominator > 0 ? numerator / denominator : null;
        }
    }
}
