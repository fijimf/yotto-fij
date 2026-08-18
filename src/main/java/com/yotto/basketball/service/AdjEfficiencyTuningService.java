package com.yotto.basketball.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.RatingTuningRun;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.repository.RatingTuningRunRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Walk-forward λ tuning for the adjusted-efficiency ridge fits (spec W4).
 *
 * <p>For each season with box scores, the season is replayed date by date IN MEMORY
 * (no snapshot writes): each date's fit-games are first predicted with the efficiency
 * and tempo solutions fit through the PRIOR date (the leakage rule), then the date is
 * absorbed and re-solved. Because the normal-equations accumulators are λ-independent,
 * one accumulation pass serves the whole grid — only the per-date solves are per-λ.
 *
 * <p>Per (λ, season) the report records n, spread MAE, log loss (win prob Φ(spread/σ)),
 * and the empirical residual σ (population stddev of actual − predicted margin), plus a
 * pooled all-season aggregate per λ. Games where either team has no prior fit game are
 * skipped, never imputed. The sweep writes only to {@code rating_tuning_runs} —
 * promotion of a winning λ is a manual config change
 * ({@code app.ratings.adj-efficiency.lambda}).
 */
@Service
public class AdjEfficiencyTuningService {

    private static final Logger log = LoggerFactory.getLogger(AdjEfficiencyTuningService.class);

    /** A RUNNING row older than this is presumed orphaned (app restart) and failed. */
    static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(2);

    private final SeasonGameDataLoader seasonGameDataLoader;
    private final TeamGameStatsRepository teamGameStatsRepository;
    private final SeasonRepository seasonRepository;
    private final RatingTuningRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final double marginSigma;
    private final List<Double> lambdaGrid;

    public AdjEfficiencyTuningService(SeasonGameDataLoader seasonGameDataLoader,
                                      TeamGameStatsRepository teamGameStatsRepository,
                                      SeasonRepository seasonRepository,
                                      RatingTuningRunRepository runRepository,
                                      ObjectMapper objectMapper,
                                      @Value("${app.prediction.margin-sigma:11.0}") double marginSigma,
                                      @Value("${app.ratings.adj-efficiency.lambda-grid:0.25,0.5,1,2,4,8,16,32}") String lambdaGrid) {
        this.seasonGameDataLoader = seasonGameDataLoader;
        this.teamGameStatsRepository = teamGameStatsRepository;
        this.seasonRepository = seasonRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.marginSigma = marginSigma;
        this.lambdaGrid = parseGrid(lambdaGrid);
    }

