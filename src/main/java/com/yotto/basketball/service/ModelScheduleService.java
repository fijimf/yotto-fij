package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.service.PublicModelService.PublicModel;
import com.yotto.basketball.util.EasternDates;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One model's day-by-day schedule (/models/{slug}/schedule, spec §7.3).
 * Past days render the leakage-free {@code prediction_evaluations} rows
 * (predicted vs actual, right/wrong, day summary); days without evaluations
 * fall back to live predictions for the scheduled games.
 */
@Service
public class ModelScheduleService {

    private final PublicModelService publicModelService;
    private final PredictionEvaluationRepository evaluationRepository;
    private final GameRepository gameRepository;
    private final PredictionService predictionService;
    private final Clock clock;

    public ModelScheduleService(PublicModelService publicModelService,
                                PredictionEvaluationRepository evaluationRepository,
                                GameRepository gameRepository,
                                PredictionService predictionService,
                                Clock clock) {
        this.publicModelService = publicModelService;
        this.evaluationRepository = evaluationRepository;
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
        this.clock = clock;
    }

    /** One game row; score fields are null for non-final games. */
    public record GameRow(long gameId,
                          long homeTeamId, String homeName, String homeLogo,
                          long awayTeamId, String awayName, String awayLogo,
                          Double predictedSpread, Double predictedTotal, Double homeWinProb,
                          Integer homeScore, Integer awayScore,
                          Boolean pickCorrect) {

        public String spreadLabel() {
            return predictedSpread == null ? "—"
                    : String.format(Locale.US, "%+.1f", predictedSpread);
        }

        public String totalLabel() {
            return predictedTotal == null ? "—"
                    : String.format(Locale.US, "%.1f", predictedTotal);
        }

        public String probLabel() {
            return homeWinProb == null ? "—"
                    : String.format(Locale.US, "%.0f%%", homeWinProb * 100);
        }
    }

    /** The day-summary strip over evaluated (final) games. */
    public record DaySummary(int games, int correct, Double spreadMae) {}

    public record SchedulePage(PublicModel model,
                               List<PublicModel> allModels,
                               LocalDate date, LocalDate prevDate, LocalDate nextDate,
                               boolean evaluated,
                               List<GameRow> rows,
                               DaySummary summary) {

        public boolean hasGames() { return !rows.isEmpty(); }
    }

    @Transactional(readOnly = true)
    public SchedulePage build(String slug, LocalDate requestedDate) {
        PublicModel model = publicModelService.find(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown model: " + slug));

        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(clock);

        // Past mode: the model's leakage-free pre-game evaluations for the day
        List<PredictionEvaluation> evals = evaluationRepository.findByModelAndDate(model.modelType(), date);
        List<GameRow> rows;
        boolean evaluated = !evals.isEmpty();
        if (evaluated) {
            rows = evals.stream().map(ModelScheduleService::fromEvaluation).toList();
        } else {
            rows = liveRows(model, date);
        }

        return new SchedulePage(model, publicModelService.list(),
                date, date.minusDays(1), date.plusDays(1),
                evaluated, rows, evaluated ? summarize(evals) : null);
    }

    private static GameRow fromEvaluation(PredictionEvaluation e) {
        Game g = e.getGame();
        Boolean pickCorrect = null;
        if (e.getPredictedSpread() != null && e.getHomeWon() != null) {
            pickCorrect = (e.getPredictedSpread() > 0) == e.getHomeWon();
        }
        return new GameRow(g.getId(),
                g.getHomeTeam().getId(), g.getHomeTeam().getName(), g.getHomeTeam().getLogoUrl(),
                g.getAwayTeam().getId(), g.getAwayTeam().getName(), g.getAwayTeam().getLogoUrl(),
                e.getPredictedSpread(), e.getPredictedTotal(), e.getPredictedHomeWinProb(),
                g.getHomeScore(), g.getAwayScore(),
                pickCorrect);
    }

    /** Live predictions for the day's non-final games (no evaluation rows yet). */
    private List<GameRow> liveRows(PublicModel model, LocalDate date) {
        LocalDateTime[] window = EasternDates.rangeWindowUtc(date, date);
        List<GameRow> rows = new ArrayList<>();
        for (Game game : gameRepository.findInUtcWindow(window[0], window[1])) {
            if (game.getStatus() == Game.GameStatus.CANCELLED
                    || game.getStatus() == Game.GameStatus.POSTPONED) {
                continue;
            }
            PredictionResult result = predictionService.buildPrediction(game);
            Double spread = null, total = null, prob = null;
            if (model.ml()) {
                PredictionResult.MlPrediction ml = result.mlModels().get(model.slug());
                if (ml != null) {
                    spread = ml.spread();
                    total = ml.total();
                    prob = ml.homeWinProbability();
                }
            } else if (result.adjEfficiency() != null) {
                spread = result.adjEfficiency().spread();
                total = result.adjEfficiency().total();
                prob = result.adjEfficiency().homeWinProbability();
            }
            rows.add(new GameRow(game.getId(),
                    game.getHomeTeam().getId(), game.getHomeTeam().getName(), game.getHomeTeam().getLogoUrl(),
                    game.getAwayTeam().getId(), game.getAwayTeam().getName(), game.getAwayTeam().getLogoUrl(),
                    spread, total, prob,
                    game.getStatus() == Game.GameStatus.FINAL ? game.getHomeScore() : null,
                    game.getStatus() == Game.GameStatus.FINAL ? game.getAwayScore() : null,
                    null));
        }
        rows.sort(Comparator.comparingLong(GameRow::gameId));
        return rows;
    }

    private static DaySummary summarize(List<PredictionEvaluation> evals) {
        int correct = 0, decided = 0;
        double absErr = 0;
        int errN = 0;
        for (PredictionEvaluation e : evals) {
            if (e.getPredictedSpread() != null && e.getHomeWon() != null) {
                decided++;
                if ((e.getPredictedSpread() > 0) == e.getHomeWon()) correct++;
            }
            if (e.getSpreadError() != null) {
                absErr += Math.abs(e.getSpreadError());
                errN++;
            }
        }
        return new DaySummary(decided, correct, errN > 0 ? absErr / errN : null);
    }
}
