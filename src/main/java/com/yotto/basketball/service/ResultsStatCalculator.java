package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.TeamGameStats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Results and Scoring stats derived from game results alone (no box scores
 * needed, so these cover all teams and dates even where team_game_stats is
 * sparse). All values are cumulative through the snapshot date.
 *
 * <p>OWP/OOWP reuse {@link RpiCalculator} — the exact logic behind the wide
 * snapshot's RPI columns — so the stat pages and the RPI ranking can never
 * disagree.
 *
 * <p>NAMING: these stat names must stay disjoint from
 * {@link StatisticsTimeSeriesService}'s population-stat names (win_pct,
 * mean_pts_for, mean_pts_against, mean_margin, correlation_pts) —
 * season_population_stats deletes are stat-name-scoped and shared across both
 * writers, so a name collision would make the two services clobber each
 * other's rows.
 */
public class ResultsStatCalculator implements DailyStatCalculator {

    public static final String WP = "wp";
    public static final String PPG = "ppg";
    public static final String OPP_PPG = "opp_ppg";
    public static final String SCORING_MARGIN = "scoring_margin";
    public static final String MARGIN_VOLATILITY = "margin_volatility";
    public static final String OWP = "owp";
    public static final String OOWP = "oowp";

    private static final List<StatMeta> METAS = List.of(
            new StatMeta(WP, true),
            new StatMeta(OWP, true),
            new StatMeta(OOWP, true),
            new StatMeta(PPG, true),
            new StatMeta(OPP_PPG, false),
            new StatMeta(SCORING_MARGIN, true),
            new StatMeta(MARGIN_VOLATILITY, false)
    );

    /** Stat names owned by this calculator (used for population-row deletes). */
    public static List<StatMeta> statMetas() {
        return METAS;
    }

    private final Map<Long, TeamAcc> accByTeamId = new HashMap<>();
    private final Map<Long, List<RpiCalculator.GameRecord>> gamesByTeam = new HashMap<>();

    @Override
    public List<StatMeta> definitions() {
        return METAS;
    }

    @Override
    public void begin(SeasonGameData data) {
        accByTeamId.clear();
        gamesByTeam.clear();
    }

    @Override
    public void onGame(Game game, TeamGameStats homeStats, TeamGameStats awayStats) {
        long homeId = game.getHomeTeam().getId();
        long awayId = game.getAwayTeam().getId();
        int homeScore = game.getHomeScore();
        int awayScore = game.getAwayScore();
        boolean homeWon = homeScore > awayScore;

        accByTeamId.computeIfAbsent(homeId, k -> new TeamAcc()).addGame(homeScore, awayScore, homeWon);
        accByTeamId.computeIfAbsent(awayId, k -> new TeamAcc()).addGame(awayScore, homeScore, !homeWon);
        RpiCalculator.addGame(gamesByTeam, homeId, awayId,
                Boolean.TRUE.equals(game.getNeutralSite()), homeWon);
    }

    @Override
    public List<TeamStatValue> snapshot(LocalDate date) {
        Map<Long, RpiCalculator.RpiComponents> rpiByTeam = RpiCalculator.compute(gamesByTeam);

        List<TeamStatValue> values = new ArrayList<>();
        for (Map.Entry<Long, TeamAcc> entry : accByTeamId.entrySet()) {
            long teamId = entry.getKey();
            TeamAcc acc = entry.getValue();
            if (acc.games == 0) continue;

            values.add(new TeamStatValue(teamId, WP, (double) acc.wins / acc.games, acc.games));
            values.add(new TeamStatValue(teamId, PPG, acc.ptsFor / acc.games, acc.games));
            values.add(new TeamStatValue(teamId, OPP_PPG, acc.ptsAgainst / acc.games, acc.games));
            values.add(new TeamStatValue(teamId, SCORING_MARGIN,
                    (acc.ptsFor - acc.ptsAgainst) / acc.games, acc.games));
            Double volatility = acc.stddevMargin();
            if (volatility != null) {
                values.add(new TeamStatValue(teamId, MARGIN_VOLATILITY, volatility, acc.games));
            }
            RpiCalculator.RpiComponents rpi = rpiByTeam.get(teamId);
            if (rpi != null) {
                values.add(new TeamStatValue(teamId, OWP, rpi.owp(), acc.games));
                values.add(new TeamStatValue(teamId, OOWP, rpi.oowp(), acc.games));
            }
        }
        return values;
    }

    /** Cumulative results for one team; margin stddev via the sums form (sample, n−1). */
    private static class TeamAcc {
        int games;
        int wins;
        double ptsFor, ptsAgainst;
        double sumMargin, sumMarginSq;

        void addGame(int ownScore, int oppScore, boolean won) {
            games++;
            if (won) wins++;
            ptsFor += ownScore;
            ptsAgainst += oppScore;
            double margin = ownScore - oppScore;
            sumMargin += margin;
            sumMarginSq += margin * margin;
        }

        Double stddevMargin() {
            if (games < 2) return null;
            double v = (sumMarginSq - sumMargin * sumMargin / games) / (games - 1);
            return v >= 0 ? Math.sqrt(v) : 0.0;
        }
    }
}
