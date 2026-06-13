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
 * <p>Adding a stat is one entry in {@link #REGISTRY} — no schema change.
 */
public class BoxScoreStatCalculator implements DailyStatCalculator {

    /** FTA coefficient in the possession estimate. */
    private static final double FTA_POSS_WEIGHT = 0.475;

    private record StatDef(String name, boolean higherIsBetter, Function<TeamAcc, Double> extractor) {}

    private static final List<StatDef> REGISTRY = List.of(
            new StatDef("pace",           true,  TeamAcc::pace),
            new StatDef("off_efficiency", true,  a -> a.per100(a.pts, a.poss())),
            new StatDef("def_efficiency", false, a -> a.per100(a.oppPts, a.oppPoss())),
            new StatDef("efg_pct",        true,  a -> a.ratio(a.fgm + 0.5 * a.fg3m, a.fga)),
            new StatDef("opp_efg_pct",    false, a -> a.ratio(a.oppFgm + 0.5 * a.oppFg3m, a.oppFga)),
            new StatDef("tov_rate",       false, a -> a.ratio(a.to, a.poss())),
            new StatDef("opp_tov_rate",   true,  a -> a.ratio(a.oppTo, a.oppPoss())),
            new StatDef("orb_pct",        true,  a -> a.ratio(a.orb, a.orb + a.oppDrb)),
            new StatDef("drb_pct",        true,  a -> a.ratio(a.drb, a.drb + a.oppOrb)),
            new StatDef("ft_rate",        true,  a -> a.ratio(a.ftm, a.fga)),
            new StatDef("opp_ft_rate",    false, a -> a.ratio(a.oppFtm, a.oppFga)),
            new StatDef("fg_pct",         true,  a -> a.ratio(a.fgm, a.fga)),
            new StatDef("fg3_pct",        true,  a -> a.ratio(a.fg3m, a.fg3a)),
            new StatDef("ft_pct",         true,  a -> a.ratio(a.ftm, a.fta)),
            new StatDef("fg3_rate",       true,  a -> a.ratio(a.fg3a, a.fga))
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

    /** A box score is usable when every field the registry depends on is present. */
    private static boolean isUsable(TeamGameStats s) {
        return s != null
                && s.getFgMade() != null && s.getFgAttempted() != null
                && s.getFg3Made() != null && s.getFg3Attempted() != null
                && s.getFtMade() != null && s.getFtAttempted() != null
                && s.getOffensiveReb() != null && s.getDefensiveReb() != null
                && s.getTurnovers() != null;
    }

    /** Cumulative own + opponent box-score sums for one team. */
    private static class TeamAcc {
        int games;
        double pts, oppPts;
        double fgm, fga, fg3m, fg3a, ftm, fta, orb, drb, to;
        double oppFgm, oppFga, oppFg3m, oppFg3a, oppFtm, oppFta, oppOrb, oppDrb, oppTo;

        void addGame(int ownScore, int oppScore, TeamGameStats own, TeamGameStats opp) {
            games++;
            pts += ownScore;
            oppPts += oppScore;
            fgm  += own.getFgMade();       fga  += own.getFgAttempted();
            fg3m += own.getFg3Made();      fg3a += own.getFg3Attempted();
            ftm  += own.getFtMade();       fta  += own.getFtAttempted();
            orb  += own.getOffensiveReb(); drb  += own.getDefensiveReb();
            to   += own.getTurnovers();
            oppFgm  += opp.getFgMade();       oppFga  += opp.getFgAttempted();
            oppFg3m += opp.getFg3Made();      oppFg3a += opp.getFg3Attempted();
            oppFtm  += opp.getFtMade();       oppFta  += opp.getFtAttempted();
            oppOrb  += opp.getOffensiveReb(); oppDrb  += opp.getDefensiveReb();
            oppTo   += opp.getTurnovers();
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
