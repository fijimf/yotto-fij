package com.yotto.basketball.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the ONNX loading/scoring path using tiny deterministic fixture models
 * in {@code src/test/resources/ml-models} (generated graphs, not real trained models):
 *
 * <ul>
 *   <li>{@code spread_model.onnx}  — returns feature 0 ({@code massey_beta_home})</li>
 *   <li>{@code total_model.onnx}   — returns feature 5 ({@code massey_gamma_sum})</li>
 *   <li>{@code winprob_model.onnx} — returns probabilities [0.5, 0.5]</li>
 * </ul>
 *
 * <p>No Spring context or database required.
 */
class MlPredictionServiceTest {

    private MlPredictionService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    private MlPredictionService newService(String modelDir, boolean enabled) {
        MlPredictionService s = new MlPredictionService(new ObjectMapper());
        ReflectionTestUtils.setField(s, "modelDir", modelDir);
        ReflectionTestUtils.setField(s, "configEnabled", enabled);
        s.init();
        return s;
    }

    private static String fixtureDir() {
        try {
            return Paths.get(MlPredictionServiceTest.class.getClassLoader()
                    .getResource("ml-models/features.json").toURI()).getParent().toString();
        } catch (Exception e) {
            throw new IllegalStateException("ml-models fixtures missing from test classpath", e);
        }
    }

    private static MlFeatureVector completeVector() {
        return new MlFeatureVector(
                7.5, 2.5, 5.0,
                70.25, 68.5, 138.75,
                0.8, 0.3, 0.62,
                0.9, 0.35, 0.67,
                0.6, 4.2, 145.8, 9.1,
                0.4, -2.6, 150.2, 11.3,
                12, 11,
                3, 4, 9,
                false, true);
    }

    @Test
    void initLoadsModelsAndParsesMetrics() {
        service = newService(fixtureDir(), true);

        assertThat(service.isEnabled()).isTrue();
        MlModelStatus status = service.getStatus();
        assertThat(status.enabled()).isTrue();
        assertThat(status.version()).isEqualTo("test-fixture-1");
        assertThat(status.featureCount()).isEqualTo(27);
        assertThat(status.trainedAt()).isNotNull();

        MlModelStatus.Metrics m = status.metrics();
        assertThat(m).isNotNull();
        assertThat(m.spreadRmse()).isEqualTo(10.5);
        assertThat(m.spreadMae()).isEqualTo(8.25);
        assertThat(m.totalRmse()).isEqualTo(17.75);
        assertThat(m.totalMae()).isEqualTo(14.0);
        assertThat(m.brierScore()).isEqualTo(0.1875);
        assertThat(m.winAccuracy()).isEqualTo(72.5);
        assertThat(m.inSample()).isFalse();
    }

    @Test
    void predictScoresAllThreeModels() {
        service = newService(fixtureDir(), true);

        PredictionResult.MlPrediction p = service.predict(completeVector());

        assertThat(p).isNotNull();
        assertThat(p.spread()).isCloseTo(7.5, org.assertj.core.data.Offset.offset(1e-4));    // feature 0
        assertThat(p.total()).isCloseTo(138.75, org.assertj.core.data.Offset.offset(1e-4));  // feature 5
        assertThat(p.homeWinProbability()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(p.awayWinProbability()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(p.homeImpliedMoneyline()).isEqualTo(-100);
        assertThat(p.awayImpliedMoneyline()).isEqualTo(-100);
        assertThat(p.modelVersion()).isEqualTo("test-fixture-1");
    }

    @Test
    void predictReturnsNullOnIncompleteRollingFeatures() {
        service = newService(fixtureDir(), true);

        MlFeatureVector v = new MlFeatureVector(
                7.5, 2.5, 5.0, 70.25, 68.5, 138.75, 0.8, 0.3, 0.62, 0.9, 0.35, 0.67,
                null, null, null, null,          // home rolling features missing (cold start)
                0.4, -2.6, 150.2, 11.3,
                12, 11, 3, 4, 9, false, true);

        assertThat(service.predict(v)).isNull();
        assertThat(service.predict(null)).isNull();
    }

    @Test
    void missingModelDirectoryDisablesService() {
        service = newService("/nonexistent/model/dir", true);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.getStatus().metrics()).isNull();
        assertThat(service.predict(completeVector())).isNull();
    }

    @Test
    void configDisabledSkipsLoading() {
        service = newService(fixtureDir(), false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.predict(completeVector())).isNull();
    }

    @Test
    void reloadReinitialisesSessions() {
        service = newService(fixtureDir(), true);
        assertThat(service.isEnabled()).isTrue();

        MlModelStatus status = service.reload();

        assertThat(status.enabled()).isTrue();
        PredictionResult.MlPrediction p = service.predict(completeVector());
        assertThat(p).isNotNull();
        assertThat(p.spread()).isCloseTo(7.5, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void featureCountMismatchDisablesService(@TempDir Path tempDir) throws IOException {
        Path fixtures = Path.of(fixtureDir());
        try (Stream<Path> files = Files.list(fixtures)) {
            for (Path f : files.toList()) {
                Files.copy(f, tempDir.resolve(f.getFileName().toString()));
            }
        }
        // Truncate the feature list to 26 names — the loader must refuse the bundle
        String json = Files.readString(tempDir.resolve("features.json"));
        Files.writeString(tempDir.resolve("features.json"),
                json.replace("\"is_conference_game\"", "").replaceAll(",\\s*]", "]"));

        service = newService(tempDir.toString(), true);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.predict(completeVector())).isNull();
    }
}
