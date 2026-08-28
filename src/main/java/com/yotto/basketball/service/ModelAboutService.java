package com.yotto.basketball.service;

import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository.ProbMetrics;
import com.yotto.basketball.repository.PredictionEvaluationRepository.SpreadMetrics;
import com.yotto.basketball.repository.PredictionEvaluationRepository.TotalMetrics;
import com.yotto.basketball.repository.PredictionEvaluationRepository.VsBookMetrics;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.PublicModelService.PublicModel;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a model hub About page (/models/{slug}, spec §7.2): identity +
 * grouped feature list from the bundle manifest, honest evaluation aggregates
 * from prediction_evaluations (model row + BOOK benchmark, season/segment
 * scoped), calibration, walk-forward report, and the in-sample caveat.
 */
@Service
public class ModelAboutService {

    /**
     * Game segments: dropdown key → tournament_type values ('NONE' = regular
     * season). Also referenced by ModelPerformanceController so the two pages
     * can never drift.
     */
    public static final Map<String, List<String>> SEGMENTS = Map.of(
            "regular",    List.of("NONE", "IN_SEASON_TOURNAMENT"),
            "conf",       List.of("CONFERENCE_TOURNAMENT"),
            "ncaa",       List.of("NCAA_TOURNAMENT"),
            "postseason", List.of("NIT", "CBI", "CROWN", "OTHER_POSTSEASON"));

    /** Sentinel seasonId meaning "all seasons" in the aggregate queries. */
    public static final long ALL_SEASONS = -1L;

    private static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    /** Ordered feature-grouping rules; first substring match wins. */
    private static final List<Map.Entry<String, List<String>>> FEATURE_GROUP_RULES = List.of(
            Map.entry("Adjusted efficiency", List.of("adj_")),
            Map.entry("Preseason priors", List.of("prev_")),
            Map.entry("Form & schedule", List.of("_l5", "_l10", "days_rest", "season_week",
                    "games_played", "massey_resid", "is_conference_game", "is_neutral_site")),
            Map.entry("Ratings", List.of("massey", "bt_", "rpi", "stddev_margin")),
            Map.entry("Box-score profile", List.of("efg", "tov_rate", "orb_pct", "drb_pct",
                    "ft_rate", "fg3_rate", "pace", "off_eff", "def_eff")));

    private final PublicModelService publicModelService;
    private final MlPredictionService mlPredictionService;
    private final MlModelRegistryService registryService;
    private final PredictionEvaluationRepository evaluationRepository;
    private final SeasonRepository seasonRepository;

    public ModelAboutService(PublicModelService publicModelService,
                             MlPredictionService mlPredictionService,
                             MlModelRegistryService registryService,
                             PredictionEvaluationRepository evaluationRepository,
                             SeasonRepository seasonRepository) {
        this.publicModelService = publicModelService;
        this.mlPredictionService = mlPredictionService;
        this.registryService = registryService;
        this.evaluationRepository = evaluationRepository;
        this.seasonRepository = seasonRepository;
    }

    public record FeatureGroup(String name, List<String> features) {}

    public record AboutPage(PublicModel model,
                            MlBundleStatus bundle,
                            List<FeatureGroup> featureGroups,
                            List<Integer> years,
                            Integer selectedYear,
                            boolean allSeasons,
                            String segment,
                            SpreadMetrics spread, SpreadMetrics bookSpread,
                            TotalMetrics total, TotalMetrics bookTotal,
                            ProbMetrics prob, ProbMetrics bookProb,
                            VsBookMetrics vsBook,
                            List<ModelComparePageService.CalibrationPoint> calibration,
                            String inSampleNote) {

        public boolean hasEvalData() {
            return spread != null || prob != null || total != null;
        }

        /** Bundle trained-at as an Eastern-zone date label (templates can't do zone math). */
        public String trainedAtLabel() {
            if (bundle == null || bundle.trainedAt() == null) return "—";
            return bundle.trainedAt().atZone(java.time.ZoneId.of("America/New_York"))
                    .toLocalDate().toString();
        }
    }

