package com.yotto.basketball.service;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calculates and persists time-series statistics for each team in a season.
 *
 * <p>Two passes per season date:
 * <ol>
 *   <li>Build per-team cumulative snapshots (win %, means, std devs, Pearson r, rolling window).</li>
 *   <li>Derive league-wide and per-conference population distributions from those snapshots,
 *       then back-fill z-scores on each snapshot.</li>
 * </ol>
 *
 * <p>The calculation is idempotent — existing rows for the season are deleted before recalculating.
 */
@Service
public class StatisticsTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsTimeSeriesService.class);

    private static final List<String> STAT_NAMES =
            List.of("win_pct", "mean_pts_for", "mean_pts_against", "mean_margin", "correlation_pts");

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final TeamSeasonStatSnapshotRepository snapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;

    public StatisticsTimeSeriesService(SeasonRepository seasonRepository,
                                       GameRepository gameRepository,
                                       ConferenceMembershipRepository membershipRepository,
                                       TeamSeasonStatSnapshotRepository snapshotRepository,
                                       SeasonPopulationStatRepository popStatRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.membershipRepository = membershipRepository;
        this.snapshotRepository = snapshotRepository;
        this.popStatRepository = popStatRepository;
    }

    @Transactional
    public void calculateAndStoreForSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found, skipping time-series stats", seasonYear);
            return;
        }

        log.info("Calculating time-series stats for season {}", seasonYear);

        // Wipe existing data (idempotent)
        snapshotRepository.deleteBySeasonId(season.getId());
        popStatRepository.deleteBySeasonId(season.getId());

        // Conference membership maps
        List<ConferenceMembership> memberships = membershipRepository.findBySeasonId(season.getId());
        Map<Long, Conference> conferenceByTeamId = new HashMap<>();
        Map<Long, Long> confIdByTeamId = new HashMap<>();
        Map<Long, Conference> conferencesById = new HashMap<>();
        for (ConferenceMembership cm : memberships) {
            Conference conf = cm.getConference();
            Long teamId = cm.getTeam().getId();
            conferenceByTeamId.put(teamId, conf);
            confIdByTeamId.put(teamId, conf.getId());
            conferencesById.put(conf.getId(), conf);
        }

        // All final games sorted by date ascending
        List<Game> finalGames = gameRepository.findBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL);
        finalGames.sort(Comparator.comparing(g -> g.getGameDate().toLocalDate()));

        Map<Long, Team> teamsById = new HashMap<>();
        for (Game g : finalGames) {
            teamsById.put(g.getHomeTeam().getId(), g.getHomeTeam());
            teamsById.put(g.getAwayTeam().getId(), g.getAwayTeam());
        }

        // Group games by date (preserving sorted order)
        Map<LocalDate, List<Game>> gamesByDate = finalGames.stream()
                .collect(Collectors.groupingBy(g -> g.getGameDate().toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, TeamAcc> accumulators = new HashMap<>();
        List<TeamSeasonStatSnapshot> allSnapshots = new ArrayList<>();
        List<SeasonPopulationStat> allPopStats = new ArrayList<>();

        for (Map.Entry<LocalDate, List<Game>> entry : gamesByDate.entrySet()) {
            LocalDate date = entry.getKey();

            // Update accumulators for games on this date
            for (Game game : entry.getValue()) {
                if (game.getHomeScore() == null || game.getAwayScore() == null) continue;
                Long homeId = game.getHomeTeam().getId();
                Long awayId = game.getAwayTeam().getId();
                int homeScore = game.getHomeScore();
                int awayScore = game.getAwayScore();
                boolean homeWon = homeScore > awayScore;
                accumulators.computeIfAbsent(homeId, k -> new TeamAcc()).addGame(homeScore, awayScore, homeWon);
                accumulators.computeIfAbsent(awayId, k -> new TeamAcc()).addGame(awayScore, homeScore, !homeWon);
            }

            // Build snapshots (without z-scores yet)
            List<TeamSeasonStatSnapshot> dateSnaps = new ArrayList<>(accumulators.size());
            for (Map.Entry<Long, TeamAcc> accEntry : accumulators.entrySet()) {
                Long teamId = accEntry.getKey();
                Team team = teamsById.get(teamId);
                if (team == null) continue;
                dateSnaps.add(buildSnapshot(team, season, date, accEntry.getValue()));
            }

            // Compute league-wide population stats
            Map<String, PopData> leaguePop = computePopStats(dateSnaps);

            // Compute per-conference population stats
            Map<Long, Map<String, PopData>> confPop = new HashMap<>();
            Map<Long, List<TeamSeasonStatSnapshot>> snapsByConf = dateSnaps.stream()
                    .filter(s -> confIdByTeamId.containsKey(s.getTeam().getId()))
                    .collect(Collectors.groupingBy(s -> confIdByTeamId.get(s.getTeam().getId())));
            for (Map.Entry<Long, List<TeamSeasonStatSnapshot>> ce : snapsByConf.entrySet()) {
                confPop.put(ce.getKey(), computePopStats(ce.getValue()));
            }

            // Apply z-scores to snapshots
            for (TeamSeasonStatSnapshot snap : dateSnaps) {
                applyLeagueZscores(snap, leaguePop);
                Long confId = confIdByTeamId.get(snap.getTeam().getId());
                if (confId != null && confPop.containsKey(confId)) {
                    applyConfZscores(snap, confPop.get(confId));
                }
            }

            // Convert population stats to entities
            for (Map.Entry<String, PopData> pe : leaguePop.entrySet()) {
                allPopStats.add(toPopEntity(season, null, date, pe.getKey(), pe.getValue()));
            }
            for (Map.Entry<Long, Map<String, PopData>> ce : confPop.entrySet()) {
                Conference conf = conferencesById.get(ce.getKey());
                for (Map.Entry<String, PopData> pe : ce.getValue().entrySet()) {
                    allPopStats.add(toPopEntity(season, conf, date, pe.getKey(), pe.getValue()));
                }
            }

            allSnapshots.addAll(dateSnaps);
        }

        snapshotRepository.saveAll(allSnapshots);
        popStatRepository.saveAll(allPopStats);

        log.info("Time-series stats complete for season {} — {} snapshots, {} population stat rows",
                seasonYear, allSnapshots.size(), allPopStats.size());
    }

    // ── Snapshot builder ──────────────────────────────────────────────────────

    private TeamSeasonStatSnapshot buildSnapshot(Team team, Season season, LocalDate date, TeamAcc acc) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(date);
        s.setGamesPlayed(acc.n);
        s.setWins(acc.wins);
        s.setLosses(acc.n - acc.wins);
        s.setWinPct(acc.n > 0 ? (double) acc.wins / acc.n : null);
        s.setMeanPtsFor(acc.meanX());
        s.setStddevPtsFor(acc.stddevX());
        s.setMeanPtsAgainst(acc.meanY());
        s.setStddevPtsAgainst(acc.stddevY());
        s.setCorrelationPts(acc.correlation());
        s.setMeanMargin(acc.meanMargin());
        s.setStddevMargin(acc.stddevMargin());
        s.setRollingWins(acc.rollingWins());
        s.setRollingLosses(acc.rollingLosses());
        s.setRollingMeanPtsFor(acc.rollingMeanX());
        s.setRollingMeanPtsAgainst(acc.rollingMeanY());
        return s;
    }

    // ── Population statistics ─────────────────────────────────────────────────

    private static final Map<String, Function<TeamSeasonStatSnapshot, Double>> GETTERS = Map.of(
            "win_pct",          TeamSeasonStatSnapshot::getWinPct,
            "mean_pts_for",     TeamSeasonStatSnapshot::getMeanPtsFor,
            "mean_pts_against", TeamSeasonStatSnapshot::getMeanPtsAgainst,
            "mean_margin",      TeamSeasonStatSnapshot::getMeanMargin,
            "correlation_pts",  TeamSeasonStatSnapshot::getCorrelationPts
    );

    private Map<String, PopData> computePopStats(List<TeamSeasonStatSnapshot> snaps) {
        Map<String, PopData> result = new LinkedHashMap<>();
        for (String statName : STAT_NAMES) {
            Function<TeamSeasonStatSnapshot, Double> getter = GETTERS.get(statName);
            List<Double> values = snaps.stream()
                    .map(getter)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (values.isEmpty()) continue;

            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double min  = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max  = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double stddev = 0;
            if (values.size() > 1) {
                double variance = values.stream()
                        .mapToDouble(v -> (v - mean) * (v - mean))
                        .sum() / (values.size() - 1);
                stddev = Math.sqrt(variance);
            }
            result.put(statName, new PopData(mean, stddev, min, max, values.size()));
        }
        return result;
    }

    private SeasonPopulationStat toPopEntity(Season season, Conference conference,
                                             LocalDate date, String statName, PopData pd) {
        SeasonPopulationStat e = new SeasonPopulationStat();
        e.setSeason(season);
        e.setConference(conference);
        e.setStatDate(date);
        e.setStatName(statName);
        e.setPopMean(pd.mean);
        e.setPopStddev(pd.stddev);
        e.setPopMin(pd.min);
        e.setPopMax(pd.max);
        e.setTeamCount(pd.count);
        return e;
    }

    // ── Z-score application ───────────────────────────────────────────────────

    private void applyLeagueZscores(TeamSeasonStatSnapshot s, Map<String, PopData> pop) {
        s.setZscoreWinPct(zscore(s.getWinPct(), pop.get("win_pct")));
        s.setZscoreMeanPtsFor(zscore(s.getMeanPtsFor(), pop.get("mean_pts_for")));
        s.setZscoreMeanPtsAgainst(zscore(s.getMeanPtsAgainst(), pop.get("mean_pts_against")));
        s.setZscoreMeanMargin(zscore(s.getMeanMargin(), pop.get("mean_margin")));
        s.setZscoreCorrelationPts(zscore(s.getCorrelationPts(), pop.get("correlation_pts")));
    }

    private void applyConfZscores(TeamSeasonStatSnapshot s, Map<String, PopData> pop) {
        s.setConfZscoreWinPct(zscore(s.getWinPct(), pop.get("win_pct")));
        s.setConfZscoreMeanPtsFor(zscore(s.getMeanPtsFor(), pop.get("mean_pts_for")));
        s.setConfZscoreMeanPtsAgainst(zscore(s.getMeanPtsAgainst(), pop.get("mean_pts_against")));
        s.setConfZscoreMeanMargin(zscore(s.getMeanMargin(), pop.get("mean_margin")));
    }

    private Double zscore(Double value, PopData pop) {
        if (value == null || pop == null || pop.stddev == 0) return null;
        return (value - pop.mean) / pop.stddev;
    }

    // ── Inner helpers ─────────────────────────────────────────────────────────

    private record PopData(double mean, double stddev, double min, double max, int count) {}

    /**
     * Maintains running sums for efficient per-game-date stat computation.
     * Uses the Pearson correlation numerator/denominator form that avoids
     * storing individual scores.
     */
    private static class TeamAcc {
        int n = 0;
        int wins = 0;
        double sumX = 0, sumY = 0, sumX2 = 0, sumY2 = 0, sumXY = 0;
        final ArrayDeque<double[]> rolling = new ArrayDeque<>(); // [ptsFor, ptsAgainst, won]

        void addGame(int ptsFor, int ptsAgainst, boolean won) {
            n++;
            if (won) wins++;
            double x = ptsFor, y = ptsAgainst;
            sumX += x; sumY += y;
            sumX2 += x * x; sumY2 += y * y;
            sumXY += x * y;
            rolling.addLast(new double[]{x, y, won ? 1 : 0});
            if (rolling.size() > 10) rolling.removeFirst();
        }

        Double meanX() { return n > 0 ? sumX / n : null; }
        Double meanY() { return n > 0 ? sumY / n : null; }

        Double stddevX() {
            if (n < 2) return null;
            double v = (sumX2 - sumX * sumX / n) / (n - 1);
            return v >= 0 ? Math.sqrt(v) : 0.0;
        }

        Double stddevY() {
            if (n < 2) return null;
            double v = (sumY2 - sumY * sumY / n) / (n - 1);
            return v >= 0 ? Math.sqrt(v) : 0.0;
        }

        Double meanMargin() { return n > 0 ? (sumX - sumY) / n : null; }

        Double stddevMargin() {
            if (n < 2) return null;
            // Margin = X - Y, so: sumM = sumX - sumY, sumM2 = sumX2 + sumY2 - 2*sumXY
            double sumM = sumX - sumY;
            double sumM2 = sumX2 + sumY2 - 2 * sumXY;
            double v = (sumM2 - sumM * sumM / n) / (n - 1);
            return v >= 0 ? Math.sqrt(v) : 0.0;
        }

        Double correlation() {
            if (n < 2) return null;
            double num = n * sumXY - sumX * sumY;
            double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
            return den == 0 ? null : num / den;
        }

        int rollingWins()  { return (int) rolling.stream().filter(g -> g[2] > 0).count(); }
        int rollingLosses(){ return rolling.size() - rollingWins(); }
        Double rollingMeanX() { return rolling.isEmpty() ? null : rolling.stream().mapToDouble(g -> g[0]).average().orElse(0); }
        Double rollingMeanY() { return rolling.isEmpty() ? null : rolling.stream().mapToDouble(g -> g[1]).average().orElse(0); }
    }
}
