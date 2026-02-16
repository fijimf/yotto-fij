package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "scrape_batches")
public class ScrapeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private Integer seasonYear;

    @NotNull
    @Enumerated(EnumType.STRING)
    private ScrapeType scrapeType;

    @NotNull
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    private ScrapeStatus status;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer recordsCreated = 0;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer recordsUpdated = 0;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer datesSucceeded = 0;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer datesFailed = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public enum ScrapeType {
        TEAMS,
        CONFERENCES,
        STANDINGS,
        GAMES,
        ODDS_BACKFILL
    }

    public enum ScrapeStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        PARTIAL
    }

    public ScrapeBatch() {
    }

    public static ScrapeBatch start(Integer seasonYear, ScrapeType scrapeType) {
        ScrapeBatch batch = new ScrapeBatch();
        batch.setSeasonYear(seasonYear);
        batch.setScrapeType(scrapeType);
        batch.setStartedAt(LocalDateTime.now());
        batch.setStatus(ScrapeStatus.RUNNING);
        batch.setRecordsCreated(0);
        batch.setRecordsUpdated(0);
        batch.setDatesSucceeded(0);
        batch.setDatesFailed(0);
        return batch;
    }

    public void complete() {
        this.completedAt = LocalDateTime.now();
        if (this.datesFailed > 0 && this.datesSucceeded > 0) {
            this.status = ScrapeStatus.PARTIAL;
        } else if (this.datesFailed > 0) {
            this.status = ScrapeStatus.FAILED;
        } else {
            this.status = ScrapeStatus.COMPLETED;
        }
    }

    public void fail(String errorMessage) {
        this.completedAt = LocalDateTime.now();
        this.status = ScrapeStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    public void incrementCreated() {
        this.recordsCreated++;
    }

    public void incrementUpdated() {
        this.recordsUpdated++;
    }

    public void incrementDatesSucceeded() {
        this.datesSucceeded++;
    }

    public void incrementDatesFailed() {
        this.datesFailed++;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getSeasonYear() {
        return seasonYear;
    }

    public void setSeasonYear(Integer seasonYear) {
        this.seasonYear = seasonYear;
    }

    public ScrapeType getScrapeType() {
        return scrapeType;
    }

    public void setScrapeType(ScrapeType scrapeType) {
        this.scrapeType = scrapeType;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public ScrapeStatus getStatus() {
        return status;
    }

    public void setStatus(ScrapeStatus status) {
        this.status = status;
    }

    public Integer getRecordsCreated() {
        return recordsCreated;
    }

    public void setRecordsCreated(Integer recordsCreated) {
        this.recordsCreated = recordsCreated;
    }

    public Integer getRecordsUpdated() {
        return recordsUpdated;
    }

    public void setRecordsUpdated(Integer recordsUpdated) {
        this.recordsUpdated = recordsUpdated;
    }

    public Integer getDatesSucceeded() {
        return datesSucceeded;
    }

    public void setDatesSucceeded(Integer datesSucceeded) {
        this.datesSucceeded = datesSucceeded;
    }

    public Integer getDatesFailed() {
        return datesFailed;
    }

    public void setDatesFailed(Integer datesFailed) {
        this.datesFailed = datesFailed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScrapeBatch that = (ScrapeBatch) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScrapeBatch{" +
                "id=" + id +
                ", seasonYear=" + seasonYear +
                ", scrapeType=" + scrapeType +
                ", status=" + status +
                '}';
    }
}
