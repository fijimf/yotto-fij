package com.yotto.basketball.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RPI component computation shared by {@link StatisticsTimeSeriesService} (wide
 * snapshot columns rpi/rpi_wp/rpi_owp/rpi_oowp) and {@link ResultsStatCalculator}
 * (long-format owp/oowp stat pages). One implementation so the two surfaces can
 * never disagree; see docs/RPI.md for the definitions.
 */
public final class RpiCalculator {

    private RpiCalculator() {}

    /** One game from a team's perspective. */
    public record GameRecord(long opponentId, boolean isHome, boolean isNeutral, boolean isWin) {}

    /**
     * The RPI pieces for one team. {@code wp} is the location-adjusted winning
     * percentage (home wins discounted ×0.6, road wins boosted ×1.4); OWP/OOWP
     * exclude games against the team itself.
     */
    public record RpiComponents(double wp, double owp, double oowp, double rpi) {}

    /** RPI components for every team with computable pieces, keyed by team id. */
    public static Map<Long, RpiComponents> compute(Map<Long, List<GameRecord>> gamesByTeam) {
        // Pre-compute OWP for all teams; reused when building OOWP
        Map<Long, Double> owpCache = new HashMap<>();
        for (Long teamId : gamesByTeam.keySet()) {
            owpCache.put(teamId, owpForTeam(teamId, gamesByTeam));
        }

        Map<Long, RpiComponents> result = new HashMap<>();
        for (Long teamId : gamesByTeam.keySet()) {
            List<GameRecord> games = gamesByTeam.get(teamId);
            if (games.isEmpty()) continue;

            double wp = adjustedWp(games);
            Double owp = owpCache.get(teamId);
            if (owp == null) continue;

            // OOWP: average OWP(O) for each distinct opponent O
            Set<Long> opponents = games.stream().map(GameRecord::opponentId).collect(Collectors.toSet());
            double oowpSum = 0;
            int oowpCount = 0;
            for (Long oppId : opponents) {
                Double oppOwp = owpCache.get(oppId);
                if (oppOwp != null) { oowpSum += oppOwp; oowpCount++; }
            }
            if (oowpCount == 0) continue;
            double oowp = oowpSum / oowpCount;

            double rpi = 0.25 * wp + 0.50 * owp + 0.25 * oowp;
            result.put(teamId, new RpiComponents(wp, owp, oowp, rpi));
        }
        return result;
    }

    /** Location-adjusted WP (used only for a team's own WP component). */
    static double adjustedWp(List<GameRecord> games) {
        double sumWins = 0, sumTotal = 0;
        for (GameRecord g : games) {
            double mult = g.isNeutral() ? 1.0 : (g.isHome() ? (g.isWin() ? 0.6 : 1.4) : (g.isWin() ? 1.4 : 0.6));
            if (g.isWin()) sumWins += mult;
            sumTotal += mult;
        }
        return sumTotal == 0 ? 0 : sumWins / sumTotal;
    }

    /** OWP for teamId: average raw WP of each distinct opponent, excluding games vs teamId. */
    static Double owpForTeam(long teamId, Map<Long, List<GameRecord>> gamesByTeam) {
        List<GameRecord> myGames = gamesByTeam.get(teamId);
        if (myGames == null || myGames.isEmpty()) return null;
        Set<Long> opponents = myGames.stream().map(GameRecord::opponentId).collect(Collectors.toSet());
        double sum = 0;
        int count = 0;
        for (Long oppId : opponents) {
            List<GameRecord> oppGames = gamesByTeam.get(oppId);
            if (oppGames == null) continue;
            long oppWins = oppGames.stream().filter(g -> g.opponentId() != teamId && g.isWin()).count();
            long oppTotal = oppGames.stream().filter(g -> g.opponentId() != teamId).count();
            if (oppTotal == 0) continue;
            sum += (double) oppWins / oppTotal;
            count++;
        }
        return count == 0 ? null : sum / count;
    }

    /** Convenience for accumulating both perspectives of one game. */
    public static void addGame(Map<Long, List<GameRecord>> gamesByTeam,
                               long homeId, long awayId, boolean neutral, boolean homeWon) {
        gamesByTeam.computeIfAbsent(homeId, k -> new ArrayList<>())
                .add(new GameRecord(awayId, !neutral, neutral, homeWon));
        gamesByTeam.computeIfAbsent(awayId, k -> new ArrayList<>())
                .add(new GameRecord(homeId, false, neutral, !homeWon));
    }
}
