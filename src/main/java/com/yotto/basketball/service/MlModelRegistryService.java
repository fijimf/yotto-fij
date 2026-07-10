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

    /** Admin-table view row: registry state + loaded flag + headline metrics. */
    public record MlModelView(String slug, String displayName, MlModel.Status status,
                              boolean isDefault, boolean loaded, String version,
                              LocalDateTime trainedAt, String featureSet,
                              Double spreadRmse, Double brierScore) {}

    public List<MlModelView> modelViews() {
        return modelRepository.findAllByOrderBySlug().stream()
                .map(m -> {
                    Double spreadRmse = null, brier = null;
                    if (m.getMetricsJson() != null) {
                        try {
                            Map<?, ?> metrics = objectMapper.readValue(m.getMetricsJson(), Map.class);
                            spreadRmse = asDouble(metrics.get("spreadRmse"));
                            brier      = asDouble(metrics.get("brierScore"));
                        } catch (Exception ignored) {
                        }
                    }
                    return new MlModelView(m.getSlug(),
                            m.getDisplayName() != null ? m.getDisplayName() : m.getSlug(),
                            m.getStatus(), Boolean.TRUE.equals(m.getIsDefault()),
                            mlPredictionService.isLoaded(m.getSlug()),
                            m.getVersion(), m.getTrainedAt(), m.getFeatureSet(),
                            spreadRmse, brier);
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
            touch(model);
        }
        ensureDefaultExists();
        rebuildPlan();
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
        String defaultSlug = null;
        boolean needsExtendedStats = false;

        for (MlModel model : modelRepository.findAllByOrderBySlug()) {
            String slug = model.getSlug();
            if (model.getStatus() == MlModel.Status.RETIRED || !mlPredictionService.isLoaded(slug)) {
                continue;
            }
            displayNames.put(slug, model.getDisplayName() != null ? model.getDisplayName() : slug);
            evaluable.put(slug, model.getVersion());
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
                Map.copyOf(displayNames), needsExtendedStats);
    }

    private MlModel require(String slug) {
        return modelRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown ML model: " + slug));
    }

    private void touch(MlModel model) {
        model.setUpdatedAt(LocalDateTime.now());
        modelRepository.save(model);
    }

    private String metricsJson(MlBundleStatus status) {
        if (status.metrics() == null) return null;
        try {
            return objectMapper.writeValueAsString(status.metrics());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Immutable serving decisions.
     *
     * @param defaultSlug        the ACTIVE + default bundle filling {@code PredictionResult.ml}, or null
     * @param activeVersions     slug → version for loaded ACTIVE bundles (public predictions)
     * @param evaluableVersions  slug → version for loaded ACTIVE + CANDIDATE bundles (evaluation rows)
     * @param displayNames       slug → display name for all servable bundles
     * @param needsExtendedStats true when any servable bundle uses box-score/RPI features
     */
    public record ServingPlan(String defaultSlug,
                              Map<String, String> activeVersions,
                              Map<String, String> evaluableVersions,
                              Map<String, String> displayNames,
                              boolean needsExtendedStats) {

        static ServingPlan empty() {
            return new ServingPlan(null, Map.of(), Map.of(), Map.of(), false);
        }

        public boolean hasServableModels() {
            return !evaluableVersions.isEmpty();
        }
    }
}
