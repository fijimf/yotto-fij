package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito test — verifies AsyncScrapeService correctly delegates to the
 * orchestrator and swallows exceptions so the async task pool isn't poisoned.
 * The @Async wiring is Spring-level config, not behavior of this class.
 */
@ExtendWith(MockitoExtension.class)
class AsyncScrapeServiceTest {

    @Mock private ScrapeOrchestrator orchestrator;
    @Mock private ScrapeScheduler scheduler;

    private AsyncScrapeService service;

    @BeforeEach
    void setUp() {
        service = new AsyncScrapeService(orchestrator, scheduler);
    }

    @Test
    void scrapeFullSeasonAsync_delegatesWithManualSource() {
        service.scrapeFullSeasonAsync(2025);
        verify(orchestrator).scrapeFullSeason(2025, ScrapeBatch.Source.MANUAL);
    }

    @Test
    void scrapeFullSeasonAsync_withExplicitSource_propagated() {
        service.scrapeFullSeasonAsync(2025, ScrapeBatch.Source.SCHEDULED);
        verify(orchestrator).scrapeFullSeason(2025, ScrapeBatch.Source.SCHEDULED);
    }

    @Test
    void scrapeFullSeasonAsync_orchestratorThrows_exceptionSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(orchestrator).scrapeFullSeason(2025, ScrapeBatch.Source.MANUAL);

        // Should NOT propagate — async exceptions would otherwise be silently lost.
        // The method logs and returns normally.
        service.scrapeFullSeasonAsync(2025);

        verify(orchestrator).scrapeFullSeason(2025, ScrapeBatch.Source.MANUAL);
    }

    @Test
    void scrapeCurrentSeasonAsync_delegates() {
        service.scrapeCurrentSeasonAsync(2025);
        verify(orchestrator).scrapeCurrentSeason(2025);
    }

    @Test
    void scrapeCurrentSeasonAsync_swallowsExceptions() {
        doThrow(new RuntimeException("boom")).when(orchestrator).scrapeCurrentSeason(2025);
        service.scrapeCurrentSeasonAsync(2025);
        verify(orchestrator).scrapeCurrentSeason(2025);
    }

    @Test
    void backfillOddsAsync_delegates() {
        service.backfillOddsAsync(2025);
        verify(orchestrator).backfillOdds(2025);
    }

    @Test
    void backfillOddsAsync_swallowsExceptions() {
        doThrow(new RuntimeException("x")).when(orchestrator).backfillOdds(2025);
        service.backfillOddsAsync(2025);
        verify(orchestrator).backfillOdds(2025);
    }

    @Test
    void backfillGameStatsAsync_delegates() {
        service.backfillGameStatsAsync(2025);
        verify(orchestrator).backfillGameStats(2025);
    }

    @Test
    void scrapeTeamsAsync_delegates() {
        service.scrapeTeamsAsync(2025);
        verify(orchestrator).scrapeTeams(2025);
    }

    @Test
    void calculateStatsAsync_delegates() {
        service.calculateStatsAsync(2025);
        verify(orchestrator).calculateStats(2025);
    }

    @Test
    void calculateTimeSeriesAsync_delegates() {
        service.calculateTimeSeriesAsync(2025);
        verify(orchestrator).calculateTimeSeries(2025);
    }

    @Test
    void calculatePowerRatingsAsync_delegates() {
        service.calculatePowerRatingsAsync(2025);
        verify(orchestrator).calculatePowerRatings(2025);
    }

    @Test
    void runScheduledCycleAsync_invokesSchedulerWithScheduledSource() {
        when(scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED)).thenReturn(true);
        service.runScheduledCycleAsync();
        verify(scheduler).tryRunNow(ScrapeBatch.Source.SCHEDULED);
    }

    @Test
    void runScheduledCycleAsync_schedulerSaysAlreadyRunning_doesNotThrow() {
        when(scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED)).thenReturn(false);
        // Just verify no exception escapes when the scheduler returns false
        service.runScheduledCycleAsync();
        verify(scheduler).tryRunNow(ScrapeBatch.Source.SCHEDULED);
        assertThat(true).isTrue();
    }
}
