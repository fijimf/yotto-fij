package com.yotto.basketball.service;

import java.time.Instant;

/**
 * Snapshot of the ML model service state, used by the admin dashboard.
 *
 * @param enabled      true when all three ONNX sessions are loaded and ready
 * @param version      the "version" string from features.json, or null when disabled
 * @param trainedAt    last-modified timestamp of features.json, or null when disabled
 * @param featureCount number of features in the loaded schema (currently 27)
 * @param metrics      test-set metrics recorded by the training script, or null when
 *                     disabled or when features.json predates the metrics block
 */
public record MlModelStatus(boolean enabled, String version, Instant trainedAt, int featureCount,
                            Metrics metrics) {

    /**
     * Test-set metrics from the "metrics" block of features.json. Any field may be null
     * if absent from the file.
     *
     * @param inSample true when the trainer had no out-of-season rows and fell back to
     *                 in-sample evaluation — metrics are optimistic
     */
    public record Metrics(Double spreadRmse, Double spreadMae,
                          Double totalRmse, Double totalMae,
                          Double brierScore, Double winAccuracy,
                          boolean inSample) {}
}