    static List<Double> parseGrid(String csv) {
        List<Double> grid = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                grid.add(Double.parseDouble(trimmed));
            }
        }
        if (grid.isEmpty()) {
            throw new IllegalArgumentException("lambda grid is empty: " + csv);
        }
        return List.copyOf(grid);
    }

    // ── Run lifecycle ─────────────────────────────────────────────────────────

    /**
     * Claims a run row (single-flight: rejects when one is RUNNING and fresh) and
     * returns its id. The caller then launches {@link #runSweepAsync}.
     * @throws IllegalStateException when a sweep is already in progress
     */
    public Long startRun() {
        for (RatingTuningRun running : runRepository.findByStatus(RatingTuningRun.Status.RUNNING)) {
            if (running.getStartedAt().isAfter(LocalDateTime.now().minus(STALE_RUN_TIMEOUT))) {
                throw new IllegalStateException("A λ sweep is already in progress (run " + running.getId() + ")");
            }
            running.setStatus(RatingTuningRun.Status.FAILED);
            running.setFinishedAt(LocalDateTime.now());
            running.setError("Presumed orphaned (no finish within " + STALE_RUN_TIMEOUT + ")");
            runRepository.save(running);
        }
        RatingTuningRun run = new RatingTuningRun();
        run.setModelType(AdjustedEfficiencyRatingService.MODEL_TYPE_PREDICTION);
        run.setStatus(RatingTuningRun.Status.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setParams(toJson(Map.of("grid", lambdaGrid, "sigma", marginSigma)));
        return runRepository.save(run).getId();
    }

    @Async("tuningExecutor")
    public void runSweepAsync(Long runId) {
        RatingTuningRun run = runRepository.findById(runId).orElseThrow();
        try {
            SweepReport report = runSweep();
            run.setResults(toJson(report));
            run.setStatus(RatingTuningRun.Status.COMPLETED);
        } catch (Exception e) {
            log.error("λ sweep run {} failed", runId, e);
            run.setStatus(RatingTuningRun.Status.FAILED);
            run.setError(e.getMessage());
        }
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    public List<RatingTuningRun> recentRuns() {
        return runRepository.findTop10ByOrderByStartedAtDesc();
    }

    /** A run plus its parsed report (null until COMPLETED or on parse failure). */
    public record RunView(RatingTuningRun run, SweepReport report, String duration) {}

    public List<RunView> recentRunViews() {
        return recentRuns().stream().map(run -> {
            SweepReport report = null;
            if (run.getResults() != null) {
                try {
                    report = objectMapper.readValue(run.getResults(), SweepReport.class);
                } catch (Exception e) {
                    log.warn("Unparseable tuning results for run {}", run.getId());
                }
            }
            String duration = null;
            if (run.getFinishedAt() != null) {
                Duration d = Duration.between(run.getStartedAt(), run.getFinishedAt());
                duration = d.toMinutes() + "m " + (d.toSecondsPart()) + "s";
            }
            return new RunView(run, report, duration);
        }).toList();
    }

    public boolean isSweepInProgress() {
        return runRepository.findByStatus(RatingTuningRun.Status.RUNNING).stream()
                .anyMatch(r -> r.getStartedAt().isAfter(LocalDateTime.now().minus(STALE_RUN_TIMEOUT)));
    }

    // ── The sweep ─────────────────────────────────────────────────────────────

    /** Report shapes — plain records so Jackson output is deterministic and ordered. */
    public record SweepReport(double sigma, List<Double> grid, List<Integer> seasons,
                              List<LambdaResult> perLambda, Double bestLambda) {}
    public record LambdaResult(double lambda, Metrics overall, List<SeasonMetrics> perSeason) {}
    public record SeasonMetrics(int year, long n, Double mae, Double logLoss, Double residSigma) {}
    public record Metrics(long n, Double mae, Double logLoss, Double residSigma) {}

    /** Synchronous sweep over every season that yields predictions. Package-visible for tests. */
    SweepReport runSweep() {
        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear).sorted().toList();

        // accumulate per (lambda, year)
        Map<Double, Map<Integer, Acc>> acc = new LinkedHashMap<>();
        for (double l : lambdaGrid) acc.put(l, new LinkedHashMap<>());

        List<Integer> sweptYears = new ArrayList<>();
        for (int year : years) {
            boolean any = sweepSeason(year, acc);
            if (any) sweptYears.add(year);
        }

        List<LambdaResult> perLambda = new ArrayList<>();
        Double bestLambda = null;
        Double bestLogLoss = null;
        for (double l : lambdaGrid) {
            List<SeasonMetrics> perSeason = new ArrayList<>();
            Acc pooled = new Acc();
            for (int year : sweptYears) {
                Acc a = acc.get(l).get(year);
                if (a == null || a.n == 0) continue;
                perSeason.add(new SeasonMetrics(year, a.n, a.mae(), a.logLoss(), a.residSigma()));
                pooled.merge(a);
            }
            Metrics overall = new Metrics(pooled.n, pooled.mae(), pooled.logLoss(), pooled.residSigma());
            perLambda.add(new LambdaResult(l, overall, perSeason));
            if (pooled.n > 0 && (bestLogLoss == null || pooled.logLoss() < bestLogLoss)) {
                bestLogLoss = pooled.logLoss();
                bestLambda = l;
            }
        }
        return new SweepReport(marginSigma, lambdaGrid, sweptYears, perLambda, bestLambda);
    }

    /** Replays one season; returns whether any predictions were recorded. */
    private boolean sweepSeason(int seasonYear, Map<Double, Map<Integer, Acc>> acc) {
        var dataOpt = seasonGameDataLoader.load(seasonYear);
        if (dataOpt.isEmpty()) return false;
        var data = dataOpt.get();
        Season season = data.season();
        List<Game> finalGames = data.finalGames();
        if (finalGames.isEmpty()) return false;

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

        int T    = teamIds.size();
        int size = 2 * T + 2;
        int MU   = 2 * T;
        int HCA  = 2 * T + 1;
        int TI   = T;

        double[][] A  = new double[size][size];
        double[]   b  = new double[size];
        double[][] At = new double[T + 1][T + 1];
        double[]   bt = new double[T + 1];
        Map<Long, Integer> fitGamesByTeam = new HashMap<>();

        // λ → current {efficiency, tempo} solutions fit through the prior date
        Map<Double, double[]> effSol   = new HashMap<>();
        Map<Double, double[]> tempoSol = new HashMap<>();

        long predicted = 0;
        for (Map.Entry<LocalDate, List<Game>> entry : data.gamesByDate().entrySet()) {
            List<Game> fitGames = new ArrayList<>();
            for (Game game : entry.getValue()) {
                Map<Long, TeamGameStats> gameStats = statsByGame.getOrDefault(game.getId(), Map.of());
                Double poss = AdjustedEfficiencyRatingService.possessions(
                        gameStats.get(game.getHomeTeam().getId()),
                        gameStats.get(game.getAwayTeam().getId()));
                if (poss == null || poss <= 0) continue;
                fitGames.add(game);

                // Predict BEFORE absorbing (leakage rule): prior-date solutions only,
                // and only when both teams already have at least one fit game
                if (fitGamesByTeam.getOrDefault(game.getHomeTeam().getId(), 0) > 0
                        && fitGamesByTeam.getOrDefault(game.getAwayTeam().getId(), 0) > 0) {
                    int hi = teamIndex.get(game.getHomeTeam().getId());
                    int ai = teamIndex.get(game.getAwayTeam().getId());
                    int ind = Boolean.TRUE.equals(game.getNeutralSite()) ? 0 : 1;
                    int actualMargin = game.getHomeScore() - game.getAwayScore();
                    for (double l : lambdaGrid) {
                        double[] es = effSol.get(l);
                        double[] ts = tempoSol.get(l);
                        if (es == null || ts == null) continue;
                        double expPoss = ts[TI] + ts[hi] + ts[ai];
                        double eh = es[MU] + es[hi] - es[T + ai] + ind * es[HCA];
                        double ea = es[MU] + es[ai] - es[T + hi] - ind * es[HCA];
                        double spread = (eh - ea) * expPoss / 100.0;
                        acc.get(l).computeIfAbsent(seasonYear, k -> new Acc())
                                .add(spread, actualMargin, marginSigma);
                        predicted++;
                    }
                }
            }

            // Absorb the date, then re-solve for every λ
            if (fitGames.isEmpty()) continue;
            for (Game game : fitGames) {
                Map<Long, TeamGameStats> gameStats = statsByGame.getOrDefault(game.getId(), Map.of());
                double poss = AdjustedEfficiencyRatingService.possessions(
                        gameStats.get(game.getHomeTeam().getId()),
                        gameStats.get(game.getAwayTeam().getId()));
                int hi = teamIndex.get(game.getHomeTeam().getId());
                int ai = teamIndex.get(game.getAwayTeam().getId());
                int ind = Boolean.TRUE.equals(game.getNeutralSite()) ? 0 : 1;
                AdjustedEfficiencyRatingService.addObservation(A, b, hi, T + ai, +ind,
                        100.0 * game.getHomeScore() / poss, MU, HCA);
                AdjustedEfficiencyRatingService.addObservation(A, b, ai, T + hi, -ind,
                        100.0 * game.getAwayScore() / poss, MU, HCA);
                AdjustedEfficiencyRatingService.addTempoObservation(At, bt, hi, ai, TI, poss);
                fitGamesByTeam.merge(game.getHomeTeam().getId(), 1, Integer::sum);
                fitGamesByTeam.merge(game.getAwayTeam().getId(), 1, Integer::sum);
            }
            for (double l : lambdaGrid) {
                double[] es = AdjustedEfficiencyRatingService.solve(A, b, 2 * T, size, l);
                double[] ts = AdjustedEfficiencyRatingService.solve(At, bt, T, T + 1, l);
                if (es != null && ts != null) {
                    effSol.put(l, es);
                    tempoSol.put(l, ts);
                }
            }
        }
        log.info("λ sweep season {}: {} (game × λ) predictions recorded", seasonYear, predicted);
        return predicted > 0;
    }

    /** Running sums for one (λ, season) cell; merged for the pooled per-λ aggregate. */
    static final class Acc {
        long n;
        double sumAbsErr, sumErr, sumSqErr, sumLogLoss;

        void add(double predictedSpread, int actualMargin, double sigma) {
            double err = actualMargin - predictedSpread;
            n++;
            sumAbsErr  += Math.abs(err);
            sumErr     += err;
            sumSqErr   += err * err;
            double p = clamp(WinProbability.fromMargin(predictedSpread, sigma));
            sumLogLoss += actualMargin > 0 ? -Math.log(p) : -Math.log(1 - p);
        }

        void merge(Acc other) {
            n += other.n;
            sumAbsErr  += other.sumAbsErr;
            sumErr     += other.sumErr;
            sumSqErr   += other.sumSqErr;
            sumLogLoss += other.sumLogLoss;
        }

        Double mae()     { return n == 0 ? null : sumAbsErr / n; }
        Double logLoss() { return n == 0 ? null : sumLogLoss / n; }

        /** Population stddev of the residuals (defined for n = 1, deterministic). */
        Double residSigma() {
            if (n == 0) return null;
            double mean = sumErr / n;
            return Math.sqrt(Math.max(0, sumSqErr / n - mean * mean));
        }

        private static double clamp(double p) {
            return Math.max(1e-6, Math.min(1 - 1e-6, p));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tuning report", e);
        }
    }
}
