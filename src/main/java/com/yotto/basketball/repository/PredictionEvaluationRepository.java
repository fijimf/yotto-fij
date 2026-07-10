package com.yotto.basketball.repository;

import com.yotto.basketball.entity.PredictionEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PredictionEvaluationRepository extends JpaRepository<PredictionEvaluation, Long> {

    List<PredictionEvaluation> findByGameId(Long gameId);

    /** One row per evaluated game in a season, with the ML row's model version (null if no ML row). */
    interface EvaluatedGame {
        Long getGameId();
        String getMlVersion();
    }

    @Query(nativeQuery = true, value = """
            SELECT pe.game_id AS gameid,
                   max(pe.model_version) FILTER (WHERE pe.model_type = 'ML') AS mlversion
            FROM prediction_evaluations pe
            WHERE pe.season_id = :seasonId
            GROUP BY pe.game_id
            """)
    List<EvaluatedGame> findEvaluatedGames(@Param("seasonId") Long seasonId);

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM prediction_evaluations WHERE season_id = :seasonId")
    int deleteBySeasonId(@Param("seasonId") Long seasonId);

    /** Season years that have at least one evaluation row, newest first. */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT s.year FROM prediction_evaluations pe
            JOIN seasons s ON s.id = pe.season_id
            ORDER BY s.year DESC
            """)
    List<Integer> findEvaluatedSeasonYears();

    // ── Aggregate metrics (per model, within a season, from a cutoff date) ─────

    interface SpreadMetrics {
        String getModelType();
        long getN();
        Double getMae();
        Double getRmse();
        Double getSideAccuracy();   // fraction of games where the predicted winner won
    }

    @Query(nativeQuery = true, value = """
            SELECT pe.model_type AS modeltype,
                   count(*) AS n,
                   avg(abs(pe.spread_error)) AS mae,
                   sqrt(avg(pe.spread_error * pe.spread_error)) AS rmse,
                   avg(CASE WHEN (pe.predicted_spread >= 0) = (pe.actual_margin > 0) THEN 1.0 ELSE 0.0 END) AS sideaccuracy
            FROM prediction_evaluations pe
            WHERE pe.season_id = :seasonId
              AND pe.game_date >= :fromDate
              AND pe.predicted_spread IS NOT NULL
            GROUP BY pe.model_type
            """)
    List<SpreadMetrics> spreadMetrics(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);

    interface TotalMetrics {
        String getModelType();
        long getN();
        Double getMae();
        Double getRmse();
        Double getBias();   // mean(actual − predicted): positive = model under-predicts totals
    }

    @Query(nativeQuery = true, value = """
            SELECT pe.model_type AS modeltype,
                   count(*) AS n,
                   avg(abs(pe.total_error)) AS mae,
                   sqrt(avg(pe.total_error * pe.total_error)) AS rmse,
                   avg(pe.total_error) AS bias
            FROM prediction_evaluations pe
            WHERE pe.season_id = :seasonId
              AND pe.game_date >= :fromDate
              AND pe.predicted_total IS NOT NULL
            GROUP BY pe.model_type
            """)
    List<TotalMetrics> totalMetrics(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);

    interface ProbMetrics {
        String getModelType();
        long getN();
        Double getBrier();
        Double getAccuracy();   // fraction where (prob ≥ 0.5) matched the outcome
    }

    @Query(nativeQuery = true, value = """
            SELECT pe.model_type AS modeltype,
                   count(*) AS n,
                   avg(power(pe.predicted_home_win_prob - (CASE WHEN pe.home_won THEN 1.0 ELSE 0.0 END), 2)) AS brier,
                   avg(CASE WHEN (pe.predicted_home_win_prob >= 0.5) = pe.home_won THEN 1.0 ELSE 0.0 END) AS accuracy
            FROM prediction_evaluations pe
            WHERE pe.season_id = :seasonId
              AND pe.game_date >= :fromDate
              AND pe.predicted_home_win_prob IS NOT NULL
            GROUP BY pe.model_type
            """)
    List<ProbMetrics> probMetrics(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);

    /** Calibration: predicted-probability deciles vs. actual home-win rate, per model. */
    interface CalibrationBucket {
        String getModelType();
        int getBucket();          // 0–9: floor(prob × 10), capped at 9
        long getN();
        Double getAvgPredicted();
        Double getActualRate();
    }

    @Query(nativeQuery = true, value = """
            SELECT pe.model_type AS modeltype,
                   least(floor(pe.predicted_home_win_prob * 10), 9)::int AS bucket,
                   count(*) AS n,
                   avg(pe.predicted_home_win_prob) AS avgpredicted,
                   avg(CASE WHEN pe.home_won THEN 1.0 ELSE 0.0 END) AS actualrate
            FROM prediction_evaluations pe
            WHERE pe.season_id = :seasonId
              AND pe.game_date >= :fromDate
              AND pe.predicted_home_win_prob IS NOT NULL
            GROUP BY pe.model_type, bucket
            ORDER BY pe.model_type, bucket
            """)
    List<CalibrationBucket> calibrationBuckets(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);
}
