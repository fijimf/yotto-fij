package com.yotto.basketball.service;

import java.time.Instant;

/**
 * Snapshot of the ML model service state, used by the admin dashboard.
 *
 * @param enabled      true when all three ONNX sessions are loaded and ready
 * @param version      the "version" string from features.json, or null when disabled
 * @param trainedAt    last-modified timestamp of features.json, or null when disabled
 * @param featureCount number of features in the loaded schema (should always be 24)
 */
public record MlModelStatus(boolean enabled, String version, Instant trainedAt, int featureCount) {}
