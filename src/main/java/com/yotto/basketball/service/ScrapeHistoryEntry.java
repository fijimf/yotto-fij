package com.yotto.basketball.service;

import com.yotto.basketball.entity.ScrapeBatch;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A row in the admin Scrape History table. Either a single ad-hoc batch
 * (pipelineRunId == null, children.size() == 1) or a pipeline run
 * grouping multiple child batches under one parent UUID.
 */
public record ScrapeHistoryEntry(
        UUID pipelineRunId,
        Integer seasonYear,
        ScrapeBatch.Source source,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        ScrapeBatch.ScrapeStatus status,
        int recordsCreated,
        int recordsUpdated,
        int datesSucceeded,
        int datesFailed,
        String currentStep,
        Integer progressDone,
        Integer progressTotal,
        List<ScrapeBatch> children
) {

    public boolean isPipeline() {
        return pipelineRunId != null;
    }

    public Integer progressPercent() {
        if (progressDone == null || progressTotal == null || progressTotal == 0) return null;
        return (int) Math.round(progressDone * 100.0 / progressTotal);
    }

    public static ScrapeHistoryEntry fromStandalone(ScrapeBatch b) {
        Integer done = b.getDatesSucceeded() + b.getDatesFailed();
        return new ScrapeHistoryEntry(
                null,
                b.getSeasonYear(),
                b.getSource(),
                b.getStartedAt(),
                b.getCompletedAt(),
                b.getStatus(),
                b.getRecordsCreated() == null ? 0 : b.getRecordsCreated(),
                b.getRecordsUpdated() == null ? 0 : b.getRecordsUpdated(),
                b.getDatesSucceeded() == null ? 0 : b.getDatesSucceeded(),
                b.getDatesFailed() == null ? 0 : b.getDatesFailed(),
                b.getCurrentStep(),
                done,
                b.getProgressTotal(),
                List.of(b)
        );
    }

    public static ScrapeHistoryEntry fromPipeline(UUID pipelineRunId, List<ScrapeBatch> rawChildren) {
        List<ScrapeBatch> children = rawChildren.stream()
                .sorted(Comparator.comparing(
                        b -> b.getPipelineStepOrder() == null ? Integer.MAX_VALUE : b.getPipelineStepOrder()))
                .toList();

        ScrapeBatch first = children.get(0);

        LocalDateTime earliestStart = children.stream()
                .map(ScrapeBatch::getStartedAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);

        boolean anyRunning = children.stream().anyMatch(b -> b.getStatus() == ScrapeBatch.ScrapeStatus.RUNNING);
        LocalDateTime latestComplete = anyRunning ? null : children.stream()
                .map(ScrapeBatch::getCompletedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);

        ScrapeBatch.ScrapeStatus aggStatus = aggregateStatus(children);

        ScrapeBatch active = children.stream()
                .filter(b -> b.getStatus() == ScrapeBatch.ScrapeStatus.RUNNING)
                .findFirst()
                .orElse(children.get(children.size() - 1));

        Integer done = active.getDatesSucceeded() == null ? 0
                : active.getDatesSucceeded() + (active.getDatesFailed() == null ? 0 : active.getDatesFailed());

        int sumCreated = children.stream().mapToInt(b -> b.getRecordsCreated() == null ? 0 : b.getRecordsCreated()).sum();
        int sumUpdated = children.stream().mapToInt(b -> b.getRecordsUpdated() == null ? 0 : b.getRecordsUpdated()).sum();
        int sumDatesOk = children.stream().mapToInt(b -> b.getDatesSucceeded() == null ? 0 : b.getDatesSucceeded()).sum();
        int sumDatesFail = children.stream().mapToInt(b -> b.getDatesFailed() == null ? 0 : b.getDatesFailed()).sum();

        return new ScrapeHistoryEntry(
                pipelineRunId,
                first.getSeasonYear(),
                first.getSource(),
                earliestStart,
                latestComplete,
                aggStatus,
                sumCreated,
                sumUpdated,
                sumDatesOk,
                sumDatesFail,
                active.getCurrentStep(),
                done,
                active.getProgressTotal(),
                children
        );
    }

    private static ScrapeBatch.ScrapeStatus aggregateStatus(List<ScrapeBatch> children) {
        boolean anyRunning = false;
        boolean anyFailed = false;
        boolean anyPartial = false;
        for (ScrapeBatch b : children) {
            switch (b.getStatus()) {
                case RUNNING -> anyRunning = true;
                case FAILED -> anyFailed = true;
                case PARTIAL -> anyPartial = true;
                case COMPLETED -> { /* ok */ }
            }
        }
        if (anyRunning) return ScrapeBatch.ScrapeStatus.RUNNING;
        if (anyFailed) return ScrapeBatch.ScrapeStatus.FAILED;
        if (anyPartial) return ScrapeBatch.ScrapeStatus.PARTIAL;
        return ScrapeBatch.ScrapeStatus.COMPLETED;
    }
}
