package com.yotto.basketball.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.MlModel;
import com.yotto.basketball.repository.MlModelRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB-backed serving decisions for ML model bundles: which are ACTIVE (publicly served),
 * CANDIDATE (shadow-evaluated only) or RETIRED, and which one is the site default.
 * {@link MlPredictionService} owns the ONNX sessions; this service reconciles what is
 * on disk into {@code ml_models} rows and caches an immutable {@link ServingPlan} that
 * the prediction path reads lock-free.
 *
 * <p>The first bundle ever discovered becomes ACTIVE + default automatically; every
 * later new slug arrives as CANDIDATE and must be promoted from the admin dashboard.
 */
@Service
public class MlModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(MlModelRegistryService.class);

    private final MlModelRepository modelRepository;
    private final MlPredictionService mlPredictionService;
    private final ObjectMapper objectMapper;

    private volatile ServingPlan plan = ServingPlan.empty();

    public MlModelRegistryService(MlModelRepository modelRepository,
                                  MlPredictionService mlPredictionService,
                                  ObjectMapper objectMapper) {
        this.modelRepository = modelRepository;
        this.mlPredictionService = mlPredictionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    @Transactional
    public void reconcile() {
        reconcileStatuses(mlPredictionService.getStatuses());
    }

    /** Reloads all bundles from disk, then reconciles rows and rebuilds the plan. */
    @Transactional
    public List<MlBundleStatus> reloadAndReconcile() {
        List<MlBundleStatus> statuses = mlPredictionService.reload();
        reconcileStatuses(statuses);
        return statuses;
    }

    /** The current serving decisions; cheap volatile read for the prediction path. */
    public ServingPlan plan() {
        return plan;
    }

    /** All registry rows (newest state), for the admin models table. */
    public List<MlModel> models() {
        return modelRepository.findAllByOrderBySlug();
    }

    /**
     * slug → trained-on season years, read from the registry rows rather than the
     * serving plan: evaluation rows outlive bundle loading (and even retirement), so
     * in-sample badging must not depend on whether the bundle is currently loaded.
     * Slugs with no recorded train seasons (legacy manifests) are omitted.
     */
    public Map<String, java.util.Set<Integer>> trainedSeasonsBySlug() {
        Map<String, java.util.Set<Integer>> result = new LinkedHashMap<>();
        for (MlModel m : modelRepository.findAllByOrderBySlug()) {
            java.util.Set<Integer> seasons = parseSeasons(m.getTrainSeasons());
            if (!seasons.isEmpty()) {
                result.put(m.getSlug(), seasons);
            }
        }
        return result;
    }

    /**
     * Admin-table view row: registry state + loaded flag + headline metrics. Test-set
     * metrics come from the single held-out season; the walk-forward (wf) columns are
     * means over the trainer's expanding-window report — the honest promotion metric.
     */
    public record MlModelView(String slug, String displayName, MlModel.Status status,
                              boolean isDefault, boolean loaded, String version,
                              LocalDateTime trainedAt, String featureSet,
                              Double spreadRmse, Double brierScore,
                              Double wfSpreadRmse, Double wfBrier, String wfDetail) {}

    public List<MlModelView> modelViews() {
        return modelRepository.findAllByOrderBySlug().stream()
                .map(m -> {
                    Double spreadRmse = null, brier = null, wfSpreadRmse = null, wfBrier = null;
                    String wfDetail = null;
                    if (m.getMetricsJson() != null) {
                        try {
                            Map<?, ?> metrics = objectMapper.readValue(m.getMetricsJson(), Map.class);
                            spreadRmse   = asDouble(metrics.get("spreadRmse"));
                            brier        = asDouble(metrics.get("brierScore"));
                            wfSpreadRmse = asDouble(metrics.get("wfSpreadRmse"));
                            wfBrier      = asDouble(metrics.get("wfBrier"));
                            wfDetail     = metrics.get("wfDetail") instanceof String s ? s : null;
                        } catch (Exception ignored) {
                        }
                    }
                    return new MlModelView(m.getSlug(),
                            m.getDisplayName() != null ? m.getDisplayName() : m.getSlug(),
                            m.getStatus(), Boolean.TRUE.equals(m.getIsDefault()),
                            mlPredictionService.isLoaded(m.getSlug()),
                            m.getVersion(), m.getTrainedAt(), m.getFeatureSet(),
                            spreadRmse, brier, wfSpreadRmse, wfBrier, wfDetail);
                })
                .toList();
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    // ── Admin actions ─────────────────────────────────────────────────────────

    /** Makes the model ACTIVE and the site default. */
    @Transactional
    public void promote(String slug) {
        MlModel model = require(slug);
        for (MlModel other : modelRepository.findAll()) {
            if (other.getIsDefault() && !other.getSlug().equals(slug)) {
                other.setIsDefault(false);
                touch(other);
            }
        }
        model.setStatus(MlModel.Status.ACTIVE);
        model.setIsDefault(true);
        touch(model);
        rebuildPlan();
        log.info("ML model {} promoted to default", slug);
    }

    /** Makes the model ACTIVE (publicly served) without changing the default. */
    @Transactional
    public void activate(String slug) {
        MlModel model = require(slug);
        model.setStatus(MlModel.Status.ACTIVE);
        touch(model);
        ensureDefaultExists();
        rebuildPlan();
        log.info("ML model {} activated", slug);
    }

    /** Retires the model (not served, not evaluated); reassigns the default if needed. */
    @Transactional
    public void retire(String slug) {
        MlModel model = require(slug);
        model.setStatus(MlModel.Status.RETIRED);
        model.setIsDefault(false);
        touch(model);
        ensureDefaultExists();
        rebuildPlan();
        log.info("ML model {} retired", slug);
    }

    /** Moves a retired model back to shadow evaluation. */
    @Transactional
    public void reinstate(String slug) {
        MlModel model = require(slug);
        model.setStatus(MlModel.Status.CANDIDATE);
        touch(model);
        rebuildPlan();
        log.info("ML model {} reinstated as candidate", slug);
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    private void reconcileStatuses(List<MlBundleStatus> statuses) {
        for (MlBundleStatus status : statuses) {
            MlModel model = modelRepository.findBySlug(status.slug()).orElseGet(() -> {
                MlModel created = new MlModel();
                created.setSlug(status.slug());
                created.setCreatedAt(LocalDateTime.now());
                boolean first = modelRepository.countByStatusNot(MlModel.Status.RETIRED) == 0;
                created.setStatus(first ? MlModel.Status.ACTIVE : MlModel.Status.CANDIDATE);
                created.setIsDefault(first);
                log.info("ML model {} registered as {}", status.slug(),
                        first ? "ACTIVE default (first model)" : "CANDIDATE (shadow)");
                return created;
            });
            model.setDisplayName(status.displayName());
            model.setFeatureSet(status.featureSet());
            model.setVersion(status.version());
            if (status.trainedAt() != null) {
                model.setTrainedAt(LocalDateTime.ofInstant(status.trainedAt(), ZoneId.systemDefault()));
            }
            model.setMetricsJson(metricsJson(status));
            model.setTrainSeasons(joinSeasons(status.trainSeasons()));
            model.setTestSeason(status.testSeason());
            touch(model);
        }
        ensureDefaultExists();
        rebuildPlan();
    }

    /** Season years → "2021,2022,…", or null when the manifest carried none (legacy). */
    private static String joinSeasons(List<Integer> seasons) {
        if (seasons == null || seasons.isEmpty()) return null;
        return seasons.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** "2021,2022" → set of years; empty set for null/blank/malformed input. */
    static java.util.Set<Integer> parseSeasons(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Set.of();
        java.util.Set<Integer> years = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            try {
                years.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return java.util.Set.copyOf(years);
    }

    /** Guarantees a default exists whenever any loaded ACTIVE model exists. */
    private void ensureDefaultExists() {
        boolean hasDefault = modelRepository.findByIsDefaultTrue()
                .filter(m -> m.getStatus() == MlModel.Status.ACTIVE)
                .isPresent();
        if (hasDefault) return;
        modelRepository.findByIsDefaultTrue().ifPresent(m -> {
            m.setIsDefault(false);
            touch(m);
        });
        modelRepository.findAllByOrderBySlug().stream()
                .filter(m -> m.getStatus() == MlModel.Status.ACTIVE
                        && mlPredictionService.isLoaded(m.getSlug()))
                .findFirst()
                .ifPresent(m -> {
                    m.setIsDefault(true);
                    touch(m);
                    log.info("ML model {} is now the default", m.getSlug());
                });
    }

    private void rebuildPlan() {
        Map<String, String> active = new LinkedHashMap<>();
        Map<String, String> evaluable = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        Map<String, java.util.Set<Integer>> trainSeasons = new LinkedHashMap<>();
        String defaultSlug = null;
        boolean needsExtendedStats = false;

        for (MlModel model : modelRepository.findAllByOrderBySlug()) {
            String slug = model.getSlug();
            if (model.getStatus() == MlModel.Status.RETIRED || !mlPredictionService.isLoaded(slug)) {
                continue;
            }
            displayNames.put(slug, model.getDisplayName() != null ? model.getDisplayName() : slug);
            evaluable.put(slug, model.getVersion());
            trainSeasons.put(slug, parseSeasons(model.getTrainSeasons()));
            if (model.getStatus() == MlModel.Status.ACTIVE) {
                active.put(slug, model.getVersion());
                if (model.getIsDefault()) defaultSlug = slug;
            }
            List<String> features = mlPredictionService.featureNames(slug);
            if (features != null && MlFeatureRegistry.needsExtendedStats(features)) {
                needsExtendedStats = true;
            }
        }
        this.plan = new ServingPlan(defaultSlug, Map.copyOf(active), Map.copyOf(evaluable),
                Map.copyOf(displayNames), Map.copyOf(trainSeasons), needsExtendedStats);
    }

    private MlModel require(String slug) {
        return modelRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown ML model: " + slug));
    }

    private void touch(MlModel model) {
        model.setUpdatedAt(LocalDateTime.now());
        modelRepository.save(model);
    }

    /**
     * Serializes the bundle's test-set metrics (same keys as before) plus walk-forward
     * means and a per-season detail string, so the admin table can show both without
     * another manifest read.
     */
    private String metricsJson(MlBundleStatus status) {
        if (status.metrics() == null && status.walkForward().isEmpty()) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        if (status.metrics() != null) {
            MlBundleStatus.Metrics m = status.metrics();
            payload.put("spreadRmse",  m.spreadRmse());
            payload.put("spreadMae",   m.spreadMae());
            payload.put("totalRmse",   m.totalRmse());
            payload.put("totalMae",    m.totalMae());
            payload.put("brierScore",  m.brierScore());
            payload.put("winAccuracy", m.winAccuracy());
            payload.put("inSample",    m.inSample());
        }
        List<MlBundleStatus.WalkForwardSeason> wf = status.walkForward();
        if (!wf.isEmpty()) {
            payload.put("wfSpreadRmse", meanOf(wf, MlBundleStatus.WalkForwardSeason::spreadRmse));
            payload.put("wfTotalRmse",  meanOf(wf, MlBundleStatus.WalkForwardSeason::totalRmse));
            payload.put("wfBrier",      meanOf(wf, MlBundleStatus.WalkForwardSeason::brier));
            payload.put("wfDetail", wf.stream()
                    .map(s -> s.season() + ": RMSE " + (s.spreadRmse() != null ? s.spreadRmse() : "—")
                            + ", Brier " + (s.brier() != null ? s.brier() : "—"))
                    .collect(java.util.stream.Collectors.joining(" · ")));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double meanOf(List<MlBundleStatus.WalkForwardSeason> wf,
                                 java.util.function.Function<MlBundleStatus.WalkForwardSeason, Double> metric) {
        double[] values = wf.stream().map(metric)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray();
        if (values.length == 0) return null;
        return java.util.Arrays.stream(values).average().orElse(Double.NaN);
    }

    /**
     * Immutable serving decisions.
     *
     * @param defaultSlug        the ACTIVE + default bundle filling {@code PredictionResult.ml}, or null
     * @param activeVersions     slug → version for loaded ACTIVE bundles (public predictions)
     * @param evaluableVersions  slug → version for loaded ACTIVE + CANDIDATE bundles (evaluation rows)
     * @param displayNames       slug → display name for all servable bundles
     * @param trainSeasonsBySlug slug → season years the bundle trained on (empty set for
     *                           legacy bundles) — those seasons' evaluation rows are in-sample
     * @param needsExtendedStats true when any servable bundle uses box-score/RPI features
     */
    public record ServingPlan(String defaultSlug,
                              Map<String, String> activeVersions,
                              Map<String, String> evaluableVersions,
                              Map<String, String> displayNames,
                              Map<String, java.util.Set<Integer>> trainSeasonsBySlug,
                              boolean needsExtendedStats) {

        static ServingPlan empty() {
            return new ServingPlan(null, Map.of(), Map.of(), Map.of(), Map.of(), false);
        }

        public boolean hasServableModels() {
            return !evaluableVersions.isEmpty();
        }
    }
}
