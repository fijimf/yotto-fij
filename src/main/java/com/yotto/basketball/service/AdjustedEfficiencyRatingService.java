package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adjusted offensive/defensive efficiency ratings (KenPom-style), fit by ridge
 * regression over per-possession scoring.
 *
 * <p>Each game with box scores on both sides yields TWO observations — each team's
 * points per 100 possessions:
 *
 * <pre>
 * 100·pts_home/poss ≈ μ + off_h − def_a + η·home_ind
 * 100·pts_away/poss ≈ μ + off_a − def_h − η·home_ind
 * </pre>
 *
 * where μ is the unpenalized league-average efficiency intercept, η the unpenalized
 * per-100 home edge (applied symmetrically: home offense +η, away offense −η, so the
 * expected home margin per 100 possessions includes 2η), and off_i/def_i are
 * L2-penalized team parameters (λ = {@link #LAMBDA}). Higher def_i = stronger
 * defense (it SUBTRACTS from opponent efficiency).
 *
 * <p>Possessions use the same estimator as {@link BoxScoreStatCalculator}:
 * {@code FGA − ORB + TO + 0.475·FTA}, averaged over the two teams. Games missing a
 * box score on either side are excluded from the fit (counted and logged) but the
 * date still gets carry-forward snapshots.
 *
 * <p>A parallel ridge fit estimates per-team adjusted tempo from the same fit games:
 * {@code possessions ≈ tempo_intercept + τ_home + τ_away}, persisted as
 * {@link #MODEL_TYPE_TEMPO} snapshots with param {@code tempo_intercept}. Together the
 * three snapshot families drive the {@link #MODEL_TYPE_PREDICTION} ({@code ADJ_EFF})
 * prediction model assembled in {@link PredictionService}.
 *
 * <p>Same incremental daily-time-series structure as {@link MasseyRatingService}:
 * cumulative normal-equations accumulators, one solve per game date, snapshots
 * persisted as model types {@link #MODEL_TYPE_OFF}/{@link #MODEL_TYPE_DEF} with
 * params {@code eff_intercept}/{@code eff_hca}. λ comes from
 * {@code app.ratings.adj-efficiency.lambda} (default 1.0).
 */
@Service
public class AdjustedEfficiencyRatingService {

    private static final Logger log = LoggerFactory.getLogger(AdjustedEfficiencyRatingService.class);

    public static final String MODEL_TYPE_OFF = "ADJ_OFF";
    public static final String MODEL_TYPE_DEF = "ADJ_DEF";
    /** Per-team adjusted tempo: possessions ≈ tempo_intercept + τ_home + τ_away. */
    public static final String MODEL_TYPE_TEMPO = "ADJ_TEMPO";
    /** Evaluated prediction model built from the ADJ_OFF/ADJ_DEF/ADJ_TEMPO snapshots. */
    public static final String MODEL_TYPE_PREDICTION = "ADJ_EFF";
    /** FTA coefficient in the possession estimate — keep equal to BoxScoreStatCalculator's. */
    private static final double FTA_POSS_WEIGHT = 0.475;

    private final SeasonGameDataLoader seasonGameDataLoader;
    private final TeamGameStatsRepository teamGameStatsRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;
    private final SnapshotJdbcWriter snapshotJdbcWriter;
    /** Ridge penalty λ on the team parameters (efficiency and tempo fits alike). */
    private final double lambda;

    public AdjustedEfficiencyRatingService(SeasonGameDataLoader seasonGameDataLoader,
                                           TeamGameStatsRepository teamGameStatsRepository,
                                           TeamPowerRatingSnapshotRepository ratingRepository,
                                           PowerModelParamSnapshotRepository paramRepository,
                                           SnapshotJdbcWriter snapshotJdbcWriter,
                                           @org.springframework.beans.factory.annotation.Value("${app.ratings.adj-efficiency.lambda:1.0}") double lambda) {
        this.seasonGameDataLoader = seasonGameDataLoader;
        this.teamGameStatsRepository = teamGameStatsRepository;
        this.ratingRepository = ratingRepository;
        this.paramRepository = paramRepository;
        this.snapshotJdbcWriter = snapshotJdbcWriter;
        this.lambda = lambda;
    }

    @Transactional
    public void calculateAndStoreForSeason(int seasonYear) {
        calculateAndStoreForSeason(seasonYear, null);
    }

    /**
     * @param fromDate watermark: accumulate the whole season's games, but only solve
     *                 and rewrite snapshots for dates {@code >= fromDate}; null = full.
     */
    @Transactional
    public void calculateAndStoreForSeason(int seasonYear, LocalDate fromDate) {
        seasonGameDataLoader.load(seasonYear)
                .ifPresent(data -> calculateAndStoreForSeason(data, fromDate));
    }

    @Transactional
    public void calculateAndStoreForSeason(SeasonGameData data, LocalDate fromDate) {
        Season season = data.season();
        int seasonYear = season.getYear();

        log.info("Calculating adjusted efficiency ratings for season {}{}", seasonYear,
                fromDate != null ? " from " + fromDate : "");
        long startMs = System.currentTimeMillis();

        if (fromDate == null) {
            ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_OFF);
            ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_DEF);
            ratingRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_TEMPO);
            paramRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_OFF);
            paramRepository.deleteBySeasonIdAndModelType(season.getId(), MODEL_TYPE_TEMPO);
        } else {
            ratingRepository.deleteBySeasonIdAndModelTypeFromDate(season.getId(), MODEL_TYPE_OFF, fromDate);
            ratingRepository.deleteBySeasonIdAndModelTypeFromDate(season.getId(), MODEL_TYPE_DEF, fromDate);
            ratingRepository.deleteBySeasonIdAndModelTypeFromDate(season.getId(), MODEL_TYPE_TEMPO, fromDate);
            paramRepository.deleteBySeasonIdAndModelTypeFromDate(season.getId(), MODEL_TYPE_OFF, fromDate);
            paramRepository.deleteBySeasonIdAndModelTypeFromDate(season.getId(), MODEL_TYPE_TEMPO, fromDate);
        }

        List<Game> finalGames = data.finalGames();
        if (finalGames.isEmpty()) {
            log.info("No final games for season {}, skipping", seasonYear);
            return;
        }

        // Box scores keyed by (game, team); loaded once for the season
        Map<Long, Map<Long, TeamGameStats>> statsByGame = new HashMap<>();
        for (TeamGameStats s : teamGameStatsRepository.findBySeasonId(season.getId())) {
            statsByGame.computeIfAbsent(s.getGame().getId(), k -> new HashMap<>())
                    .put(s.getTeam().getId(), s);
        }

        List<Long> teamIds = finalGames.stream()
                .flatMap(g -> Stream.of(g.getHomeTeam().getId(), g.getAwayTeam().getId()))
                .distinct().sorted().collect(Collectors.toList());
        Map<Long, Integer> teamIndex = new HashMap<>();
        for (int i = 0; i < teamIds.size(); i++) teamIndex.put(teamIds.get(i), i);
        Map<Long, Team> teamsById = data.teamsById();

        int T    = teamIds.size();
        int size = 2 * T + 2;   // off columns, def columns, intercept, HCA
        int MU   = 2 * T;
        int HCA  = 2 * T + 1;

        double[][] A = new double[size][size];
        double[]   b = new double[size];
        // Tempo fit: possessions ≈ tempo_intercept + τ_home + τ_away (τ ridge-penalized)
        int tSize = T + 1;
        int TI    = T;   // tempo intercept column
        double[][] At = new double[tSize][tSize];
        double[]   bt = new double[tSize];
        Map<Long, Integer> gamesPlayedByTeam = new HashMap<>();   // usable (fit) games only
        int fitGames = 0, skippedNoBox = 0;

        List<TeamPowerRatingSnapshot> allRatings = new ArrayList<>();
        List<PowerModelParamSnapshot> allParams  = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<LocalDate, List<Game>> entry : data.gamesByDate().entrySet()) {
            LocalDate date = entry.getKey();

            for (Game game : entry.getValue()) {
                Map<Long, TeamGameStats> gameStats = statsByGame.getOrDefault(game.getId(), Map.of());
                TeamGameStats sh = gameStats.get(game.getHomeTeam().getId());
                TeamGameStats sa = gameStats.get(game.getAwayTeam().getId());
                Double poss = possessions(sh, sa);
                if (poss == null || poss <= 0) {
                    skippedNoBox++;
                    continue;
                }
                int hi = teamIndex.get(game.getHomeTeam().getId());
                int ai = teamIndex.get(game.getAwayTeam().getId());
                int ind = Boolean.TRUE.equals(game.getNeutralSite()) ? 0 : 1;

                addObservation(A, b, hi, T + ai, +ind, 100.0 * game.getHomeScore() / poss, MU, HCA);
                addObservation(A, b, ai, T + hi, -ind, 100.0 * game.getAwayScore() / poss, MU, HCA);
                addTempoObservation(At, bt, hi, ai, TI, poss);

                gamesPlayedByTeam.merge(game.getHomeTeam().getId(), 1, Integer::sum);
                gamesPlayedByTeam.merge(game.getAwayTeam().getId(), 1, Integer::sum);
                fitGames++;
            }

            if (fitGames == 0 || (fromDate != null && date.isBefore(fromDate))) {
                continue;
            }

            double[] solution      = solve(A, b, 2 * T, size);
            double[] tempoSolution = solve(At, bt, T, tSize);
            if (solution == null || tempoSolution == null) {
                continue;
            }
            addTeamSnapshots(allRatings, rated(teamIds, gamesPlayedByTeam, teamIndex, solution, 0),
                    teamsById, season, MODEL_TYPE_OFF, date, gamesPlayedByTeam, now);
            addTeamSnapshots(allRatings, rated(teamIds, gamesPlayedByTeam, teamIndex, solution, T),
                    teamsById, season, MODEL_TYPE_DEF, date, gamesPlayedByTeam, now);
            addTeamSnapshots(allRatings, rated(teamIds, gamesPlayedByTeam, teamIndex, tempoSolution, 0),
                    teamsById, season, MODEL_TYPE_TEMPO, date, gamesPlayedByTeam, now);
            allParams.add(paramSnap(season, MODEL_TYPE_OFF, date, "eff_intercept", solution[MU], now));
            allParams.add(paramSnap(season, MODEL_TYPE_OFF, date, "eff_hca", solution[HCA], now));
            allParams.add(paramSnap(season, MODEL_TYPE_TEMPO, date, "tempo_intercept", tempoSolution[TI], now));
        }

        long saveStartMs = System.currentTimeMillis();
        snapshotJdbcWriter.writeTeamPowerRatingSnapshots(allRatings);
        snapshotJdbcWriter.writePowerModelParamSnapshots(allParams);

        long endMs = System.currentTimeMillis();
        log.info("Adjusted efficiency ratings complete for season {} — {} snapshots, {} fit games, "
                        + "{} games without usable box scores, in {} ms (save {} ms)",
                seasonYear, allRatings.size(), fitGames, skippedNoBox, endMs - startMs, endMs - saveStartMs);
    }

    /**
     * Possession estimate averaged over both teams' box scores
     * ({@code FGA − ORB + TO + 0.475·FTA}); null when either side is unusable.
     */
    static Double possessions(TeamGameStats home, TeamGameStats away) {
        Double h = teamPossessions(home);
        Double a = teamPossessions(away);
        if (h == null || a == null) return null;
        return (h + a) / 2.0;
    }

    private static Double teamPossessions(TeamGameStats s) {
        if (s == null || s.getFgAttempted() == null || s.getOffensiveReb() == null
                || s.getTurnovers() == null || s.getFtAttempted() == null) {
            return null;
        }
        return s.getFgAttempted() - s.getOffensiveReb() + s.getTurnovers()
                + FTA_POSS_WEIGHT * s.getFtAttempted();
    }

    /**
     * Accumulates one tempo observation's outer product: x has +1 at both team columns
     * and +1 at the intercept; y is the game's estimated possessions.
     */
    static void addTempoObservation(double[][] At, double[] bt, int hi, int ai,
                                    int TI, double y) {
        At[hi][hi] += 1;
        At[ai][ai] += 1;
        At[TI][TI] += 1;
        At[hi][ai] += 1;  At[ai][hi] += 1;
        At[hi][TI] += 1;  At[TI][hi] += 1;
        At[ai][TI] += 1;  At[TI][ai] += 1;
        bt[hi] += y;
        bt[ai] += y;
        bt[TI] += y;
    }

    /**
     * Accumulates one observation's outer product: x has +1 at offIdx, −1 at defIdx,
     * +1 at MU, and c ∈ {−1, 0, +1} at HCA; y is points per 100 possessions.
     */
    static void addObservation(double[][] A, double[] b, int offIdx, int defIdx,
                               int c, double y, int MU, int HCA) {
        A[offIdx][offIdx] += 1;
        A[defIdx][defIdx] += 1;
        A[MU][MU]         += 1;
        A[HCA][HCA]       += c * c;

        A[offIdx][defIdx] -= 1;  A[defIdx][offIdx] -= 1;
        A[offIdx][MU]     += 1;  A[MU][offIdx]     += 1;
        A[defIdx][MU]     -= 1;  A[MU][defIdx]     -= 1;
        if (c != 0) {
            A[offIdx][HCA] += c;  A[HCA][offIdx] += c;
            A[defIdx][HCA] -= c;  A[HCA][defIdx] -= c;
            A[MU][HCA]     += c;  A[HCA][MU]     += c;
        }

        b[offIdx] += y;
        b[defIdx] -= y;
        b[MU]     += y;
        b[HCA]    += c * y;
    }

    /** Sorted (teamId, ratingBits) for played teams, reading solution[offset + teamIndex]. */
    private static List<long[]> rated(List<Long> teamIds, Map<Long, Integer> gamesPlayed,
                                      Map<Long, Integer> teamIndex, double[] solution, int offset) {
        List<long[]> result = new ArrayList<>();
        for (Long teamId : teamIds) {
            if (gamesPlayed.getOrDefault(teamId, 0) > 0) {
                result.add(new long[]{teamId,
                        Double.doubleToLongBits(solution[offset + teamIndex.get(teamId)])});
            }
        }
        result.sort((x, y) -> Double.compare(Double.longBitsToDouble(y[1]), Double.longBitsToDouble(x[1])));
        return result;
    }

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

    private double[] solve(double[][] A, double[] b, int penalized, int size) {
        return solve(A, b, penalized, size, lambda);
    }

    /**
     * Solves (A + λD)·x = b where D penalizes the first {@code penalized} columns
     * (all team params); the trailing unpenalized params get only a stability nudge.
     * Static with an explicit λ so the tuning sweep can reuse it.
     */
    static double[] solve(double[][] A, double[] b, int penalized, int size, double lambda) {
        double[][] Areg = new double[size][size];
        for (int i = 0; i < size; i++) Areg[i] = Arrays.copyOf(A[i], size);
        for (int j = 0; j < penalized; j++) Areg[j][j] += lambda;
        for (int j = penalized; j < size; j++) Areg[j][j] += 1e-6;

        RealMatrix mat = new Array2DRowRealMatrix(Areg, false);
        RealVector rhs = new ArrayRealVector(b, true);
        try {
            return new CholeskyDecomposition(mat).getSolver().solve(rhs).toArray();
        } catch (Exception e) {
            try {
                return new LUDecomposition(mat).getSolver().solve(rhs).toArray();
            } catch (Exception e2) {
                log.warn("Adjusted efficiency solve failed: {}", e2.getMessage());
                return null;
            }
        }
    }
}
