package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Season-scoped in-memory snapshot lookups for bulk prediction evaluation.
 *
 * <p>{@link PredictionService#buildInternal} makes 60–100 individual repository
 * round-trips per game; over a full-season evaluation rebuild that is hundreds of
 * thousands of queries and hours of wall clock. This cache bulk-loads the season's
 * rating and param snapshots once (projections, materialized into detached snapshot
 * instances), derives per-team recent-game lists from the already-loaded final-games
 * list, and lazily loads each team's box-score/season-stat time series with ONE query
 * per team instead of one per game.
 *
 * <p>Lookup semantics mirror the repository queries they replace EXACTLY: "latest
 * strictly before" is {@link TreeMap#lowerEntry} (matching {@code snapshot_date <
 * :beforeDate}), recent games are newest-first with {@code gameDate < :before}, and
 * the recent-game population (FINAL with recorded scores, single season) matches
 * {@code findRecentFinalGamesForTeam}. Live single-game predictions do not use this
 * class — they keep the per-query path.
 *
 * <p>Not thread-safe; one instance per evaluation run.
 */
final class SeasonPredictionCache {

    private final Long seasonId;
    private final Long priorSeasonId;   // null when the previous season doesn't exist
    private final TeamStatSnapshotRepository teamStatSnapshotRepository;
    private final TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;

    /** teamId → modelType → snapshotDate → detached snapshot. */
    private final Map<Long, Map<String, TreeMap<LocalDate, TeamPowerRatingSnapshot>>> ratings = new HashMap<>();
    /** modelType → paramName → snapshotDate → value. */
    private final Map<String, Map<String, TreeMap<LocalDate, Double>>> params = new HashMap<>();
    /** teamId → season's FINAL games involving the team, ascending by gameDate (then id). */
    private final Map<Long, List<Game>> gamesByTeam = new HashMap<>();

    // Lazy per-team loads (one query per team per season)
    private final Map<Long, TreeMap<LocalDate, Map<String, Double>>> boxStatsByTeam = new HashMap<>();
    private final Map<Long, TreeMap<LocalDate, SeasonStats>> seasonStatsByTeam = new HashMap<>();
    private final Map<Long, Optional<double[]>> priorRatingsByTeam = new HashMap<>();

    record SeasonStats(Double rpi, Double stddevMargin, Double rpiOwp) {}

    SeasonPredictionCache(Long seasonId, Long priorSeasonId, List<Game> finalGamesAscending,
                          TeamPowerRatingSnapshotRepository ratingRepository,
                          PowerModelParamSnapshotRepository paramRepository,
                          TeamStatSnapshotRepository teamStatSnapshotRepository,
                          TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository) {
        this.seasonId = seasonId;
        this.priorSeasonId = priorSeasonId;
        this.ratingRepository = ratingRepository;
        this.teamStatSnapshotRepository = teamStatSnapshotRepository;
        this.teamSeasonStatSnapshotRepository = teamSeasonStatSnapshotRepository;

        for (TeamPowerRatingSnapshotRepository.RatingRow row : ratingRepository.findRowsBySeasonId(seasonId)) {
            TeamPowerRatingSnapshot snap = new TeamPowerRatingSnapshot();
            snap.setSnapshotDate(row.getSnapshotDate());
            snap.setRating(row.getRating());
            snap.setGamesPlayed(row.getGamesPlayed());
            ratings.computeIfAbsent(row.getTeamId(), k -> new HashMap<>())
                    .computeIfAbsent(row.getModelType(), k -> new TreeMap<>())
                    .put(row.getSnapshotDate(), snap);
        }
        for (PowerModelParamSnapshotRepository.ParamRow row : paramRepository.findRowsBySeasonId(seasonId)) {
            params.computeIfAbsent(row.getModelType(), k -> new HashMap<>())
                    .computeIfAbsent(row.getParamName(), k -> new TreeMap<>())
                    .put(row.getSnapshotDate(), row.getParamValue());
        }
        // finalGamesAscending is already ordered by gameDate, id
        for (Game g : finalGamesAscending) {
            gamesByTeam.computeIfAbsent(g.getHomeTeam().getId(), k -> new ArrayList<>()).add(g);
            gamesByTeam.computeIfAbsent(g.getAwayTeam().getId(), k -> new ArrayList<>()).add(g);
        }
    }

