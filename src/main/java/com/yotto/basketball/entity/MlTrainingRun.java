package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Durable record of one ML training run executed by the trainer service. The service
 * keeps run state in memory only, so this table is the history the admin dashboard
 * shows; {@code runId} is the trainer-service UUID used for status polling.
 */
@Entity
@Table(name = "ml_training_runs")
public class MlTrainingRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "run_id", length = 40, unique = true)
    private String runId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status;

    @Column(name = "train_seasons", length = 200)
    private String trainSeasons;

    private Integer testSeason;

    @NotNull
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(name = "metrics_json", columnDefinition = "TEXT")
    private String metricsJson;

    @Column(name = "log_tail", columnDefinition = "TEXT")
    private String logTail;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @NotNull
    private Boolean reloaded = false;

    public MlTrainingRun() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getTrainSeasons() { return trainSeasons; }
    public void setTrainSeasons(String trainSeasons) { this.trainSeasons = trainSeasons; }

    public Integer getTestSeason() { return testSeason; }
    public void setTestSeason(Integer testSeason) { this.testSeason = testSeason; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }

    public String getLogTail() { return logTail; }
    public void setLogTail(String logTail) { this.logTail = logTail; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Boolean getReloaded() { return reloaded; }
    public void setReloaded(Boolean reloaded) { this.reloaded = reloaded; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MlTrainingRun that = (MlTrainingRun) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
