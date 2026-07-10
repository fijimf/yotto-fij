package com.yotto.basketball.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.MlTrainingRun;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.MlTrainingRunRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.scraping.AsyncScrapeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Bridges the admin dashboard to the always-on trainer service (Docker-internal,
 * {@code ML_TRAINER_URL}). Training runs asynchronously inside the trainer container;
 * this service starts runs and reconciles their state into {@code ml_training_runs}.
 *
 * <p>State is pulled, not pushed: {@link #pollActiveRuns()} is invoked by the
 * HTMX-polled training-status fragment (and on dashboard load), fetches
 * {@code GET /status/{runId}} for each RUNNING row, and on completion hot-reloads the
 * ONNX models and kicks off prediction evaluation for all seasons — so one Train
 * click carries through train → reload → evaluate with no shell access.
 */
@Service
public class MlTrainingService {

    private static final Logger log = LoggerFactory.getLogger(MlTrainingService.class);

    /** A RUNNING row older than this with an unreachable/unaware trainer is marked FAILED. */
    static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(2);

    /** Slug rule shared with the trainer service and train_models.py. */
    private static final java.util.regex.Pattern SLUG_RE = java.util.regex.Pattern.compile("^[a-z0-9-]{1,40}$");

    private final MlTrainingRunRepository runRepository;
    private final SeasonRepository seasonRepository;
    private final MlPredictionService mlPredictionService;
    private final MlModelRegistryService mlModelRegistryService;
    private final AsyncScrapeService asyncScrapeService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MlTrainingService(MlTrainingRunRepository runRepository,
                             SeasonRepository seasonRepository,
                             MlPredictionService mlPredictionService,
                             MlModelRegistryService mlModelRegistryService,
                             AsyncScrapeService asyncScrapeService,
                             ObjectMapper objectMapper,
                             RestClient.Builder restClientBuilder,
                             @Value("${prediction.ml.trainer-url:http://trainer:8000}") String trainerUrl) {
        this.runRepository       = runRepository;
        this.seasonRepository    = seasonRepository;
        this.mlPredictionService = mlPredictionService;
        this.mlModelRegistryService = mlModelRegistryService;
        this.asyncScrapeService  = asyncScrapeService;
        this.objectMapper        = objectMapper;
        this.restClient          = restClientBuilder.baseUrl(trainerUrl).build();
    }

    /**
     * Starts a training run for the given model slug on the trainer service
     * (seasons auto-discovered there).
     *
     * @param modelSlug  bundle to (re)train, e.g. "baseline" or "pace-v2"
     * @param featureSet feature set name, or null to let the trainer pick a default
     * @throws IllegalStateException with a user-displayable message when the slug is
     *         invalid or the trainer is busy, has no data, or is unreachable
     */
    @Transactional
    public MlTrainingRun startTraining(String modelSlug, String featureSet) {
        if (modelSlug == null || !SLUG_RE.matcher(modelSlug).matches()) {
            throw new IllegalStateException(
                    "Model name must be 1-40 chars of lowercase letters, digits or hyphens");
        }
        if (runRepository.existsByStatus(MlTrainingRun.Status.RUNNING)) {
            throw new IllegalStateException("A training run is already in progress");
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model_name", modelSlug);
        if (featureSet != null && !featureSet.isBlank()) {
            body.put("feature_set", featureSet);
        }
        TrainerStart resp;
        try {
            resp = restClient.post().uri("/train").body(body).retrieve().body(TrainerStart.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Trainer rejected the request: " + detailOf(e), e);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException(
                    "Trainer service is unreachable — is the trainer container running?", e);
        }
        if (resp == null || resp.runId() == null) {
            throw new IllegalStateException("Trainer returned no run id");
        }

        MlTrainingRun run = new MlTrainingRun();
        run.setRunId(resp.runId());
        run.setModelSlug(modelSlug);
        run.setStatus(MlTrainingRun.Status.RUNNING);
        run.setTrainSeasons(resp.trainSeasons() != null ? joinYears(resp.trainSeasons()) : null);
        run.setTestSeason(resp.testSeason());
        run.setStartedAt(LocalDateTime.now());
        run = runRepository.save(run);
        log.info("ML training run {} started (model={}, train={}, test={})",
                resp.runId(), modelSlug, run.getTrainSeasons(), run.getTestSeason());
        return run;
    }

    /**
     * Reconciles all RUNNING rows against the trainer service. On a run's completion:
     * hot-reloads the ONNX models and (if the reload leaves ML enabled) kicks off
     * incremental prediction evaluation for all seasons.
     */
    @Transactional
    public void pollActiveRuns() {
        for (MlTrainingRun run : runRepository.findByStatus(MlTrainingRun.Status.RUNNING)) {
            reconcile(run);
        }
    }

    /** True when any run is currently RUNNING (drives the HTMX poll UI). */
    public boolean isTrainingInProgress() {
        return runRepository.existsByStatus(MlTrainingRun.Status.RUNNING);
    }

    /** Recent runs decorated with parsed metrics and a display duration, newest first. */
    public List<RunView> recentRuns() {
        return runRepository.findTop5ByOrderByStartedAtDesc().stream()
                .map(this::toView)
                .toList();
    }

    /** View row for the admin training-history fragment. */
    public record RunView(MlTrainingRun run, Double spreadRmse, Double totalRmse,
                          Double brierScore, String version, String duration) {}

    private RunView toView(MlTrainingRun run) {
        Double spreadRmse = null, totalRmse = null, brier = null;
        String version = null;
        if (run.getMetricsJson() != null) {
            try {
                Map<?, ?> m = objectMapper.readValue(run.getMetricsJson(), Map.class);
                spreadRmse = asDouble(m.get("spread_rmse"));
                totalRmse  = asDouble(m.get("total_rmse"));
                brier      = asDouble(m.get("brier_score"));
                version    = m.get("version") != null ? m.get("version").toString() : null;
            } catch (Exception e) {
                log.warn("Unparseable metrics_json for run {}: {}", run.getRunId(), e.getMessage());
            }
        }
        LocalDateTime end = run.getFinishedAt() != null ? run.getFinishedAt() : LocalDateTime.now();
        Duration d = Duration.between(run.getStartedAt(), end);
        String duration = d.toHours() > 0
                ? d.toHours() + "h " + d.toMinutesPart() + "m"
                : d.toMinutes() > 0 ? d.toMinutes() + "m " + d.toSecondsPart() + "s"
                : d.toSeconds() + "s";
        return new RunView(run, spreadRmse, totalRmse, brier, version, duration);
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    private void reconcile(MlTrainingRun run) {
        TrainerStatus status;
        try {
            status = restClient.get().uri("/status/{id}", run.getRunId())
                    .retrieve().body(TrainerStatus.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Trainer restarted and lost in-memory state — the run is gone for good
            fail(run, "Trainer no longer knows this run (container restarted?)");
            return;
        } catch (Exception e) {
            failIfStale(run, "Trainer unreachable while run was in progress");
            return;
        }
        if (status == null) {
            failIfStale(run, "Trainer returned an empty status");
            return;
        }

        run.setLogTail(status.logTail());
        switch (status.status() == null ? "" : status.status()) {
            case "COMPLETED" -> complete(run, status);
            case "FAILED" -> {
                run.setFinishedAt(LocalDateTime.now());
                fail(run, status.error() != null ? status.error() : "training failed");
            }
            default -> runRepository.save(run);   // still RUNNING — persist fresh log tail
        }
    }

    private void complete(MlTrainingRun run, TrainerStatus status) {
        run.setStatus(MlTrainingRun.Status.COMPLETED);
        run.setFinishedAt(LocalDateTime.now());
        if (status.metrics() != null) {
            try {
                run.setMetricsJson(objectMapper.writeValueAsString(status.metrics()));
            } catch (Exception e) {
                log.warn("Could not serialise training metrics: {}", e.getMessage());
            }
        }

        mlModelRegistryService.reloadAndReconcile();
        boolean loaded = run.getModelSlug() != null
                ? mlPredictionService.isLoaded(run.getModelSlug())
                : mlPredictionService.isEnabled();
        run.setReloaded(loaded);
        runRepository.save(run);
        log.info("ML training run {} completed — bundles reloaded (model {} loaded={})",
                run.getRunId(), run.getModelSlug(), loaded);

        if (loaded) {
            List<Integer> years = seasonRepository.findAll().stream()
                    .map(Season::getYear).sorted().toList();
            if (!years.isEmpty()) {
                asyncScrapeService.evaluatePredictionsAsync(years, false);
            }
        }
    }

    private void fail(MlTrainingRun run, String message) {
        run.setStatus(MlTrainingRun.Status.FAILED);
        if (run.getFinishedAt() == null) {
            run.setFinishedAt(LocalDateTime.now());
        }
        run.setErrorMessage(message);
        runRepository.save(run);
        log.warn("ML training run {} failed: {}", run.getRunId(), message);
    }

    /** Transient trainer outages keep the run RUNNING; only a long-stale run is failed. */
    private void failIfStale(MlTrainingRun run, String message) {
        if (run.getStartedAt().isBefore(LocalDateTime.now().minus(STALE_RUN_TIMEOUT))) {
            fail(run, message + " for over " + STALE_RUN_TIMEOUT.toHours() + "h");
        }
    }

    private static String joinYears(List<Integer> years) {
        return String.join(",", years.stream().map(String::valueOf).toList());
    }

    private static String detailOf(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("detail") != null) {
                return body.get("detail").toString();
            }
        } catch (Exception ignored) {
        }
        return e.getStatusText();
    }

    // ── Trainer-service wire types ────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrainerStart(@JsonProperty("run_id") String runId,
                        @JsonProperty("train_seasons") List<Integer> trainSeasons,
                        @JsonProperty("test_season") Integer testSeason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrainerStatus(@JsonProperty("run_id") String runId,
                         String status,
                         @JsonProperty("log_tail") String logTail,
                         Map<String, Object> metrics,
                         String error) {}
}