    /** Latest snapshot strictly before cutoff, or null. Mirrors {@code findLatestBefore}. */
    TeamPowerRatingSnapshot latestRatingBefore(Long teamId, String modelType, LocalDate cutoff) {
        TreeMap<LocalDate, TeamPowerRatingSnapshot> byDate =
                ratings.getOrDefault(teamId, Map.of()).get(modelType);
        if (byDate == null) return null;
        Map.Entry<LocalDate, TeamPowerRatingSnapshot> e = byDate.lowerEntry(cutoff);
        return e == null ? null : e.getValue();
    }

    /** Latest param value strictly before cutoff, or null. Mirrors {@code findLatestParamBefore}. */
    Double latestParamBefore(String modelType, String paramName, LocalDate cutoff) {
        TreeMap<LocalDate, Double> byDate =
                params.getOrDefault(modelType, Map.of()).get(paramName);
        if (byDate == null) return null;
        Map.Entry<LocalDate, Double> e = byDate.lowerEntry(cutoff);
        return e == null ? null : e.getValue();
    }

    /**
     * The team's most recent FINAL games strictly before the instant, newest first,
     * at most {@code limit}. Mirrors {@code findRecentFinalGamesForTeam}.
     */
    List<Game> recentFinalGames(Long teamId, LocalDateTime before, int limit) {
        List<Game> asc = gamesByTeam.getOrDefault(teamId, List.of());
        int end = asc.size();
        while (end > 0 && !asc.get(end - 1).getGameDate().isBefore(before)) {
            end--;
        }
        List<Game> recent = new ArrayList<>(Math.min(limit, end));
        for (int i = end - 1; i >= 0 && recent.size() < limit; i--) {
            recent.add(asc.get(i));
        }
        return recent;
    }

    /**
     * The team's box-score stat map (statName → value) at the latest snapshot date
     * strictly before cutoff; empty map when none. Mirrors the repository's
     * {@code findLatestBefore} + {@code toStatMap}.
     */
    Map<String, Double> latestBoxStatsBefore(Long teamId, LocalDate cutoff) {
        TreeMap<LocalDate, Map<String, Double>> byDate = boxStatsByTeam.computeIfAbsent(teamId, id -> {
            TreeMap<LocalDate, Map<String, Double>> loaded = new TreeMap<>();
            for (TeamStatSnapshotRepository.StatRow row
                    : teamStatSnapshotRepository.findRowsByTeamAndSeason(id, seasonId)) {
                loaded.computeIfAbsent(row.getSnapshotDate(), k -> new LinkedHashMap<>())
                        .put(row.getStatName(), row.getValue());
            }
            return loaded;
        });
        Map.Entry<LocalDate, Map<String, Double>> e = byDate.lowerEntry(cutoff);
        return e == null ? Map.of() : Collections.unmodifiableMap(e.getValue());
    }

    /** The team's season-stat fields at the latest snapshot strictly before cutoff, or null. */
    SeasonStats latestSeasonStatsBefore(Long teamId, LocalDate cutoff) {
        TreeMap<LocalDate, SeasonStats> byDate = seasonStatsByTeam.computeIfAbsent(teamId, id -> {
            TreeMap<LocalDate, SeasonStats> loaded = new TreeMap<>();
            for (TeamSeasonStatSnapshotRepository.SeasonStatRow row
                    : teamSeasonStatSnapshotRepository.findRowsByTeamAndSeason(id, seasonId)) {
                loaded.put(row.getSnapshotDate(),
                        new SeasonStats(row.getRpi(), row.getStddevMargin(), row.getRpiOwp()));
            }
            return loaded;
        });
        Map.Entry<LocalDate, SeasonStats> e = byDate.lowerEntry(cutoff);
        return e == null ? null : e.getValue();
    }

    /**
     * [β, θ] from the previous season's final snapshots, or null unless BOTH exist.
     * Memoized; mirrors {@link PredictionService}'s priorRatings.
     */
    double[] priorRatings(Long teamId) {
        return priorRatingsByTeam.computeIfAbsent(teamId, id -> {
            if (priorSeasonId == null) return Optional.empty();
            var beta  = ratingRepository.findLatest(id, priorSeasonId, MasseyRatingService.MODEL_TYPE).orElse(null);
            var theta = ratingRepository.findLatest(id, priorSeasonId, BradleyTerryRatingService.MODEL_TYPE).orElse(null);
            if (beta == null || theta == null) return Optional.empty();
            return Optional.of(new double[]{beta.getRating(), theta.getRating()});
        }).orElse(null);
    }
}
