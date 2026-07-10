package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Registry row for one named ML model bundle (directory {@code /models/<slug>} holding
 * three ONNX files + a features.json manifest). Serving state:
 *
 * <ul>
 *   <li>{@code ACTIVE} — predictions shown publicly; the {@code isDefault} one fills
 *       {@code PredictionResult.ml}</li>
 *   <li>{@code CANDIDATE} — shadow mode: scored into {@code prediction_evaluations}
 *       (visible on the performance page) but not returned in public predictions</li>
 *   <li>{@code RETIRED} — not loaded or scored; files and history are kept</li>
 * </ul>
 */
@Entity
@Table(name = "ml_models")
public class MlModel {

    public enum Status { ACTIVE, CANDIDATE, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(length = 40, unique = true)
    private String slug;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "feature_set", length = 40)
    private String featureSet;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status = Status.CANDIDATE;

    @NotNull
    @Column(name = "is_default")
    private Boolean isDefault = false;

    @Column(length = 40)
    private String version;

    private LocalDateTime trainedAt;

    @Column(name = "metrics_json", columnDefinition = "TEXT")
    private String metricsJson;

    @NotNull
    private LocalDateTime createdAt;

    @NotNull
    private LocalDateTime updatedAt;

    public MlModel() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getFeatureSet() { return featureSet; }
    public void setFeatureSet(String featureSet) { this.featureSet = featureSet; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public LocalDateTime getTrainedAt() { return trainedAt; }
    public void setTrainedAt(LocalDateTime trainedAt) { this.trainedAt = trainedAt; }

    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MlModel that = (MlModel) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
