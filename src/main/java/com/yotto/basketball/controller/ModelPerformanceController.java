package com.yotto.basketball.controller;

import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import com.yotto.basketball.service.PredictionEvaluationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Public model-performance page: aggregate prediction accuracy per model (spread,
 * total, win probability) for a season, benchmarked against the book closing line.
 * Data comes from {@code prediction_evaluations} (see PredictionEvaluationService).
 */
@Controller
public class ModelPerformanceController {

    /** Display metadata and ordering for known model types. */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            PredictionEvaluationService.MODEL_ML,          "ML Model",
            MasseyRatingService.MODEL_TYPE,                "Massey",
            MasseyRatingService.MODEL_TYPE_TOTALS,         "Massey Totals",
            BradleyTerryRatingService.MODEL_TYPE,          "Bradley-Terry",
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "Weighted Bradley-Terry",
            PredictionEvaluationService.MODEL_BOOK,        "Book Closing Line");

    private static final List<String> DISPLAY_ORDER = List.of(
            PredictionEvaluationService.MODEL_ML,
            MasseyRatingService.MODEL_TYPE,
            MasseyRatingService.MODEL_TYPE_TOTALS,
            BradleyTerryRatingService.MODEL_TYPE,
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED,
            PredictionEvaluationService.MODEL_BOOK);

    private final PredictionEvaluationRepository evaluationRepository;
    private final com.yotto.basketball.repository.SeasonRepository seasonRepository;

    public ModelPerformanceController(PredictionEvaluationRepository evaluationRepository,
                                      com.yotto.basketball.repository.SeasonRepository seasonRepository) {
        this.evaluationRepository = evaluationRepository;
        this.seasonRepository = seasonRepository;
    }

    @GetMapping("/predictions/performance")
    public String performance(@RequestParam(required = false) Integer year,
                              @RequestParam(defaultValue = "season") String window,
                              Model model) {
        List<Integer> years = evaluationRepository.findEvaluatedSeasonYears();
        Integer selectedYear = (year != null && years.contains(year))
                ? year
                : (years.isEmpty() ? null : years.get(0));
        boolean last30 = "30".equals(window);

        model.addAttribute("years", years);
        model.addAttribute("selectedYear", selectedYear);
        model.addAttribute("window", last30 ? "30" : "season");
        model.addAttribute("currentPage", "predictions");

        if (selectedYear == null) {
            return "pages/model-performance";
        }

        Long seasonId = seasonRepository.findByYear(selectedYear).orElseThrow().getId();
        LocalDate from = last30 ? LocalDate.now().minusDays(30) : LocalDate.of(1900, 1, 1);

        model.addAttribute("spreadRows", sortRows(evaluationRepository.spreadMetrics(seasonId, from),
                PredictionEvaluationRepository.SpreadMetrics::getModelType));
        model.addAttribute("totalRows", sortRows(evaluationRepository.totalMetrics(seasonId, from),
                PredictionEvaluationRepository.TotalMetrics::getModelType));
        model.addAttribute("probRows", sortRows(evaluationRepository.probMetrics(seasonId, from),
                PredictionEvaluationRepository.ProbMetrics::getModelType));
        model.addAttribute("calibration", evaluationRepository.calibrationBuckets(seasonId, from).stream()
                .map(b -> new CalibrationPoint(b.getModelType(), displayName(b.getModelType()),
                        b.getBucket(), b.getN(), b.getAvgPredicted(), b.getActualRate()))
                .toList());
        model.addAttribute("displayNames", DISPLAY_NAMES);
        return "pages/model-performance";
    }

    /** JSON-friendly calibration point for the Chart.js inline block. */
    public record CalibrationPoint(String modelType, String displayName, int bucket,
                                   long n, Double avgPredicted, Double actualRate) {}

    private <T> List<T> sortRows(List<T> rows, java.util.function.Function<T, String> typeOf) {
        return rows.stream()
                .sorted(Comparator.comparingInt(r -> orderOf(typeOf.apply(r))))
                .toList();
    }

    private static int orderOf(String modelType) {
        int idx = DISPLAY_ORDER.indexOf(modelType);
        return idx >= 0 ? idx : DISPLAY_ORDER.size();
    }

    private static String displayName(String modelType) {
        return DISPLAY_NAMES.getOrDefault(modelType, modelType);
    }
}