    @org.springframework.cache.annotation.Cacheable(
            value = com.yotto.basketball.config.CacheConfig.MODEL_ABOUT,
            key = "#slug + ':' + #yearParam + ':' + #segmentParam")
    @Transactional(readOnly = true)
    public AboutPage build(String slug, String yearParam, String segmentParam) {
        PublicModel model = publicModelService.find(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown model: " + slug));

        MlBundleStatus bundle = model.ml() ? bundleFor(slug) : null;
        List<FeatureGroup> featureGroups = model.ml() ? groupFeatures(slug) : List.of();

        List<Integer> years = evaluationRepository.findEvaluatedSeasonYears();

        // Default view: the held-out test season when known (honest numbers);
        // otherwise all seasons (always honest for the classical model)
        boolean allSeasons;
        Integer selectedYear;
        if (yearParam == null) {
            Integer testSeason = bundle != null ? bundle.testSeason() : null;
            if (testSeason != null && years.contains(testSeason)) {
                selectedYear = testSeason;
                allSeasons = false;
            } else {
                selectedYear = null;
                allSeasons = !years.isEmpty();
            }
        } else if ("ALL".equalsIgnoreCase(yearParam)) {
            selectedYear = null;
            allSeasons = !years.isEmpty();
        } else {
            Integer parsed = parseYear(yearParam);
            selectedYear = parsed != null && years.contains(parsed) ? parsed
                    : (years.isEmpty() ? null : years.get(0));
            allSeasons = false;
        }

        String segment = SEGMENTS.containsKey(segmentParam) ? segmentParam : "all";

        if (selectedYear == null && !allSeasons) {
            return new AboutPage(model, bundle, featureGroups, years, null, false, segment,
                    null, null, null, null, null, null, null, List.of(), null);
        }

        Long seasonId = allSeasons ? ALL_SEASONS
                : seasonRepository.findByYear(selectedYear)
                        .orElseThrow(() -> new EntityNotFoundException("Season not found: " + selectedYear))
                        .getId();
        boolean allSegments = "all".equals(segment);
        List<String> types = SEGMENTS.getOrDefault(segment, List.of("NONE"));
        String modelType = model.modelType();

        SpreadMetrics spread = null, bookSpread = null;
        for (SpreadMetrics m : evaluationRepository.spreadMetrics(seasonId, BEGINNING, allSegments, types)) {
            if (modelType.equals(m.getModelType())) spread = m;
            if (PredictionEvaluationService.MODEL_BOOK.equals(m.getModelType())) bookSpread = m;
        }
        TotalMetrics total = null, bookTotal = null;
        for (TotalMetrics m : evaluationRepository.totalMetrics(seasonId, BEGINNING, allSegments, types)) {
            if (modelType.equals(m.getModelType())) total = m;
            if (PredictionEvaluationService.MODEL_BOOK.equals(m.getModelType())) bookTotal = m;
        }
        ProbMetrics prob = null, bookProb = null;
        for (ProbMetrics m : evaluationRepository.probMetrics(seasonId, BEGINNING, allSegments, types)) {
            if (modelType.equals(m.getModelType())) prob = m;
            if (PredictionEvaluationService.MODEL_BOOK.equals(m.getModelType())) bookProb = m;
        }
        VsBookMetrics vsBook = evaluationRepository.vsBookMetrics(seasonId, BEGINNING, allSegments, types).stream()
                .filter(m -> modelType.equals(m.getModelType()))
                .filter(m -> m.getSpreadN() > 0 || m.getOuN() > 0)
                .findFirst().orElse(null);

        List<ModelComparePageService.CalibrationPoint> calibration =
                evaluationRepository.calibrationBuckets(seasonId, BEGINNING, allSegments, types).stream()
                        .filter(b -> modelType.equals(b.getModelType())
                                || PredictionEvaluationService.MODEL_BOOK.equals(b.getModelType()))
                        .map(b -> new ModelComparePageService.CalibrationPoint(
                                b.getModelType(),
                                PredictionEvaluationService.MODEL_BOOK.equals(b.getModelType())
                                        ? "Book Closing Line" : model.displayName(),
                                b.getBucket(), b.getN(), b.getAvgPredicted(), b.getActualRate()))
                        .toList();

        return new AboutPage(model, bundle, featureGroups, years, selectedYear, allSeasons, segment,
                spread, bookSpread, total, bookTotal, prob, bookProb, vsBook, calibration,
                inSampleNote(model, bundle, allSeasons, selectedYear));
    }

    /** All-seasons/all-segments headline per model type for the /models index cards. */
    public record Headline(Double spreadMae, Double logLoss, Double bookSpreadMae, Double bookLogLoss) {}

    @org.springframework.cache.annotation.Cacheable(com.yotto.basketball.config.CacheConfig.MODEL_HEADLINES)
    @Transactional(readOnly = true)
    public Map<String, Headline> headlines() {
        Double bookMae = null, bookLl = null;
        Map<String, Double> maeByType = new LinkedHashMap<>();
        for (SpreadMetrics m : evaluationRepository.spreadMetrics(ALL_SEASONS, BEGINNING, true, List.of("NONE"))) {
            if (PredictionEvaluationService.MODEL_BOOK.equals(m.getModelType())) bookMae = m.getMae();
            else maeByType.put(m.getModelType(), m.getMae());
        }
        Map<String, Double> llByType = new LinkedHashMap<>();
        for (ProbMetrics m : evaluationRepository.probMetrics(ALL_SEASONS, BEGINNING, true, List.of("NONE"))) {
            if (PredictionEvaluationService.MODEL_BOOK.equals(m.getModelType())) bookLl = m.getLogLoss();
            else llByType.put(m.getModelType(), m.getLogLoss());
        }
        Map<String, Headline> result = new LinkedHashMap<>();
        for (String type : maeByType.keySet()) {
            result.put(type, new Headline(maeByType.get(type), llByType.get(type), bookMae, bookLl));
        }
        for (String type : llByType.keySet()) {
            result.putIfAbsent(type, new Headline(null, llByType.get(type), bookMae, bookLl));
        }
        return result;
    }

    private MlBundleStatus bundleFor(String slug) {
        return mlPredictionService.getStatuses().stream()
                .filter(s -> s.slug().equals(slug))
                .findFirst().orElse(null);
    }

    private String inSampleNote(PublicModel model, MlBundleStatus bundle,
                                boolean allSeasons, Integer selectedYear) {
        if (!model.ml()) return null;
        var trainSeasons = registryService.trainedSeasonsBySlug().get(model.slug());
        if (trainSeasons == null || trainSeasons.isEmpty()) return null;
        if (allSeasons) {
            String yrs = trainSeasons.stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(", "));
            return "This view mixes in-sample seasons (" + yrs + " — the model trained on those games) "
                    + "with held-out ones. The in-sample rows flatter the model"
                    + (bundle != null && bundle.testSeason() != null
                        ? "; the honest read is the held-out " + bundle.testSeason() + " season." : ".");
        }
        if (selectedYear != null && trainSeasons.contains(selectedYear)) {
            return "The model was trained on " + selectedYear + " games — these numbers are "
                    + "in-sample and optimistic. Judge it on seasons it was not trained on.";
        }
        return null;
    }

    /** Groups a bundle's ordered feature list for humans; first matching rule wins. */
    static List<FeatureGroup> groupFeatures(List<String> featureNames) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> rule : FEATURE_GROUP_RULES) {
            grouped.put(rule.getKey(), new ArrayList<>());
        }
        grouped.put("Other", new ArrayList<>());
        for (String feature : featureNames) {
            grouped.get(groupOf(feature)).add(feature);
        }
        return grouped.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> new FeatureGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    private List<FeatureGroup> groupFeatures(String slug) {
        List<String> names = mlPredictionService.featureNames(slug);
        return names == null ? List.of() : groupFeatures(names);
    }

    private static String groupOf(String feature) {
        for (Map.Entry<String, List<String>> rule : FEATURE_GROUP_RULES) {
            for (String needle : rule.getValue()) {
                if (feature.contains(needle)) return rule.getKey();
            }
        }
        return "Other";
    }

    private static Integer parseYear(String year) {
        try {
            return Integer.valueOf(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
