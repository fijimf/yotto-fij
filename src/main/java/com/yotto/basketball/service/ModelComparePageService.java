package com.yotto.basketball.service;

import com.yotto.basketball.config.CacheConfig;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the full /models/compare view model (extracted from the controller so
 * the seven aggregate queries per request can be cached — load testing measured
 * ~1-3s of app CPU per uncached render). The returned map's keys are the
 * template's model attribute names.
 *
 * <p>Cache keys are the RAW request params, so junk params create junk entries;
 * that's bounded by the cache's maximumSize and irrelevant in practice. Entries
 * are evicted after scrapes/evaluations/ML reloads (PageCacheEvictionService);
 * the last-30-days window drifting within the TTL backstop is immaterial.
 */
@Service
public class ModelComparePageService {

    /** Display metadata and ordering for the fixed model types; ML:&lt;slug&gt; is dynamic. */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            MasseyRatingService.MODEL_TYPE,                       "Massey",
            MasseyRatingService.MODEL_TYPE_TOTALS,                "Massey Totals",
            BradleyTerryRatingService.MODEL_TYPE,                 "Bradley-Terry",
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED,        "Weighted Bradley-Terry",
            AdjustedEfficiencyRatingService.MODEL_TYPE_PREDICTION, "Adjusted Efficiency",
            PredictionEvaluationService.MODEL_BOOK,               "Book Closing Line");

    private static final List<String> DISPLAY_ORDER = List.of(
            MasseyRatingService.MODEL_TYPE,
            MasseyRatingService.MODEL_TYPE_TOTALS,
            BradleyTerryRatingService.MODEL_TYPE,
            BradleyTerryRatingService.MODEL_TYPE_WEIGHTED,
            AdjustedEfficiencyRatingService.MODEL_TYPE_PREDICTION,
            PredictionEvaluationService.MODEL_BOOK);

    /** Game segments — shared with the model About pages so the two can never drift. */
    private static final Map<String, List<String>> SEGMENTS = ModelAboutService.SEGMENTS;

    /** Sentinel seasonId meaning "all seasons" in the aggregate queries. */
    private static final long ALL_SEASONS = -1L;

    private final PredictionEvaluationRepository evaluationRepository;
    private final com.yotto.basketball.repository.SeasonRepository seasonRepository;
    private final com.yotto.basketball.repository.ConferenceRepository conferenceRepository;
    private final MlModelRegistryService mlModelRegistryService;
    private final ConferenceNamingService conferenceNamingService;

    public ModelComparePageService(PredictionEvaluationRepository evaluationRepository,
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

    @Cacheable(value = CacheConfig.MODEL_COMPARE,
               key = "#year + ':' + #window + ':' + #segment + ':' + #cmodel")
    @Transactional(readOnly = true)
    public Map<String, Object> build(String year, String window, String segment, String cmodel) {
        Map<String, Object> model = new LinkedHashMap<>();

        List<Integer> years = evaluationRepository.findEvaluatedSeasonYears();
        boolean allSeasons = "ALL".equalsIgnoreCase(year) && !years.isEmpty();
        Integer requestedYear = parseYear(year);
        Integer selectedYear = allSeasons ? null
                : (requestedYear != null && years.contains(requestedYear))
                        ? requestedYear
                        : (years.isEmpty() ? null : years.get(0));
        boolean last30 = "30".equals(window);
        String selectedSegment = SEGMENTS.containsKey(segment) ? segment : "all";

        model.put("years", years);
        model.put("selectedYear", selectedYear);
        model.put("allSeasons", allSeasons);
        model.put("window", last30 ? "30" : "season");
        model.put("segment", selectedSegment);

        boolean hasData = allSeasons || selectedYear != null;
        model.put("hasData", hasData);
        if (!hasData) {
            return model;
        }

        Long seasonId = allSeasons ? ALL_SEASONS
                : seasonRepository.findByYear(selectedYear).orElseThrow().getId();
        LocalDate from = last30 ? LocalDate.now().minusDays(30) : LocalDate.of(1900, 1, 1);
        boolean allSegments = "all".equals(selectedSegment);
        List<String> types = SEGMENTS.getOrDefault(selectedSegment, List.of("NONE"));

        model.put("spreadRows",
                sortRows(evaluationRepository.spreadMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.SpreadMetrics::getModelType));
        model.put("totalRows",
                sortRows(evaluationRepository.totalMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.TotalMetrics::getModelType));
        model.put("probRows",
                sortRows(evaluationRepository.probMetrics(seasonId, from, allSegments, types),
                        PredictionEvaluationRepository.ProbMetrics::getModelType));
        model.put("monthly",
                evaluationRepository.monthlyMetrics(seasonId, allSegments, types).stream()
                        .map(m -> new MonthlyPoint(m.getModelType(), displayName(m.getModelType()), m.getMonth(),
                                m.getSpreadN(), m.getSpreadMae(), m.getProbN(), m.getBrier(), m.getLogLoss()))
                        .toList());
        model.put("calibration",
                evaluationRepository.calibrationBuckets(seasonId, from, allSegments, types).stream()
                        .map(b -> new CalibrationPoint(b.getModelType(), displayName(b.getModelType()),
                                b.getBucket(), b.getN(), b.getAvgPredicted(), b.getActualRate()))
                        .toList());
        String confModel = resolveConfModel(cmodel);
        model.put("confModel", confModel);
        model.put("confModelOptions", spreadModelOptions());
        model.put("conferenceRows",
                buildConferenceRows(seasonId, from, allSegments, types, confModel, allSeasons, selectedYear));
        model.put("displayNames", allDisplayNames());
        model.put("inSampleBadges", buildInSampleBadges(allSeasons, selectedYear));
        model.put("vsBookRows", buildVsBookRows(seasonId, from, allSegments, types));
        return model;
    }

    /** Same-game model-vs-book rows; models with no spread or total overlap are dropped. */
    private List<VsBookRow> buildVsBookRows(Long seasonId, LocalDate from, boolean allSegments,
                                            List<String> types) {
        return sortRows(evaluationRepository.vsBookMetrics(seasonId, from, allSegments, types),
                PredictionEvaluationRepository.VsBookMetrics::getModelType).stream()
                .filter(r -> r.getSpreadN() > 0 || r.getOuN() > 0)
                .map(r -> new VsBookRow(r.getModelType(), r.getSpreadN(),
                        r.getModelMae(), r.getBookMae(),
                        (r.getModelMae() != null && r.getBookMae() != null)
                                ? r.getModelMae() - r.getBookMae() : null,
                        r.getAtsN(), r.getAtsRate(), r.getOuN(), r.getOuRate(),
                        r.getClvN(), r.getClvRate()))
                .toList();
    }

    /**
     * Same-game comparison row; delta = model MAE − book MAE on identical games
     * (negative = model closer). Rates are null when no games qualified.
     */
    public record VsBookRow(String modelType, long spreadN, Double modelMae, Double bookMae,
                            Double delta, long atsN, Double atsRate, long ouN, Double ouRate,
                            long clvN, Double clvRate) {}

    /**
     * modelType → badge tooltip for ML models whose training data overlaps the current
     * view: rows for a trained-on season are in-sample and overstate accuracy. Empty
     * when the selected season is genuinely out-of-sample for every model.
     */
    private Map<String, String> buildInSampleBadges(boolean allSeasons, Integer selectedYear) {
        Map<String, String> badges = new java.util.LinkedHashMap<>();
        mlModelRegistryService.trainedSeasonsBySlug().forEach((slug, seasons) -> {
            String modelType = PredictionEvaluationService.ML_TYPE_PREFIX + slug;
            if (allSeasons) {
                String years = seasons.stream().sorted().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                badges.put(modelType, "Trained on " + years + ". Rows from those seasons are "
                        + "in-sample: the model saw these games during training, so aggregate "
                        + "numbers here flatter it.");
            } else if (selectedYear != null && seasons.contains(selectedYear)) {
                badges.put(modelType, "This model was trained on " + selectedYear + " games — "
                        + "these numbers are in-sample and optimistic. Judge it on seasons it "
                        + "was not trained on.");
            }
        });
        return badges;
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

    /** Spread-capable models for the by-conference card: ML bundles first, then the classical spreads. */
    private Map<String, String> spreadModelOptions() {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        mlModelRegistryService.plan().displayNames().forEach((slug, displayName) ->
                options.put(PredictionEvaluationService.ML_TYPE_PREFIX + slug, displayName + " (ML)"));
        options.put(MasseyRatingService.MODEL_TYPE, "Massey");
        options.put(AdjustedEfficiencyRatingService.MODEL_TYPE_PREDICTION, "Adjusted Efficiency");
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
                               long spreadN, Double spreadMae, long probN, Double brier, Double logLoss) {}

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
