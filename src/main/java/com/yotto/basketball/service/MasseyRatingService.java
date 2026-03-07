package com.yotto.basketball.service;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.apache.commons.math3.linear.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Massey linear regression power ratings (spread and total).
 *
 * <p>Spread model: margin_predicted = β_h − β_a + α, where β_i is a team strength
 * rating and α is a home court advantage in points. Fit by OLS with L2
 * regularization on team columns (λ = {@link #LAMBDA}).
 *
 * <p>Total model: total_predicted = γ_h + γ_a + δ, where γ_i is a team
 * scoring-strength and δ is a home-game scoring intercept. Both teams contribute
 * +1 to the design vector (vs. +1/−1 for spread). Stored as {@link #MODEL_TYPE_TOTAL}.
 *
 * <p>Both systems share a single pass over game data with independent cumulative
 * accumulators (A, b) and (A_total, b_total). The normal-equations matrix for
 * each system is maintained as a rank-2 outer-product update per game.
 */
@Service
public class MasseyRatingService {

    private static final Logger log = LoggerFactory.getLogger(MasseyRatingService.class);

    public static final String MODEL_TYPE       = "MASSEY";
    public static final String MODEL_TYPE_TOTAL = "MASSEY_TOTAL";
    private static final double LAMBDA = 1.0;

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;

    public MasseyRatingService(SeasonRepository seasonRepository,
                               GameRepository gameRepository,
                               TeamPowerRatingSnapshotRepository ratingRepository,
                               PowerModelParamSnapshotRepository paramRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.ratingRepository = ratingRepository;
        this.paramRepository = paramRepository;
    }

    @Transactional
    public void calculateAndStoreForSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found, skipping Massey calculation", seasonYear);
            return;
        }

        log.info("Calculating Massey ratings for season {}", seasonYear);

        ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE);
        ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_TOTAL);
        paramRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE);
        paramRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_TOTAL);

        List<Game> finalGames = gameRepository.findBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL)
                .stream()
                .filter(g -> g.getHomeScore() != null && g.getAwayScore() != null)
                .collect(Collectors.toList());

        if (finalGames.isEmpty()) {
            log.info("No final games for season {}, skipping", seasonYear);
            return;
        }

        finalGames.sort(Comparator.comparing(g -> g.getGameDate().toLocalDate()));

        // Fixed team index: all teams that appear in any game this season
        List<Long> teamIds = finalGames.stream()
                .flatMap(g -> Stream.of(g.getHomeTeam().getId(), g.getAwayTeam().getId()))
                .distinct().sorted().collect(Collectors.toList());
        Map<Long, Integer> teamIndex = new HashMap<>();
        for (int i = 0; i < teamIds.size(); i++) teamIndex.put(teamIds.get(i), i);

        Map<Long, Team> teamsById = new HashMap<>();
        for (Game g : finalGames) {
            teamsById.put(g.getHomeTeam().getId(), g.getHomeTeam());
            teamsById.put(g.getAwayTeam().getId(), g.getAwayTeam());
        }

        int T = teamIds.size();
        int size = T + 1; // last index is HCA

        // Cumulative normal equations accumulators — spread system
        double[][] A = new double[size][size];
        double[] b = new double[size];
        // Cumulative normal equations accumulators — total system (both teams +1)
        double[][] At = new double[size][size];
        double[] bt = new double[size];
        Map<Long, Integer> gamesPlayedByTeam = new HashMap<>();

        Map<LocalDate, List<Game>> gamesByDate = finalGames.stream()
                .collect(Collectors.groupingBy(g -> g.getGameDate().toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));

        List<TeamPowerRatingSnapshot> allRatings = new ArrayList<>();
        List<PowerModelParamSnapshot> allParams = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<LocalDate, List<Game>> entry : gamesByDate.entrySet()) {
            LocalDate date = entry.getKey();

            // Outer-product updates to both accumulators for each new game
            for (Game game : entry.getValue()) {
                int hi = teamIndex.get(game.getHomeTeam().getId());
                int ai = teamIndex.get(game.getAwayTeam().getId());
                int hca = Boolean.TRUE.equals(game.getNeutralSite()) ? 0 : 1;
                int margin = game.getHomeScore() - game.getAwayScore();
                int total  = game.getHomeScore() + game.getAwayScore();

                // Spread system: x = e_hi − e_ai + hca·e_T  →  A += x·xᵀ
                A[hi][hi] += 1;
                A[ai][ai] += 1;
                A[hi][ai] -= 1;
                A[ai][hi] -= 1;
                if (hca == 1) {
                    A[hi][T] += 1;  A[T][hi] += 1;
                    A[ai][T] -= 1;  A[T][ai] -= 1;
                    A[T][T]  += 1;
                }
                b[hi] += margin;
                b[ai] -= margin;
                b[T]  += hca * margin;

                // Total system: x = e_hi + e_ai + hca·e_T  →  At += x·xᵀ
                At[hi][hi] += 1;
                At[ai][ai] += 1;
                At[hi][ai] += 1;   // positive: both teams contribute to the total
                At[ai][hi] += 1;
                if (hca == 1) {
                    At[hi][T] += 1;  At[T][hi] += 1;
                    At[ai][T] += 1;  At[T][ai] += 1;
                    At[T][T]  += 1;
                }
                bt[hi] += total;
                bt[ai] += total;
                bt[T]  += hca * total;

                gamesPlayedByTeam.merge(game.getHomeTeam().getId(), 1, Integer::sum);
                gamesPlayedByTeam.merge(game.getAwayTeam().getId(), 1, Integer::sum);
            }

            // ── Spread model ──────────────────────────────────────────────────
            double[] solution = solve(A, b, T, size);
            if (solution != null) {
                double alpha = solution[T];
                addTeamSnapshots(allRatings, ratedTeamsFor(teamIds, teamIndex, gamesPlayedByTeam, solution),
                        teamsById, season, MODEL_TYPE, date, gamesPlayedByTeam, now);
                allParams.add(paramSnap(season, MODEL_TYPE, date, "hca", alpha, now));
            }

            // ── Total model ───────────────────────────────────────────────────
            double[] solutionT = solve(At, bt, T, size);
            if (solutionT != null) {
                double delta = solutionT[T];
                addTeamSnapshots(allRatings, ratedTeamsFor(teamIds, teamIndex, gamesPlayedByTeam, solutionT),
                        teamsById, season, MODEL_TYPE_TOTAL, date, gamesPlayedByTeam, now);
                allParams.add(paramSnap(season, MODEL_TYPE_TOTAL, date, "hca_total", delta, now));
            }
        }

        ratingRepository.saveAll(allRatings);
        paramRepository.saveAll(allParams);

        log.info("Massey ratings complete for season {} — {} snapshots across {} dates",
                seasonYear, allRatings.size(), gamesByDate.size());
    }

    /** Builds a sorted list of (teamId bits, rating bits) for teams that have played at least one game. */
    private static List<long[]> ratedTeamsFor(List<Long> teamIds, Map<Long, Integer> teamIndex,
                                               Map<Long, Integer> gamesPlayed, double[] solution) {
        List<long[]> rated = new ArrayList<>();
        for (Long teamId : teamIds) {
            if (gamesPlayed.getOrDefault(teamId, 0) > 0) {
                rated.add(new long[]{teamId, Double.doubleToLongBits(solution[teamIndex.get(teamId)])});
            }
        }
        rated.sort((x, y) -> Double.compare(Double.longBitsToDouble(y[1]), Double.longBitsToDouble(x[1])));
        return rated;
    }

    /** Appends TeamPowerRatingSnapshot records to the accumulator list. */
    private static void addTeamSnapshots(List<TeamPowerRatingSnapshot> allRatings, List<long[]> rated,
                                          Map<Long, Team> teamsById, Season season, String modelType,
                                          LocalDate date, Map<Long, Integer> gamesPlayed, LocalDateTime now) {
        for (int rank = 0; rank < rated.size(); rank++) {
            Long teamId = rated.get(rank)[0];
            TeamPowerRatingSnapshot snap = new TeamPowerRatingSnapshot();
            snap.setTeam(teamsById.get(teamId));
            snap.setSeason(season);
            snap.setModelType(modelType);
            snap.setSnapshotDate(date);
            snap.setRating(Double.longBitsToDouble(rated.get(rank)[1]));
            snap.setRank(rank + 1);
            snap.setGamesPlayed(gamesPlayed.getOrDefault(teamId, 0));
            snap.setCalculatedAt(now);
            allRatings.add(snap);
        }
    }

    /** Creates a PowerModelParamSnapshot record. */
    private static PowerModelParamSnapshot paramSnap(Season season, String modelType, LocalDate date,
                                                      String paramName, double value, LocalDateTime now) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setSnapshotDate(date);
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setCalculatedAt(now);
        return p;
    }

    /**
     * Solves (A + λD)·x = b where D penalizes the first T team-rating columns.
     * Deep-copies A before adding regularization to preserve the accumulator.
     * Falls back from Cholesky to LU if the matrix is not positive definite.
     */
    private double[] solve(double[][] A, double[] b, int T, int size) {
        double[][] Areg = new double[size][size];
        for (int i = 0; i < size; i++) Areg[i] = Arrays.copyOf(A[i], size);
        for (int j = 0; j < T; j++) Areg[j][j] += LAMBDA;
        // Small numerical-stability nudge on HCA diagonal when it might be zero
        Areg[T][T] += 1e-6;

        RealMatrix mat = new Array2DRowRealMatrix(Areg, false);
        RealVector rhs = new ArrayRealVector(b, true);
        try {
            return new CholeskyDecomposition(mat).getSolver().solve(rhs).toArray();
        } catch (Exception e) {
            try {
                return new LUDecomposition(mat).getSolver().solve(rhs).toArray();
            } catch (Exception e2) {
                log.warn("Massey solve failed: {}", e2.getMessage());
                return null;
            }
        }
    }
}
