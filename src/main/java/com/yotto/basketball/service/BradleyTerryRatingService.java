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
 * Bradley-Terry logistic regression power ratings.
 *
 * <p>Model: P(home wins) = σ(θ_h − θ_a + α), where θ_i is a log-odds team
 * strength and α is a home court advantage in log-odds. Fit by maximum
 * likelihood with L2 regularization on team parameters (λ = {@link #LAMBDA}).
 *
 * <p>Newton-Raphson is used for optimization. The parameter vector from each
 * game date is used as the warm start for the next date, so convergence
 * typically requires only 1–3 iterations rather than the 5–15 needed from cold
 * start.
 */
@Service
public class BradleyTerryRatingService {

    private static final Logger log = LoggerFactory.getLogger(BradleyTerryRatingService.class);

    public static final String MODEL_TYPE = "BRADLEY_TERRY";
    private static final double LAMBDA   = 0.01;
    private static final double CONVERGE = 1e-6;
    private static final int    MAX_ITER = 50;

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;

    public BradleyTerryRatingService(SeasonRepository seasonRepository,
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
            log.warn("Season {} not found, skipping Bradley-Terry calculation", seasonYear);
            return;
        }

        log.info("Calculating Bradley-Terry ratings for season {}", seasonYear);

        ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE);
        paramRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE);

        List<Game> finalGames = gameRepository.findBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL)
                .stream()
                .filter(g -> g.getHomeScore() != null && g.getAwayScore() != null)
                .filter(g -> !g.getHomeScore().equals(g.getAwayScore())) // ties impossible in CBB but guard anyway
                .collect(Collectors.toList());

        if (finalGames.isEmpty()) {
            log.info("No final games for season {}, skipping", seasonYear);
            return;
        }

        finalGames.sort(Comparator.comparing(g -> g.getGameDate().toLocalDate()));

        // Fixed team index
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

        int T    = teamIds.size();
        int size = T + 1; // last index is α (HCA)

        // params[0..T-1] = θ (team strengths), params[T] = α (HCA)
        double[] params = new double[size];

        // Accumulated game records: {homeIdx, awayIdx, homeWon(1/0), nonNeutral(1/0)}
        List<int[]> seenGames = new ArrayList<>(finalGames.size());

        Map<Long, Integer> gamesPlayedByTeam = new HashMap<>();

        Map<LocalDate, List<Game>> gamesByDate = finalGames.stream()
                .collect(Collectors.groupingBy(g -> g.getGameDate().toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));

        List<TeamPowerRatingSnapshot> allRatings = new ArrayList<>();
        List<PowerModelParamSnapshot> allParams  = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<LocalDate, List<Game>> entry : gamesByDate.entrySet()) {
            LocalDate date = entry.getKey();

            for (Game game : entry.getValue()) {
                int hi = teamIndex.get(game.getHomeTeam().getId());
                int ai = teamIndex.get(game.getAwayTeam().getId());
                int homeWon   = game.getHomeScore() > game.getAwayScore() ? 1 : 0;
                int nonNeutral = Boolean.TRUE.equals(game.getNeutralSite()) ? 0 : 1;
                seenGames.add(new int[]{hi, ai, homeWon, nonNeutral});
                gamesPlayedByTeam.merge(game.getHomeTeam().getId(), 1, Integer::sum);
                gamesPlayedByTeam.merge(game.getAwayTeam().getId(), 1, Integer::sum);
            }

            // Newton-Raphson, warm-starting from current params
            newtonRaphson(params, seenGames, T, size);

            double alpha = params[T];

            // Rank teams that have played at least one game
            List<long[]> ratedTeams = new ArrayList<>();
            for (Long teamId : teamIds) {
                if (gamesPlayedByTeam.getOrDefault(teamId, 0) > 0) {
                    ratedTeams.add(new long[]{teamId, Double.doubleToLongBits(params[teamIndex.get(teamId)])});
                }
            }
            ratedTeams.sort((x, y) -> Double.compare(
                    Double.longBitsToDouble(y[1]), Double.longBitsToDouble(x[1])));

            for (int rank = 0; rank < ratedTeams.size(); rank++) {
                Long teamId = ratedTeams.get(rank)[0];
                TeamPowerRatingSnapshot snap = new TeamPowerRatingSnapshot();
                snap.setTeam(teamsById.get(teamId));
                snap.setSeason(season);
                snap.setModelType(MODEL_TYPE);
                snap.setSnapshotDate(date);
                snap.setRating(Double.longBitsToDouble(ratedTeams.get(rank)[1]));
                snap.setRank(rank + 1);
                snap.setGamesPlayed(gamesPlayedByTeam.getOrDefault(teamId, 0));
                snap.setCalculatedAt(now);
                allRatings.add(snap);
            }

            PowerModelParamSnapshot hcaSnap = new PowerModelParamSnapshot();
            hcaSnap.setSeason(season);
            hcaSnap.setModelType(MODEL_TYPE);
            hcaSnap.setSnapshotDate(date);
            hcaSnap.setParamName("hca");
            hcaSnap.setParamValue(alpha);
            hcaSnap.setCalculatedAt(now);
            allParams.add(hcaSnap);
        }

        ratingRepository.saveAll(allRatings);
        paramRepository.saveAll(allParams);

        log.info("Bradley-Terry ratings complete for season {} — {} snapshots across {} dates",
                seasonYear, allRatings.size(), gamesByDate.size());
    }

    /**
     * Newton-Raphson maximization of the regularized log-likelihood.
     *
     * <p>Update rule: params -= H⁻¹ · ∇L  (H is negative definite, so this
     * moves toward the maximum; equivalently, solve H·δ = ∇L then subtract δ).
     */
    private void newtonRaphson(double[] params, List<int[]> games, int T, int size) {
        for (int iter = 0; iter < MAX_ITER; iter++) {
            double[] grad = new double[size];
            double[][] H  = new double[size][size];

            for (int[] game : games) {
                int hi = game[0], ai = game[1], y = game[2], nn = game[3];
                double logit = params[hi] - params[ai] + params[T] * nn;
                double p = sigmoid(logit);
                double r = y - p;
                double w = p * (1 - p);

                grad[hi] += r;
                grad[ai] -= r;
                grad[T]  += r * nn;

                H[hi][hi] -= w;
                H[ai][ai] -= w;
                H[hi][ai] += w;
                H[ai][hi] += w;
                H[T][T]   -= w * nn;
                H[hi][T]  -= w * nn;  H[T][hi] -= w * nn;
                H[ai][T]  += w * nn;  H[T][ai] += w * nn;
            }

            // L2 regularization on team parameters only
            for (int j = 0; j < T; j++) {
                grad[j] -= LAMBDA * params[j];
                H[j][j] -= LAMBDA;
            }
            // Small stability nudge on HCA diagonal in case all seen games are neutral-site
            H[T][T] -= 1e-6;

            // Check convergence
            double gradNormSq = 0;
            for (double g : grad) gradNormSq += g * g;
            if (Math.sqrt(gradNormSq) < CONVERGE) break;

            // Solve H·δ = ∇L, then params -= δ
            try {
                RealVector delta = new LUDecomposition(new Array2DRowRealMatrix(H, false))
                        .getSolver()
                        .solve(new ArrayRealVector(grad, false));
                for (int j = 0; j < size; j++) params[j] -= delta.getEntry(j);
            } catch (Exception e) {
                log.debug("Newton step failed at iteration {}: {}", iter, e.getMessage());
                break;
            }
        }
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
