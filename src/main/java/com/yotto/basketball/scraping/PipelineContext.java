package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;

import java.util.UUID;

/**
 * Stamps each ScrapeBatch created by a scraper with the metadata needed to
 * (a) group child batches into a single pipeline run in the dashboard, and
 * (b) attribute the run to its trigger source (button / cron / auto-init).
 *
 * Standalone Advanced-button calls pass {@link #manual()} — pipelineRunId is
 * null so the batch is shown as an independent row.
 */
public record PipelineContext(UUID pipelineRunId, Integer stepOrder, ScrapeBatch.Source source) {

    public static PipelineContext manual() {
        return new PipelineContext(null, null, ScrapeBatch.Source.MANUAL);
    }

    public static PipelineContext standalone(ScrapeBatch.Source source) {
        return new PipelineContext(null, null, source);
    }

    /** Returns a child context within this pipeline run at the given step order. */
    public PipelineContext step(int stepOrder) {
        return new PipelineContext(pipelineRunId, stepOrder, source);
    }
}
