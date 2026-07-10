package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds and maintains {@code prediction_evaluations}: one row per FINAL game per model,
 * holding the model's pre-game prediction and its error vs. the actual result.
 *
 * <p>Predictions are recomputed from rating snapshots dated strictly before each game
 * (via {@link PredictionService#buildPrediction}), so evaluation is leakage-free and can
 * be run retroactively over full past seasons — including for a newly trained ML model.
 *
 * <p>Evaluation is incremental: games that already have rows are skipped, except that a
 * game whose ML row was produced by a different model version than the currently loaded
 * one is fully re-evaluated (all its rows are upserted). Writes go through a JDBC
 * {@code ON CONFLICT} upsert keyed on {@code (game_id, model_type)}.
 */
@Service
public class PredictionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PredictionEvaluationService.class);

    /** Composite ML model rows (spread + total + win prob), tagged with the model version. */
    public static final String MODEL_ML = "ML";
    /** Closing-line benchmark rows built from {@link BettingOdds}. */
    public static final String MODEL_BOOK = "BOOK";

    private static final String UPSERT_SQL = """
            INSERT INTO prediction_evaluations
                (game_id, season_id, model_type, game_date,
                 predicted_spread, predicted_total, predicted_home_win_prob,
                 actual_margin, actual_total, home_won,
                 spread_error, total_error, model_version, evaluated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (game_id, model_type) DO UPDATE SET
                predicted_spread        = EXCLUDED.predicted_spread,
                predicted_total         = EXCLUDED.predicted_total,
                predicted_home_win_prob = EXCLUDED.predicted_home_win_prob,
                actual_margin           = EXCLUDED.actual_margin,
                actual_total            = EXCLUDED.actual_total,
                home_won                = EXCLUDED.home_won,
                spread_error            = EXCLUDED.spread_error,
                total_error             = EXCLUDED.total_error,
                model_version           = EXCLUDED.model_version,
                evaluated_at            = EXCLUDED.evaluated_at
            """;

    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final PredictionEvaluationRepository evaluationRepository;
    private final PredictionService predictionService;
    private final MlPredictionService mlPredictionService;
    private final JdbcTemplate jdbcTemplate;

    public PredictionEvaluationService(GameRepository gameRepository,
                                       SeasonRepository seasonRepository,
                                       PredictionEvaluationRepository evaluationRepository,
                                       PredictionService predictionService,
                                       MlPredictionService mlPredictionService,
                                       JdbcTemplate jdbcTemplate) {
        this.gameRepository       = gameRepository;
        this.seasonRepository     = seasonRepository;
        this.evaluationRepository = evaluationRepository;
        this.predictionService    = predictionService;
        this.mlPredictionService  = mlPredictionService;
        this.jdbcTemplate         = jdbcTemplate;
    }

    /**
     * Incrementally evaluates all un-evaluated FINAL games of a season (plus games whose
     * ML rows are stale relative to the currently loaded model). Returns the number of
     * games evaluated in this run.
     */
    @Transactional
    public int evaluateSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Prediction evaluation skipped — season {} not found", seasonYear);
            return 0;
        }

        String currentMlVersion = mlPredictionService.isEnabled()
                ? mlPredictionService.getStatus().version() : null;

        Map<Long, String> evaluatedMlVersions = new HashMap<>();
        for (var eg : evaluationRepository.findEvaluatedGames(season.getId())) {
            evaluatedMlVersions.put(eg.getGameId(), eg.getMlVersion());
        }

        List<Game> games = gameRepository.findFinalGamesForEvaluation(seasonYear);
        List<Object[]> rows = new ArrayList<>();
        int evaluatedGames = 0;

        for (Game game : games) {
            boolean seen = evaluatedMlVersions.containsKey(game.getId());
            boolean mlStale = currentMlVersion != null
                    && !currentMlVersion.equals(evaluatedMlVersions.get(game.getId()));
            if (seen && !mlStale) {
                continue;
            }
            List<Object[]> gameRows = buildRows(game, season, currentMlVersion);
            if (!gameRows.isEmpty()) {
                rows.addAll(gameRows);
                evaluatedGames++;
            }
        }

        if (!rows.isEmpty()) {
            int[] argTypes = {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.DATE,
                              Types.DOUBLE, Types.DOUBLE, Types.DOUBLE,
                              Types.INTEGER, Types.INTEGER, Types.BOOLEAN,
                              Types.DOUBLE, Types.DOUBLE, Types.VARCHAR, Types.TIMESTAMP};
            jdbcTemplate.batchUpdate(UPSERT_SQL, rows, argTypes);
        }
        log.info("Prediction evaluation for season {}: {} games evaluated ({} rows), {} already current",
                seasonYear, evaluatedGames, rows.size(), games.size() - evaluatedGames);
        return evaluatedGames;
    }

    /** Deletes all evaluation rows for a season and re-evaluates it from scratch. */
    @Transactional
    public int rebuildSeason(int seasonYear) {
        seasonRepository.findByYear(seasonYear).ifPresent(season -> {
            int deleted = evaluationRepository.deleteBySeasonId(season.getId());
            log.info("Prediction evaluation rebuild for season {}: {} rows deleted", seasonYear, deleted);
        });
        return evaluateSeason(seasonYear);
    }

    // ── Row construction ──────────────────────────────────────────────────────

    /**
     * Builds one upsert row per model with a usable prediction for this game.
     * Returns an empty list when no model (and no book line) could predict it.
     */
    private List<Object[]> buildRows(Game game, Season season, String currentMlVersion) {
        PredictionResult result = predictionService.buildPrediction(game);
        if (result.actualMargin() == null) {
            return List.of();
        }
        int actualMargin = result.actualMargin();
        int actualTotal  = result.actualTotal();
        boolean homeWon  = actualMargin > 0;

        List<Object[]> rows = new ArrayList<>();

        if (result.massey() != null) {
            rows.add(row(game, season, MasseyRatingService.MODEL_TYPE,
                    result.massey().spread(), null, null,
                    actualMargin, actualTotal, homeWon, null));
        }
        if (result.masseyTotal() != null) {
            rows.add(row(game, season, MasseyRatingService.MODEL_TYPE_TOTALS,
                    null, result.masseyTotal().total(), null,
                    actualMargin, actualTotal, homeWon, null));
        }
        if (result.bradleyTerry() != null) {
            rows.add(row(game, season, BradleyTerryRatingService.MODEL_TYPE,
                    null, null, result.bradleyTerry().homeWinProbability(),
                    actualMargin, actualTotal, homeWon, null));
        }
        if (result.bradleyTerryWeighted() != null) {
            rows.add(row(game, season, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED,
                    null, null, result.bradleyTerryWeighted().homeWinProbability(),
                    actualMargin, actualTotal, homeWon, null));
        }
        if (result.ml() != null) {
            rows.add(row(game, season, MODEL_ML,
                    result.ml().spread(), result.ml().total(), result.ml().homeWinProbability(),
                    actualMargin, actualTotal, homeWon,
                    Objects.requireNonNullElse(result.ml().modelVersion(), currentMlVersion)));
        }

        Object[] bookRow = bookRow(game, season, actualMargin, actualTotal, homeWon);
        if (bookRow != null) {
            rows.add(bookRow);
        }
        return rows;
    }

    /**
     * Closing-line benchmark. {@code betting_odds.spread} is in handicap orientation
     * (negative = home favored), so the book's expected home margin is {@code −spread}.
     * The win probability is implied by the de-vigged moneyline pair.
     */
    private Object[] bookRow(Game game, Season season,
                             int actualMargin, int actualTotal, boolean homeWon) {
        BettingOdds odds = game.getBettingOdds();
        if (odds == null) return null;

        Double spread = odds.getSpread()    != null ? -odds.getSpread().doubleValue()   : null;
        Double total  = odds.getOverUnder() != null ? odds.getOverUnder().doubleValue() : null;
        Double prob   = impliedHomeWinProb(odds.getHomeMoneyline(), odds.getAwayMoneyline());
        if (spread == null && total == null && prob == null) return null;

        return row(game, season, MODEL_BOOK, spread, total, prob,
                actualMargin, actualTotal, homeWon, null);
    }

    private static Object[] row(Game game, Season season, String modelType,
                                Double predictedSpread, Double predictedTotal, Double predictedProb,
                                int actualMargin, int actualTotal, boolean homeWon,
                                String modelVersion) {
        return new Object[]{
                game.getId(), season.getId(), modelType,
                Date.valueOf(game.getGameDate().toLocalDate()),
                predictedSpread, predictedTotal, predictedProb,
                actualMargin, actualTotal, homeWon,
                predictedSpread != null ? actualMargin - predictedSpread : null,
                predictedTotal  != null ? actualTotal  - predictedTotal  : null,
                modelVersion,
                Timestamp.valueOf(LocalDateTime.now())
        };
    }

    /**
     * De-vigged win probability implied by an American moneyline pair, or null when
     * either line is missing. Each line maps to an implied probability (with vig);
     * normalising the pair to sum to 1 removes the vig.
     */
    static Double impliedHomeWinProb(Integer homeMoneyline, Integer awayMoneyline) {
        if (homeMoneyline == null || awayMoneyline == null) return null;
        double ih = impliedProb(homeMoneyline);
        double ia = impliedProb(awayMoneyline);
        if (ih + ia <= 0) return null;
        return ih / (ih + ia);
    }

    private static double impliedProb(int moneyline) {
        return moneyline < 0
                ? -moneyline / (double) (-moneyline + 100)
                : 100.0 / (moneyline + 100);
    }
}
