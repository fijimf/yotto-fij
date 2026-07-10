package com.yotto.basketball.service;

import java.time.Instant;

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
 * @param metrics      test-set metrics recorded by the trainer, or null
 */
public record MlBundleStatus(String slug, String displayName, String featureSet,
                             String version, Instant trainedAt, int featureCount,
                             Metrics metrics) {

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
