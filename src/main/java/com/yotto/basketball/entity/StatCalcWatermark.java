package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Per-season record of the last stats calculation run, used to skip recalculation
 * when nothing changed or scope it to the dates that did.
 */
@Entity
@Table(name = "stat_calc_watermarks",
       uniqueConstraints = @UniqueConstraint(columnNames = {"season_id"}))
public class StatCalcWatermark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @NotNull
    @Column(name = "last_calc_started_at")
    private LocalDateTime lastCalcStartedAt;

    @NotNull
    @Column(name = "final_game_count")
    private Integer finalGameCount;

    public StatCalcWatermark() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public LocalDateTime getLastCalcStartedAt() { return lastCalcStartedAt; }
    public void setLastCalcStartedAt(LocalDateTime lastCalcStartedAt) { this.lastCalcStartedAt = lastCalcStartedAt; }

    public Integer getFinalGameCount() { return finalGameCount; }
    public void setFinalGameCount(Integer finalGameCount) { this.finalGameCount = finalGameCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StatCalcWatermark that = (StatCalcWatermark) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
