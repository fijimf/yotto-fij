package com.yotto.basketball.controller;

import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.ConferenceNamingService;
import com.yotto.basketball.service.MasseyRatingService;
import com.yotto.basketball.service.MlModelRegistryService;
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

    /** Display metadata and ordering for the fixed model types; ML:&lt;slug&gt; is dynamic. */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            MasseyRatingService.MODEL_TYPE,                "Massey",
            MasseyRatingService.MODEL_TYPE_TOTALS,         "Massey Totals",
            BradleyTerryRatingService.MODEL_TYPE,          "Bradley-Terry",
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "Weighted Bradley-Terry",
            PredictionEvaluationService.MODEL_BOOK,        "Book Closing Line");

    private static final List<String> DISPLAY_ORDER = List.of(
            MasseyRatingService.MODEL_TYPE,
            MasseyRatingService.MODEL_TYPE_TOTALS,
            BradleyTerryRatingService.MODEL_TYPE,
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED,
            PredictionEvaluationService.MODEL_BOOK);

    /** Game segments: dropdown key → tournament_type values ('NONE' = regular season). */
    private static final Map<String, List<String>> SEGMENTS = Map.of(
            "regular",    List.of("NONE", "IN_SEASON_TOURNAMENT"),
            "conf",       List.of("CONFERENCE_TOURNAMENT"),
            "ncaa",       List.of("NCAA_TOURNAMENT"),
            "postseason", List.of("NIT", "CBI", "CROWN", "OTHER_POSTSEASON"));

    /** Sentinel seasonId meaning "all seasons" in the aggregate queries. */
    private static final long ALL_SEASONS = -1L;

    private final PredictionEvaluationRepository evaluationRepository;
    private final com.yotto.basketball.repository.SeasonRepository seasonRepository;
    private final com.yotto.basketball.repository.ConferenceRepository conferenceRepository;
    private final MlModelRegistryService mlModelRegistryService;
    private final ConferenceNamingService conferenceNamingService;

    public ModelPerformanceController(PredictionEvaluationRepository evaluationRepository,
                                      com.yotto.basketball.repository.SeasonRepository seasonRepository,
                                      com.yotto.basketball.repository.ConferenceRepository conferenceRepository,
                                      MlModelRegistryService mlModelRegistryService,
                                      ConferenceNamingService conferenceNamingService) {
        this.evaluationRepository = evaluationRepository;
        this.seasonRepository = seasonRepository;
        this.conferenceRepository = conferenceRepository;
        this.mlModelRegistryService = mlModelRegistryService;
        this.conferenceNamingService = conferenceNamingService;
    }

    @GetMapping("/predictions/performance")
    public String performance(@RequestParam(required = false) String year,
                              @RequestParam(defaultValue = "season") String window,
                              @RequestParam(defaultValue = "all") String segment,
                              @RequestParam(required = false) String cmodel,
                              Model model) {
        List<Integer> years = evaluationRepository.findEvaluatedSeasonYears();
        boolean allSeasons = "ALL".equalsIgnoreCase(year) && !years.isEmpty();
        Integer requestedYear = parseYear(year);
        Integer selectedYear = allSeasons ? null
                : (requestedYear != null && years.contains(requestedYear))
                        ? requestedYear
                        : (years.isEmpty() ? null : years.get(0));
        boolean last30 = "30".equals(window);
        String selectedSegment = SEGMENTS.containsKey(segment) ? segment : "all";

        model.addAttribute("years", years);
        model.addAttribute("selectedYear", selectedYear);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("window", last30 ? "30" : "season");
        model.addAttribute("segment", selectedSegment);
        model.addAttribute("currentPage", "predictions");

        boolean hasData = allSeasons || selectedYear != null;
        model.addAttribute("hasData", hasData);
        if (!hasData) {
            return "pages/model-performance";
        }

        Long seasonId = allSeasons ? ALL_SEASONS
                : seasonRepository.findByYear(selectedYear).orElseThrow().getId();
        LocalDate from = last30 ? LocalDate.now().minusDays(30) : LocalDate.of(1900, 1, 1);
        boolean allSegments = "all".equals(selectedSegment);
        List<String> types = SEGMENTS.getOrDefault(selectedSegment, List.of("NONE"));

        model.addAttribute("spreadRows",
                sortRows(evaluationRepository.spreadMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.SpreadMetrics::getModelType));
        model.addAttribute("totalRows",
                sortRows(evaluationRepository.totalMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.TotalMetrics::getModelType));
        model.addAttribute("probRows",
                sortRows(evaluationRepository.probMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.ProbMetrics::getModelType));
        model.addAttribute("monthly",
                evaluationRepository.monthlyMetrics(seasonId, allSegments, types).stream()
                        .map(m -> new MonthlyPoint(m.getModelType(), displayName(m.getModelType()), m.getMonth(),
                                m.getSpreadN(), m.getSpreadMae(), m.getProbN(), m.getBrier()))
                        .toList());
        model.addAttribute("calibration",
                evaluationRepository.calibrationBuckets(seasonId, from, allSegments, types).stream()
                        .map(b -> new CalibrationPoint(b.getModelType(), displayName(b.getModelType()),
                                b.getBucket(), b.getN(), b.getAvgPredicted(), b.getActualRate()))
                        .toList());
        String confModel = resolveConfModel(cmodel);
        model.addAttribute("confModel", confModel);
        model.addAttribute("confModelOptions", spreadModelOptions());
        model.addAttribute("conferenceRows",
                buildConferenceRows(seasonId, from, allSegments, types, confModel, allSeasons, selectedYear));
        model.addAttribute("displayNames", allDisplayNames());
        return "pages/model-performance";
    }

    private List<ConferenceRow> buildConferenceRows(Long seasonId, LocalDate from, boolean allSegments,
                                                    List<String> types, String confModel,
                                                    boolean allSeasons, Integer selectedYear) {
        var metrics = evaluationRepository.conferenceMetrics(seasonId, from, allSegments, types, confModel);
        var confById = conferenceRepository
                .findAllById(metrics.stream().map(PredictionEvaluationRepository.ConferenceMetrics::getConferenceId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        com.yotto.basketball.entity.Conference::getId, c -> c));
        var names = conferenceNamingService.load();
        return metrics.stream()
                .map(cm -> {
                    var conf = confById.get(cm.getConferenceId());
                    String name = conf == null ? "Unknown"
                            : allSeasons ? conf.getName() : names.name(conf, selectedYear);
                    return new ConferenceRow(name, cm.getN(), cm.getModelMae(), cm.getBookMae(),
                            cm.getModelMae() - cm.getBookMae(), cm.getSideAccuracy());
                })
                .sorted(Comparator.comparingDouble(ConferenceRow::delta))
                .toList();
    }

    /** Spread-capable models for the by-conference card: ML bundles first, then Massey. */
    private Map<String, String> spreadModelOptions() {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        mlModelRegistryService.plan().displayNames().forEach((slug, displayName) ->
                options.put(PredictionEvaluationService.ML_TYPE_PREFIX + slug, displayName + " (ML)"));
        options.put(MasseyRatingService.MODEL_TYPE, "Massey");
        return options;
    }

    private String resolveConfModel(String requested) {
        Map<String, String> options = spreadModelOptions();
        if (requested != null && options.containsKey(requested)) {
            return requested;
        }
        String defaultSlug = mlModelRegistryService.plan().defaultSlug();
        String defaultType = defaultSlug == null ? null
                : PredictionEvaluationService.ML_TYPE_PREFIX + defaultSlug;
        return (defaultType != null && options.containsKey(defaultType))
                ? defaultType
                : MasseyRatingService.MODEL_TYPE;
    }

    /** Per-conference paired comparison row; delta = model MAE − book MAE (negative = model better). */
    public record ConferenceRow(String name, long n, Double modelMae, Double bookMae,
                                double delta, Double sideAccuracy) {}

    private static Integer parseYear(String year) {
        if (year == null) return null;
        try {
            return Integer.valueOf(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Fixed model-type names plus a dynamic entry per ML bundle ('ML:slug' → registry name). */
    private Map<String, String> allDisplayNames() {
        Map<String, String> names = new java.util.LinkedHashMap<>(DISPLAY_NAMES);
        mlModelRegistryService.plan().displayNames().forEach((slug, displayName) ->
                names.put(PredictionEvaluationService.ML_TYPE_PREFIX + slug, displayName + " (ML)"));
        return names;
    }

    /** JSON-friendly calibration point for the Chart.js inline block. */
    public record CalibrationPoint(String modelType, String displayName, int bucket,
                                   long n, Double avgPredicted, Double actualRate) {}

    /** JSON-friendly month-by-month point for the Chart.js inline block; month is 'YYYY-MM'. */
    public record MonthlyPoint(String modelType, String displayName, String month,
                               long spreadN, Double spreadMae, long probN, Double brier) {}

    private <T> List<T> sortRows(List<T> rows, java.util.function.Function<T, String> typeOf) {
        return rows.stream()
                .sorted(Comparator.comparingInt(r -> orderOf(typeOf.apply(r))))
                .toList();
    }

    /** ML bundles sort first (alphabetically), then the fixed baselines, BOOK last. */
    private static int orderOf(String modelType) {
        if (modelType.startsWith(PredictionEvaluationService.ML_TYPE_PREFIX)) return -1;
        int idx = DISPLAY_ORDER.indexOf(modelType);
        return idx >= 0 ? idx : DISPLAY_ORDER.size();
    }

    private String displayName(String modelType) {
        return allDisplayNames().getOrDefault(modelType, modelType);
    }
}
