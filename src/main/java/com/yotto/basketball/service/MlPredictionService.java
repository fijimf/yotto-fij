package com.yotto.basketball.service;

import ai.onnxruntime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.FloatBuffer;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Loads three ONNX models (spread, total, win-probability) from a filesystem directory
 * and scores them for a given {@link MlFeatureVector}.
 *
 * <p>Enabled only when {@code prediction.ml.enabled=true} AND the model directory
 * contains {@code spread_model.onnx}, {@code total_model.onnx},
 * {@code winprob_model.onnx}, and {@code features.json}. If any file is missing the
 * service initialises in a disabled state — Phase 1 predictions continue unaffected.
 *
 * <p>A {@link ReentrantReadWriteLock} guards the three sessions: {@link #predict}
 * holds a read lock (concurrent predictions allowed); {@link #reload} holds a write
 * lock (blocks until in-flight predictions finish, then swaps sessions).
 */
@Service
public class MlPredictionService {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionService.class);
    private static final int EXPECTED_FEATURES = 24;

    @Value("${prediction.ml.model-dir:/models}")
    private String modelDir;

    @Value("${prediction.ml.enabled:false}")
    private boolean configEnabled;

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Guarded by lock
    private OrtEnvironment env;
    private OrtSession spreadSession;
    private OrtSession totalSession;
    private OrtSession winprobSession;
    private boolean enabled = false;
    private String modelVersion;
    private Instant trainedAt;
    private int featureCount;

    public MlPredictionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!configEnabled) {
            log.info("ML predictions disabled via configuration (prediction.ml.enabled=false)");
            return;
        }
        loadModels();
    }

    /** Scores all three models for the given feature vector. Returns null if disabled or features incomplete. */
    public PredictionResult.MlPrediction predict(MlFeatureVector v) {
        if (v == null) return null;
        if (hasNullRollingFeature(v)) return null;

        lock.readLock().lock();
        try {
            if (!enabled) return null;

            float[] input = toFloatArray(v);
            long[] shape = {1, EXPECTED_FEATURES};

            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)) {
                var inputs = java.util.Map.of("float_input", tensor);

                double spread   = runRegressor(spreadSession,   inputs);
                double total    = runRegressor(totalSession,    inputs);
                double pHome    = runClassifier(winprobSession, inputs);
                double pAway    = 1.0 - pHome;

                return new PredictionResult.MlPrediction(
                        spread, total,
                        pHome, pAway,
                        impliedMoneyline(pHome), impliedMoneyline(pAway),
                        modelVersion, true);
            }
        } catch (Exception e) {
            log.warn("ML prediction failed: {}", e.getMessage());
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Closes all existing sessions and re-initialises from {@code modelDir}.
     * Acquires a write lock — waits for in-flight predictions to complete first.
     */
    public MlModelStatus reload() {
        lock.writeLock().lock();
        try {
            closeSessionsUnderLock();
            loadModels();
        } finally {
            lock.writeLock().unlock();
        }
        log.info("ML models reloaded — enabled={}, version={}", enabled, modelVersion);
        return getStatus();
    }

    public boolean isEnabled() {
        lock.readLock().lock();
        try { return enabled; } finally { lock.readLock().unlock(); }
    }

    public MlModelStatus getStatus() {
        lock.readLock().lock();
        try {
            return new MlModelStatus(enabled, modelVersion, trainedAt, featureCount);
        } finally {
            lock.readLock().unlock();
        }
    }

    @PreDestroy
    public void close() {
        lock.writeLock().lock();
        try {
            closeSessionsUnderLock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Must be called with the write lock held (or during @PostConstruct before any readers). */
    private void loadModels() {
        enabled = false;
        modelVersion = null;
        trainedAt = null;
        featureCount = 0;

        try {
            File dir           = new File(modelDir);
            File featuresFile  = new File(dir, "features.json");
            File spreadFile    = new File(dir, "spread_model.onnx");
            File totalFile     = new File(dir, "total_model.onnx");
            File winprobFile   = new File(dir, "winprob_model.onnx");

            if (!featuresFile.exists() || !spreadFile.exists()
                    || !totalFile.exists() || !winprobFile.exists()) {
                log.warn("ML model files not found in {} — running in Phase 1-only mode", modelDir);
                return;
            }

            JsonNode features = objectMapper.readTree(featuresFile);
            int count = features.path("features").size();
            if (count != EXPECTED_FEATURES) {
                log.warn("features.json has {} features, expected {} — skipping ML load", count, EXPECTED_FEATURES);
                return;
            }

            env          = OrtEnvironment.getEnvironment();
            spreadSession  = env.createSession(spreadFile.getAbsolutePath());
            totalSession   = env.createSession(totalFile.getAbsolutePath());
            winprobSession = env.createSession(winprobFile.getAbsolutePath());

            modelVersion = features.path("version").asText(null);
            trainedAt    = Instant.ofEpochMilli(featuresFile.lastModified());
            featureCount = count;
            enabled      = true;

            log.info("ML models loaded — version={}, featureCount={}", modelVersion, featureCount);
        } catch (Exception e) {
            log.warn("Failed to load ML models from {}: {} — running in Phase 1-only mode",
                    modelDir, e.getMessage());
            closeSessionsUnderLock();
        }
    }

    private void closeSessionsUnderLock() {
        closeQuietly(spreadSession);
        closeQuietly(totalSession);
        closeQuietly(winprobSession);
        closeQuietly(env);
        spreadSession  = null;
        totalSession   = null;
        winprobSession = null;
        env            = null;
        enabled        = false;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    /** Runs a regressor session and returns output[0] (single-batch float). */
    private static double runRegressor(OrtSession session, java.util.Map<String, OnnxTensor> inputs)
            throws OrtException {
        try (OrtSession.Result result = session.run(inputs)) {
            // Use named access to avoid Map.Entry vs OnnxValue ambiguity across ORT versions.
            // onnxmltools may produce float[] (shape [1]) or float[][] (shape [1,1]).
            Object raw = result.get("variable").get().getValue();
            if (raw instanceof float[][]) return ((float[][]) raw)[0][0];
            return ((float[]) raw)[0];
        }
    }

    /**
     * Runs the calibrated-classifier session and returns P(home wins).
     *
     * <p>The Python training script exports with {@code zipmap=False}, so
     * {@code output_probability} is a float tensor of shape [1, 2]. Column 1 is P(class 1)
     * = P(home wins).
     */
    private static double runClassifier(OrtSession session, java.util.Map<String, OnnxTensor> inputs)
            throws OrtException {
        try (OrtSession.Result result = session.run(inputs)) {
            // "output_probability" is float[1][2] when zipmap=False
            float[][] probs = (float[][]) result.get("output_probability").get().getValue();
            return probs[0][1];
        }
    }

    /** Converts the feature record to a float array in canonical feature order. */
    private static float[] toFloatArray(MlFeatureVector v) {
        return new float[]{
                (float) v.masseyBetaHome(),    (float) v.masseyBetaAway(),    (float) v.masseyBetaDiff(),
                (float) v.masseyGammaHome(),   (float) v.masseyGammaAway(),   (float) v.masseyGammaSum(),
                (float) v.btThetaHome(),       (float) v.btThetaAway(),       (float) v.btLogodds(),
                v.homeWinPctL5().floatValue(), v.homeAvgMarginL5().floatValue(),
                v.homeAvgTotalL5().floatValue(), v.homeMarginStddevL5().floatValue(),
                v.awayWinPctL5().floatValue(), v.awayAvgMarginL5().floatValue(),
                v.awayAvgTotalL5().floatValue(), v.awayMarginStddevL5().floatValue(),
                (float) v.homeGamesPlayed(),   (float) v.awayGamesPlayed(),
                v.homeDaysRest() == null ? -1f : v.homeDaysRest().floatValue(),
                v.awayDaysRest() == null ? -1f : v.awayDaysRest().floatValue(),
                (float) v.seasonWeek(),
                v.isNeutralSite()    ? 1f : 0f,
                v.isConferenceGame() ? 1f : 0f
        };
    }

    /** Returns true if any rolling Double feature is null (signals incomplete window). */
    private static boolean hasNullRollingFeature(MlFeatureVector v) {
        return v.homeWinPctL5()       == null || v.homeAvgMarginL5()   == null
            || v.homeAvgTotalL5()     == null || v.homeMarginStddevL5() == null
            || v.awayWinPctL5()       == null || v.awayAvgMarginL5()   == null
            || v.awayAvgTotalL5()     == null || v.awayMarginStddevL5() == null;
    }

    private static int impliedMoneyline(double p) {
        if (p >= 0.5) return -(int) Math.round(p / (1.0 - p) * 100);
        return (int) Math.round((1.0 - p) / p * 100);
    }
}
