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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Loads and scores ML model bundles. Each bundle is a directory under the model volume
 * ({@code /models/<slug>/}) holding {@code spread_model.onnx}, {@code total_model.onnx},
 * {@code winprob_model.onnx} and a {@code features.json} manifest whose ordered feature
 * list drives input-vector assembly via {@link MlFeatureRegistry} — bundles may use
 * different feature subsets. A legacy flat layout (files directly in the model dir) is
 * loaded as slug {@code baseline}.
 *
 * <p>This service owns ONNX session lifecycle only. Which bundles are served publicly
 * vs. shadow-evaluated is decided by {@link MlModelRegistryService} (DB-backed).
 *
 * <p>A {@link ReentrantReadWriteLock} guards the bundle map: {@link #predict} holds a
 * read lock; {@link #reload} holds a write lock (waits for in-flight predictions).
 */
@Service
public class MlPredictionService {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionService.class);
    static final String LEGACY_SLUG = "baseline";
    private static final org.apache.commons.math3.distribution.NormalDistribution STANDARD_NORMAL =
            new org.apache.commons.math3.distribution.NormalDistribution(0.0, 1.0);

    @Value("${prediction.ml.model-dir:/models}")
    private String modelDir;

    @Value("${prediction.ml.enabled:false}")
    private boolean configEnabled;

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Guarded by lock
    private OrtEnvironment env;
    private final Map<String, Bundle> bundles = new LinkedHashMap<>();

    public MlPredictionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!configEnabled) {
            log.info("ML predictions disabled via configuration (prediction.ml.enabled=false)");
            return;
        }
        lock.writeLock().lock();
        try {
            loadAllBundles();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Scores one bundle for the given context. Returns null when the bundle is not
     * loaded or any of its features is unavailable for this game.
     */
    public PredictionResult.MlPrediction predict(String slug, PredictionContext context) {
        if (context == null) return null;
        lock.readLock().lock();
        try {
            Bundle bundle = bundles.get(slug);
            if (bundle == null) return null;

            float[] input = MlFeatureRegistry.buildVector(bundle.featureNames, context);
            if (input == null) return null;

            long[] shape = {1, input.length};
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)) {
                Map<String, OnnxTensor> inputs = Map.of("float_input", tensor);
                double spread = runRegressor(bundle.spreadSession, inputs, bundle.spreadOutputName);
                if (bundle.residualSpread) {
                    // The model learned only the correction over the Massey prediction
                    spread += context.masseyPredictedMargin();
                }
                double total = runRegressor(bundle.totalSession, inputs, bundle.totalOutputName);
                double pHome = bundle.derivedWinprob
                        ? STANDARD_NORMAL.cumulativeProbability(spread / bundle.marginSigma)
                        : runClassifier(bundle.winprobSession, inputs, bundle.winprobProbOutputName);
                double pAway = 1.0 - pHome;
                return new PredictionResult.MlPrediction(
                        spread, total, pHome, pAway,
                        impliedMoneyline(pHome), impliedMoneyline(pAway),
                        bundle.status.version(), slug, bundle.status.displayName(), true);
            }
        } catch (Exception e) {
            log.warn("ML prediction failed for bundle {}: {}", slug, e.getMessage());
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Closes all bundles and re-scans the model directory. Returns the loaded statuses. */
    public List<MlBundleStatus> reload() {
        lock.writeLock().lock();
        try {
            closeAllUnderLock();
            loadAllBundles();
        } finally {
            lock.writeLock().unlock();
        }
        List<MlBundleStatus> statuses = getStatuses();
        log.info("ML bundles reloaded — {} loaded: {}", statuses.size(),
                statuses.stream().map(MlBundleStatus::slug).toList());
        return statuses;
    }

    /** True when at least one bundle is loaded. */
    public boolean isEnabled() {
        lock.readLock().lock();
        try { return !bundles.isEmpty(); } finally { lock.readLock().unlock(); }
    }

    public boolean isLoaded(String slug) {
        lock.readLock().lock();
        try { return bundles.containsKey(slug); } finally { lock.readLock().unlock(); }
    }

    public Set<String> loadedSlugs() {
        lock.readLock().lock();
        try { return Set.copyOf(bundles.keySet()); } finally { lock.readLock().unlock(); }
    }

    public List<MlBundleStatus> getStatuses() {
        lock.readLock().lock();
        try {
            return bundles.values().stream().map(b -> b.status).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The ordered feature names of a loaded bundle, or null when not loaded. */
    public List<String> featureNames(String slug) {
        lock.readLock().lock();
        try {
            Bundle bundle = bundles.get(slug);
            return bundle != null ? bundle.featureNames : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @PreDestroy
    public void close() {
        lock.writeLock().lock();
        try {
            closeAllUnderLock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Loading (write lock held) ─────────────────────────────────────────────

    private void loadAllBundles() {
        File root = new File(modelDir);
        if (!root.isDirectory()) {
            log.warn("ML model directory {} not found — no bundles loaded", modelDir);
            return;
        }
        env = OrtEnvironment.getEnvironment();

        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File dir : subdirs) {
                if (new File(dir, "features.json").exists() && !dir.getName().startsWith(".")) {
                    loadBundle(dir, dir.getName());
                }
            }
        }
        // Legacy flat layout: files directly in the root, loaded as "baseline"
        if (!bundles.containsKey(LEGACY_SLUG) && new File(root, "features.json").exists()) {
            loadBundle(root, LEGACY_SLUG);
        }
        if (bundles.isEmpty()) {
            log.warn("No ML model bundles found in {} — running in Phase 1-only mode", modelDir);
        }
    }

    private void loadBundle(File dir, String dirSlug) {
        try {
            File featuresFile = new File(dir, "features.json");
            File spreadFile   = new File(dir, "spread_model.onnx");
            File totalFile    = new File(dir, "total_model.onnx");
            File winprobFile  = new File(dir, "winprob_model.onnx");

            JsonNode manifest = objectMapper.readTree(featuresFile);
            boolean residualSpread = "residual_massey".equals(manifest.path("spread_target").asText("margin"));
            boolean derivedWinprob = "derived".equals(manifest.path("winprob_mode").asText("classifier"));
            double marginSigma = manifest.path("margin_sigma").asDouble(0.0);

            // The classifier ONNX is only required when winprob is classifier-based
            if (!spreadFile.exists() || !totalFile.exists()
                    || (!derivedWinprob && !winprobFile.exists())) {
                log.warn("ML bundle {} is missing ONNX files — skipped", dirSlug);
                return;
            }
            if (derivedWinprob && marginSigma <= 0) {
                log.warn("ML bundle {} declares derived winprob without a positive margin_sigma — skipped",
                        dirSlug);
                return;
            }
            List<String> featureNames = new ArrayList<>();
            for (JsonNode f : manifest.path("features")) {
                featureNames.add(f.asText());
            }
            if (featureNames.isEmpty()) {
                log.warn("ML bundle {} has an empty feature list — skipped", dirSlug);
                return;
            }
            List<String> unknown = featureNames.stream()
                    .filter(n -> !MlFeatureRegistry.supports(n)).toList();
            if (!unknown.isEmpty()) {
                log.warn("ML bundle {} uses unknown features {} — skipped (is the app older than the model?)",
                        dirSlug, unknown);
                return;
            }

            String slug = manifest.path("slug").asText(dirSlug);
            Bundle bundle = new Bundle();
            bundle.featureNames          = List.copyOf(featureNames);
            bundle.residualSpread        = residualSpread;
            bundle.derivedWinprob        = derivedWinprob;
            bundle.marginSigma           = marginSigma;
            bundle.spreadSession         = env.createSession(spreadFile.getAbsolutePath());
            bundle.totalSession          = env.createSession(totalFile.getAbsolutePath());
            bundle.spreadOutputName      = firstOutputName(bundle.spreadSession);
            bundle.totalOutputName       = firstOutputName(bundle.totalSession);
            if (!derivedWinprob) {
                bundle.winprobSession        = env.createSession(winprobFile.getAbsolutePath());
                bundle.winprobProbOutputName = probOutputName(bundle.winprobSession);
            }
            bundle.status = new MlBundleStatus(
                    slug,
                    manifest.path("display_name").asText(slug),
                    manifest.path("feature_set").asText(null),
                    manifest.path("version").asText(null),
                    Instant.ofEpochMilli(featuresFile.lastModified()),
                    featureNames.size(),
                    parseIntList(manifest.path("train_seasons")),
                    manifest.path("test_season").isInt() ? manifest.path("test_season").asInt() : null,
                    parseMetrics(manifest.path("metrics")),
                    parseWalkForward(manifest.path("walk_forward")));

            bundles.put(slug, bundle);
            log.info("ML bundle loaded — slug={}, version={}, features={}",
                    slug, bundle.status.version(), featureNames.size());
        } catch (Exception e) {
            log.warn("Failed to load ML bundle {}: {} — skipped", dirSlug, e.getMessage());
        }
    }

    private void closeAllUnderLock() {
        for (Bundle bundle : bundles.values()) {
            closeQuietly(bundle.spreadSession);
            closeQuietly(bundle.totalSession);
            closeQuietly(bundle.winprobSession);
        }
        bundles.clear();
        closeQuietly(env);
        env = null;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Integer array manifest field → immutable list; empty for absent/legacy manifests. */
    private static List<Integer> parseIntList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Integer> values = new ArrayList<>();
        for (JsonNode v : node) {
            if (v.isInt()) values.add(v.asInt());
        }
        return List.copyOf(values);
    }

    /** Trainer walk-forward report array → per-season records; empty for legacy manifests. */
    private static List<MlBundleStatus.WalkForwardSeason> parseWalkForward(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<MlBundleStatus.WalkForwardSeason> seasons = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.path("season").isInt()) continue;
            seasons.add(new MlBundleStatus.WalkForwardSeason(
                    entry.path("season").asInt(),
                    doubleOrNull(entry, "spread_rmse"),
                    doubleOrNull(entry, "total_rmse"),
                    doubleOrNull(entry, "brier")));
        }
        return List.copyOf(seasons);
    }

    private static MlBundleStatus.Metrics parseMetrics(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) return null;
        return new MlBundleStatus.Metrics(
                doubleOrNull(node, "spread_rmse"), doubleOrNull(node, "spread_mae"),
                doubleOrNull(node, "total_rmse"),  doubleOrNull(node, "total_mae"),
                doubleOrNull(node, "brier_score"), doubleOrNull(node, "win_accuracy"),
                node.path("in_sample").asBoolean(false));
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    /** Runs a regressor session and returns output[0] (single-batch float). */
    private static double runRegressor(OrtSession session, Map<String, OnnxTensor> inputs,
                                       String outputName) throws OrtException {
        try (OrtSession.Result result = session.run(inputs)) {
            Object raw = result.get(outputName)
                    .orElseThrow(() -> new OrtException("Output '" + outputName + "' not found in regressor result"))
                    .getValue();
            if (raw instanceof float[][]) return ((float[][]) raw)[0][0];
            return ((float[]) raw)[0];
        }
    }

    /**
     * Runs the calibrated-classifier session and returns P(home wins).
     *
     * <p>The Python training script exports with {@code zipmap=False}, so the probability
     * output is a float tensor of shape [1, 2]. Column 1 is P(class 1) = P(home wins).
     */
    private static double runClassifier(OrtSession session, Map<String, OnnxTensor> inputs,
                                        String probOutputName) throws OrtException {
        try (OrtSession.Result result = session.run(inputs)) {
            float[][] probs = (float[][]) result.get(probOutputName)
                    .orElseThrow(() -> new OrtException("Output '" + probOutputName + "' not found in classifier result"))
                    .getValue();
            return probs[0][1];
        }
    }

    /** Returns the name of the first (and for regressors, only) output of the session. */
    private static String firstOutputName(OrtSession session) throws OrtException {
        return session.getOutputInfo().keySet().iterator().next();
    }

    /**
     * Returns the probability output name from a calibrated-classifier session.
     * Prefers any output whose name contains "prob"; falls back to the last output
     * (skl2onnx always emits label first, probability second).
     */
    private static String probOutputName(OrtSession session) throws OrtException {
        String last = null;
        for (String name : session.getOutputInfo().keySet()) {
            if (name.contains("prob")) return name;
            last = name;
        }
        return last;
    }

    private static int impliedMoneyline(double p) {
        if (p >= 0.5) return -(int) Math.round(p / (1.0 - p) * 100);
        return (int) Math.round((1.0 - p) / p * 100);
    }

    /**
     * One loaded bundle: ONNX sessions + manifest-derived metadata. {@code winprobSession}
     * is null in derived-winprob mode (P(home) = Φ(spread/marginSigma) instead);
     * {@code residualSpread} bundles trained on the residual over the Massey prediction,
     * which is added back at predict time.
     */
    private static final class Bundle {
        List<String> featureNames;
        boolean residualSpread;
        boolean derivedWinprob;
        double marginSigma;
        OrtSession spreadSession;
        OrtSession totalSession;
        OrtSession winprobSession;
        String spreadOutputName;
        String totalOutputName;
        String winprobProbOutputName;
        MlBundleStatus status;
    }
}
