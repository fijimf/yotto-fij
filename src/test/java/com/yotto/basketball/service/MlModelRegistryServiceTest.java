package com.yotto.basketball.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.MlModel;
import com.yotto.basketball.repository.MlModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MlModelRegistryService reconciliation of bundle metadata into
 * {@code ml_models} rows and the cached ServingPlan. No Spring context or database —
 * repository and prediction service are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class MlModelRegistryServiceTest {

    @Mock MlModelRepository modelRepository;
    @Mock MlPredictionService mlPredictionService;

    private MlModelRegistryService service;

    @BeforeEach
    void setUp() {
        service = new MlModelRegistryService(modelRepository, mlPredictionService, new ObjectMapper());
    }

    private static MlBundleStatus status(String slug, List<Integer> trainSeasons, Integer testSeason) {
        return new MlBundleStatus(slug, slug, "baseline", "v1", Instant.now(), 27,
                trainSeasons, testSeason, null, List.of());
    }

    private static MlModel existingModel(String slug) {
        MlModel model = new MlModel();
        model.setSlug(slug);
        model.setStatus(MlModel.Status.ACTIVE);
        model.setIsDefault(true);
        model.setCreatedAt(LocalDateTime.now());
        return model;
    }

    @Test
    void reconcilePersistsTrainSeasonsAndExposesThemOnThePlan() {
        MlModel model = existingModel("m1");
        when(mlPredictionService.getStatuses())
                .thenReturn(List.of(status("m1", List.of(2021, 2022), 2026)));
        when(mlPredictionService.isLoaded("m1")).thenReturn(true);
        when(modelRepository.findBySlug("m1")).thenReturn(Optional.of(model));
        when(modelRepository.findByIsDefaultTrue()).thenReturn(Optional.of(model));
        when(modelRepository.findAllByOrderBySlug()).thenReturn(List.of(model));

        service.reconcile();

        assertThat(model.getTrainSeasons()).isEqualTo("2021,2022");
        assertThat(model.getTestSeason()).isEqualTo(2026);
        assertThat(service.plan().trainSeasonsBySlug())
                .containsExactly(java.util.Map.entry("m1", Set.of(2021, 2022)));
    }

    @Test
    void legacyBundleWithoutTrainSeasonsPersistsNullAndEmptyPlanEntry() {
        MlModel model = existingModel("legacy");
        when(mlPredictionService.getStatuses())
                .thenReturn(List.of(status("legacy", List.of(), null)));
        when(mlPredictionService.isLoaded("legacy")).thenReturn(true);
        when(modelRepository.findBySlug("legacy")).thenReturn(Optional.of(model));
        when(modelRepository.findByIsDefaultTrue()).thenReturn(Optional.of(model));
        when(modelRepository.findAllByOrderBySlug()).thenReturn(List.of(model));

        service.reconcile();

        assertThat(model.getTrainSeasons()).isNull();
        assertThat(model.getTestSeason()).isNull();
        assertThat(service.plan().trainSeasonsBySlug())
                .containsExactly(java.util.Map.entry("legacy", Set.of()));
    }

    @Test
    void reconcilePersistsWalkForwardMeansIntoMetricsJson() {
        MlModel model = existingModel("m1");
        MlBundleStatus status = new MlBundleStatus("m1", "m1", "baseline", "v1", Instant.now(), 27,
                List.of(2021), 2026, null,
                List.of(new MlBundleStatus.WalkForwardSeason(2022, 11.5, 17.0, 0.195),
                        new MlBundleStatus.WalkForwardSeason(2023, 12.5, 17.5, 0.185),
                        new MlBundleStatus.WalkForwardSeason(2024, null, null, null)));
        when(mlPredictionService.getStatuses()).thenReturn(List.of(status));
        when(mlPredictionService.isLoaded("m1")).thenReturn(true);
        when(modelRepository.findBySlug("m1")).thenReturn(Optional.of(model));
        when(modelRepository.findByIsDefaultTrue()).thenReturn(Optional.of(model));
        when(modelRepository.findAllByOrderBySlug()).thenReturn(List.of(model));

        service.reconcile();

        // Null-metric seasons are excluded from the means, not treated as zero
        assertThat(model.getMetricsJson())
                .contains("\"wfSpreadRmse\":12.0")
                .contains("\"wfBrier\":0.19")
                .contains("2022: RMSE 11.5");

        MlModelRegistryService.MlModelView view = service.modelViews().get(0);
        assertThat(view.wfSpreadRmse()).isEqualTo(12.0);
        assertThat(view.wfBrier()).isEqualTo(0.19);
        assertThat(view.wfDetail()).contains("2023: RMSE 12.5");
    }

    @Test
    void parseSeasonsToleratesNullBlankAndMalformedInput() {
        assertThat(MlModelRegistryService.parseSeasons(null)).isEmpty();
        assertThat(MlModelRegistryService.parseSeasons("  ")).isEmpty();
        assertThat(MlModelRegistryService.parseSeasons("2021, 2022 ,x,2023"))
                .containsExactlyInAnyOrder(2021, 2022, 2023);
    }
}
