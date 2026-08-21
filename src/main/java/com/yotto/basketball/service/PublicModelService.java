package com.yotto.basketball.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The models with public hub pages (/models/{slug}, spec §7.1): ML bundles that
 * are ACTIVE <em>and</em> loaded, plus the classical Adjusted Efficiency
 * prediction model (spec OQ-1 — full evaluation coverage, no manifest).
 * CANDIDATE and RETIRED models never appear here; every /models/{slug} route
 * resolves through {@link #find} so they can't leak.
 */
@Service
public class PublicModelService {

    /** URL slug of the classical adjusted-efficiency prediction model. */
    public static final String ADJ_EFF_SLUG = "adjusted-efficiency";

    private final MlModelRegistryService registryService;

    public PublicModelService(MlModelRegistryService registryService) {
        this.registryService = registryService;
    }

    /**
     * One publicly-served model. {@code modelType} is the prediction_evaluations
     * key ("ML:slug" or "ADJ_EFF"); {@code ml} distinguishes bundle-backed models
     * (with manifests/features) from the classical one.
     */
    public record PublicModel(String slug, String displayName, String modelType,
                              boolean ml, boolean isDefault) {}

    /** Public models: the default ML bundle first, other ACTIVE bundles A–Z, Adjusted Efficiency last. */
    public List<PublicModel> list() {
        MlModelRegistryService.ServingPlan plan = registryService.plan();
        Map<String, String> names = plan.displayNames();
        String defaultSlug = plan.defaultSlug();

        List<PublicModel> models = new ArrayList<>();
        plan.activeVersions().keySet().stream()
                .sorted(Comparator.<String, Boolean>comparing(s -> !s.equals(defaultSlug))
                        .thenComparing(Comparator.naturalOrder()))
                .forEach(slug -> models.add(new PublicModel(slug,
                        names.getOrDefault(slug, slug),
                        PredictionEvaluationService.ML_TYPE_PREFIX + slug,
                        true, slug.equals(defaultSlug))));

        models.add(new PublicModel(ADJ_EFF_SLUG, "Adjusted Efficiency (classic)",
                AdjustedEfficiencyRatingService.MODEL_TYPE_PREDICTION, false, false));
        return models;
    }

    public Optional<PublicModel> find(String slug) {
        return list().stream().filter(m -> m.slug().equals(slug)).findFirst();
    }

    /** The default public model's slug (schedule-page redirect target), if any ML bundle serves. */
    public Optional<String> defaultSlug() {
        return Optional.ofNullable(registryService.plan().defaultSlug());
    }
}
