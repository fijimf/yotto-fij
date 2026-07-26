package com.yotto.basketball.service;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of one loaded ML model bundle (directory under the model volume holding
 * three ONNX sessions + a features.json manifest).
 *
 * @param slug         bundle identifier = directory name (manifest "slug" wins if present)
 * @param displayName  human name from the manifest, falling back to the slug
 * @param featureSet   manifest "feature_set" name, or null for legacy manifests
 * @param version      manifest "version" (UTC timestamp string)
 * @param trainedAt    last-modified timestamp of features.json
 * @param featureCount number of features in this bundle's vector
 * @param trainSeasons season years the model actually trained on (empty for legacy
 *                     manifests) — evaluation rows for these seasons are in-sample
 * @param testSeason   held-out test season, or null for legacy manifests
 * @param metrics      test-set metrics recorded by the trainer, or null
 * @param walkForward  per-season expanding-window out-of-sample metrics from the
 *                     trainer's walk-forward report (empty for legacy manifests) —
 *                     the honest promotion yardstick, unlike single-test-season metrics
 */
public record MlBundleStatus(String slug, String displayName, String featureSet,
                             String version, Instant trainedAt, int featureCount,
                             List<Integer> trainSeasons, Integer testSeason,
                             Metrics metrics, List<WalkForwardSeason> walkForward) {

    /** One held-out season of the trainer's walk-forward report. Any metric may be null. */
    public record WalkForwardSeason(int season, Double spreadRmse, Double totalRmse, Double brier) {}

    /**
     * Test-set metrics from the "metrics" block of features.json. Any field may be null.
     *
     * @param inSample true when the trainer fell back to in-sample evaluation —
     *                 metrics are optimistic
     */
    public record Metrics(Double spreadRmse, Double spreadMae,
                          Double totalRmse, Double totalMae,
                          Double brierScore, Double winAccuracy,
                          boolean inSample) {}
}
