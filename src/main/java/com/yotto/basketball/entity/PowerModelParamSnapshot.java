package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "power_model_param_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"season_id", "model_type", "snapshot_date", "param_name"}))
public class PowerModelParamSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @NotNull
    private String modelType;

    @NotNull
    private LocalDate snapshotDate;

    @NotNull
    private String paramName;

    @NotNull
    private Double paramValue;

    @NotNull
    private LocalDateTime calculatedAt;

    public PowerModelParamSnapshot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }

    public String getParamName() { return paramName; }
    public void setParamName(String paramName) { this.paramName = paramName; }

    public Double getParamValue() { return paramValue; }
    public void setParamValue(Double paramValue) { this.paramValue = paramValue; }

    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PowerModelParamSnapshot that = (PowerModelParamSnapshot) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
