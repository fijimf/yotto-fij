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
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Smoke tests for bundle loading/scoring using the tiny deterministic fixture models in
 * {@code src/test/resources/ml-models} (legacy flat layout — loaded as slug "baseline"):
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

    /** Copies the flat fixture files into {@code target/<subdir>}. */
    private static void copyFixturesInto(Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(Path.of(fixtureDir()))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, target.resolve(f.getFileName().toString()));
            }
        }
    }

    static PredictionContext completeContext() {
        return new PredictionContext(
                7.5, 2.5,                // massey beta home/away → spread fixture returns 7.5
                70.25, 68.5,             // massey gamma home/away → total fixture returns 138.75
                0.8, 0.3, 0.1,
                0.9, 0.35, 0.1,
                0.6, 4.2, 145.8, 9.1,
                0.4, -2.6, 150.2, 11.3,
                12, 11,
                3, 4, 9,
                false, true,
                Map.of(), Map.of(), null, null);
    }

    @Test
    void initLoadsLegacyFlatLayoutAsBaseline() {
        service = newService(fixtureDir(), true);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.loadedSlugs()).containsExactly("baseline");
        assertThat(service.featureNames("baseline")).hasSize(27)
                .startsWith("massey_beta_home").endsWith("is_conference_game");

        MlBundleStatus status = service.getStatuses().get(0);
        assertThat(status.slug()).isEqualTo("baseline");
        assertThat(status.version()).isEqualTo("test-fixture-1");
        assertThat(status.featureCount()).isEqualTo(27);
        MlBundleStatus.Metrics m = status.metrics();
        assertThat(m).isNotNull();
        assertThat(m.spreadRmse()).isEqualTo(10.5);
        assertThat(m.brierScore()).isEqualTo(0.1875);
        assertThat(m.inSample()).isFalse();
    }

    @Test
    void predictScoresAllThreeModels() {
        service = newService(fixtureDir(), true);

        PredictionResult.MlPrediction p = service.predict("baseline", completeContext());

        assertThat(p).isNotNull();
        assertThat(p.spread()).isCloseTo(7.5, within(1e-4));     // feature 0: massey_beta_home
        assertThat(p.total()).isCloseTo(138.75, within(1e-4));   // feature 5: massey_gamma_sum
        assertThat(p.homeWinProbability()).isCloseTo(0.5, within(1e-6));
        assertThat(p.homeImpliedMoneyline()).isEqualTo(-100);
        assertThat(p.modelSlug()).isEqualTo("baseline");
        assertThat(p.modelVersion()).isEqualTo("test-fixture-1");
    }

    @Test
    void predictReturnsNullForUnknownSlugAndIncompleteFeatures() {
        service = newService(fixtureDir(), true);

        assertThat(service.predict("nope", completeContext())).isNull();
        assertThat(service.predict("baseline", null)).isNull();

        PredictionContext incomplete = new PredictionContext(
                7.5, 2.5, 70.25, 68.5, 0.8, 0.3, 0.1, 0.9, 0.35, 0.1,
                null, null, null, null,          // home rolling features missing
                0.4, -2.6, 150.2, 11.3,
                12, 11, 3, 4, 9, false, true,
                Map.of(), Map.of(), null, null);
        assertThat(service.predict("baseline", incomplete)).isNull();
    }

    @Test
    void loadsMultipleBundlesFromSubdirectories(@TempDir Path tempDir) throws IOException {
        copyFixturesInto(tempDir.resolve("baseline"));
        copyFixturesInto(tempDir.resolve("alt"));
        // Give the alt bundle its own slug/version
        Path altManifest = tempDir.resolve("alt/features.json");
        String json = Files.readString(altManifest)
                .replace("\"version\": \"test-fixture-1\"", "\"version\": \"alt-v9\"");
        Files.writeString(altManifest, json.replaceFirst("\\{", "{\"slug\": \"alt\", \"display_name\": \"Alt Model\","));

        service = newService(tempDir.toString(), true);

        assertThat(service.loadedSlugs()).containsExactlyInAnyOrder("baseline", "alt");
        PredictionResult.MlPrediction alt = service.predict("alt", completeContext());
        assertThat(alt).isNotNull();
        assertThat(alt.modelSlug()).isEqualTo("alt");
        assertThat(alt.displayName()).isEqualTo("Alt Model");
        assertThat(alt.modelVersion()).isEqualTo("alt-v9");
    }

    @Test
    void bundleWithUnknownFeatureIsSkipped(@TempDir Path tempDir) throws IOException {
        copyFixturesInto(tempDir.resolve("weird"));
        Path manifest = tempDir.resolve("weird/features.json");
        String json = Files.readString(manifest)
                .replace("\"massey_beta_home\"", "\"feature_from_the_future\"");
        Files.writeString(manifest, json);

        service = newService(tempDir.toString(), true);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.predict("weird", completeContext())).isNull();
    }

    @Test
    void missingModelDirectoryDisablesService() {
        service = newService("/nonexistent/model/dir", true);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.getStatuses()).isEmpty();
    }

    @Test
    void configDisabledSkipsLoading() {
        service = newService(fixtureDir(), false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.predict("baseline", completeContext())).isNull();
    }

    @Test
    void reloadReinitialisesSessions() {
        service = newService(fixtureDir(), true);
        assertThat(service.isEnabled()).isTrue();

        var statuses = service.reload();

        assertThat(statuses).hasSize(1);
        PredictionResult.MlPrediction p = service.predict("baseline", completeContext());
        assertThat(p).isNotNull();
        assertThat(p.spread()).isCloseTo(7.5, within(1e-4));
    }
}
