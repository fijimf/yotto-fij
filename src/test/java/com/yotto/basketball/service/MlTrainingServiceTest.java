package com.yotto.basketball.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.MlTrainingRun;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.MlTrainingRunRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.scraping.AsyncScrapeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * Unit tests for MlTrainingService against a mocked trainer HTTP service.
 * No Spring context or database — repositories are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class MlTrainingServiceTest {

    private static final String BASE = "http://trainer-test:8000";

    @Mock MlTrainingRunRepository runRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock MlPredictionService mlPredictionService;
    @Mock MlModelRegistryService mlModelRegistryService;
    @Mock AsyncScrapeService asyncScrapeService;

    private MockRestServiceServer server;
    private MlTrainingService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new MlTrainingService(runRepository, seasonRepository, mlPredictionService,
                mlModelRegistryService, asyncScrapeService, new ObjectMapper(), builder, BASE);
    }

    private static MlTrainingRun runningRun(String runId, LocalDateTime startedAt) {
        MlTrainingRun run = new MlTrainingRun();
        run.setRunId(runId);
        run.setModelSlug("baseline");
        run.setStatus(MlTrainingRun.Status.RUNNING);
        run.setStartedAt(startedAt);
        return run;
    }

    // ── startTraining ─────────────────────────────────────────────────────────

    @Test
    void startTraining_savesRunningRow() {
        when(runRepository.existsByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(false);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        server.expect(requestTo(BASE + "/train")).andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"run_id": "abc123", "train_seasons": [2024, 2025], "test_season": 2025}
                        """, MediaType.APPLICATION_JSON));

        MlTrainingRun run = service.startTraining("baseline", null);

        assertThat(run.getRunId()).isEqualTo("abc123");
        assertThat(run.getModelSlug()).isEqualTo("baseline");
        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.RUNNING);
        assertThat(run.getTrainSeasons()).isEqualTo("2024,2025");
        assertThat(run.getTestSeason()).isEqualTo(2025);
        server.verify();
    }

    @Test
    void startTraining_alreadyRunningInDb_throwsWithoutHttpCall() {
        when(runRepository.existsByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(true);

        assertThatThrownBy(() -> service.startTraining("baseline", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
        server.verify();   // no expectations set — verifies no request was made
    }

    @Test
    void startTraining_invalidSlug_throwsWithoutHttpCall() {
        assertThatThrownBy(() -> service.startTraining("Bad Slug!", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Model name");
        server.verify();
    }

    @Test
    void startTraining_trainerBusy_surfacesDetailMessage() {
        when(runRepository.existsByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(false);
        server.expect(requestTo(BASE + "/train"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"training run xyz already in progress\"}"));

        assertThatThrownBy(() -> service.startTraining("baseline", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("training run xyz already in progress");
        verify(runRepository, never()).save(any());
    }

    @Test
    void startTraining_trainerUnreachable_throwsFriendlyMessage() {
        when(runRepository.existsByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(false);
        server.expect(requestTo(BASE + "/train"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> service.startTraining("baseline", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    // ── pollActiveRuns ────────────────────────────────────────────────────────

    @Test
    void poll_completedRun_reloadsModelsAndKicksEvaluation() {
        MlTrainingRun run = runningRun("abc123", LocalDateTime.now().minusMinutes(5));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        when(mlPredictionService.isLoaded("baseline")).thenReturn(true);
        Season s2025 = new Season();
        s2025.setYear(2025);
        when(seasonRepository.findAll()).thenReturn(List.of(s2025));
        server.expect(requestTo(BASE + "/status/abc123")).andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"run_id": "abc123", "status": "COMPLETED",
                         "log_tail": "[train] done",
                         "metrics": {"spread_rmse": 10.5, "brier_score": 0.19, "version": "2026-07-10"}}
                        """, MediaType.APPLICATION_JSON));

        service.pollActiveRuns();

        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getReloaded()).isTrue();
        assertThat(run.getMetricsJson()).contains("spread_rmse");
        assertThat(run.getLogTail()).isEqualTo("[train] done");
        verify(mlModelRegistryService).reloadAndReconcile();
        verify(asyncScrapeService).evaluatePredictionsAsync(List.of(2025), false);
    }

    @Test
    void poll_completedButReloadLeavesMlDisabled_skipsEvaluation() {
        MlTrainingRun run = runningRun("abc123", LocalDateTime.now().minusMinutes(5));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        when(mlPredictionService.isLoaded("baseline")).thenReturn(false);
        server.expect(requestTo(BASE + "/status/abc123"))
                .andRespond(withSuccess("{\"run_id\": \"abc123\", \"status\": \"COMPLETED\"}",
                        MediaType.APPLICATION_JSON));

        service.pollActiveRuns();

        assertThat(run.getReloaded()).isFalse();
        verifyNoInteractions(asyncScrapeService);
    }

    @Test
    void poll_failedRun_recordsError() {
        MlTrainingRun run = runningRun("abc123", LocalDateTime.now().minusMinutes(5));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        server.expect(requestTo(BASE + "/status/abc123"))
                .andRespond(withSuccess("""
                        {"run_id": "abc123", "status": "FAILED",
                         "log_tail": "[train] ERROR: no games",
                         "error": "training exited with code 1"}
                        """, MediaType.APPLICATION_JSON));

        service.pollActiveRuns();

        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("exited with code 1");
        verifyNoInteractions(mlModelRegistryService, asyncScrapeService);
    }

    @Test
    void poll_unknownRunId_marksFailed() {
        MlTrainingRun run = runningRun("gone", LocalDateTime.now().minusMinutes(5));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        server.expect(requestTo(BASE + "/status/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"unknown run id\"}"));

        service.pollActiveRuns();

        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("no longer knows");
    }

    @Test
    void poll_transientOutage_keepsFreshRunRunning() {
        MlTrainingRun run = runningRun("abc123", LocalDateTime.now().minusMinutes(5));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        server.expect(requestTo(BASE + "/status/abc123"))
                .andRespond(withException(new IOException("connection refused")));

        service.pollActiveRuns();

        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.RUNNING);
    }

    @Test
    void poll_staleRunWithUnreachableTrainer_marksFailed() {
        MlTrainingRun run = runningRun("abc123", LocalDateTime.now().minusHours(3));
        when(runRepository.findByStatus(MlTrainingRun.Status.RUNNING)).thenReturn(List.of(run));
        server.expect(requestTo(BASE + "/status/abc123"))
                .andRespond(withException(new IOException("connection refused")));

        service.pollActiveRuns();

        assertThat(run.getStatus()).isEqualTo(MlTrainingRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("unreachable");
    }
}
