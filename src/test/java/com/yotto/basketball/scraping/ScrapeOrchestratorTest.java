package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.service.PowerRatingService;
import com.yotto.basketball.service.StatisticsTimeSeriesService;
import com.yotto.basketball.service.StatsCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito test — no Spring context, no database.
 * ScrapeOrchestrator is pure call-routing logic; verifying ordering and
 * short-circuit behavior is enough.
 */
@ExtendWith(MockitoExtension.class)
class ScrapeOrchestratorTest {

    @Mock private ConferenceScraper conferenceScraper;
    @Mock private TeamScraper teamScraper;
    @Mock private StandingsScraper standingsScraper;
    @Mock private GameScraper gameScraper;
    @Mock private OddsBackfillScraper oddsBackfillScraper;
    @Mock private GameStatsScraper gameStatsScraper;
    @Mock private StatsCalculationService statsCalculationService;
    @Mock private StatisticsTimeSeriesService timeSeriesService;
    @Mock private PowerRatingService powerRatingService;

    private ScrapeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ScrapeOrchestrator(
                conferenceScraper, teamScraper, standingsScraper, gameScraper,
                oddsBackfillScraper, gameStatsScraper,
                statsCalculationService, timeSeriesService, powerRatingService);
    }

    private ScrapeBatch completed() {
        ScrapeBatch b = new ScrapeBatch();
        b.setStatus(ScrapeBatch.ScrapeStatus.COMPLETED);
        return b;
    }

    private ScrapeBatch failed() {
        ScrapeBatch b = new ScrapeBatch();
        b.setStatus(ScrapeBatch.ScrapeStatus.FAILED);
        return b;
    }

    // ── scrapeFullSeason ──────────────────────────────────────────────────────

    @Test
    void scrapeFullSeason_callsScrapersInDependencyOrder() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(teamScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(standingsScraper.scrape(eq(2025), any())).thenReturn(completed());

        orchestrator.scrapeFullSeason(2025);

        InOrder order = inOrder(conferenceScraper, teamScraper, standingsScraper,
                gameScraper, statsCalculationService, timeSeriesService,
                powerRatingService, oddsBackfillScraper, gameStatsScraper);
        order.verify(conferenceScraper).scrape(eq(2025), any());
        order.verify(teamScraper).scrape(eq(2025), any());
        order.verify(standingsScraper).scrape(eq(2025), any());
        order.verify(gameScraper).scrapeFullSeason(eq(2025), any());
        order.verify(statsCalculationService).calculateAndUpdateForSeason(2025);
        order.verify(timeSeriesService).calculateAndStoreForSeason(2025);
        order.verify(powerRatingService).calculateAndStoreForSeason(2025);
        order.verify(oddsBackfillScraper).backfill(eq(2025), any());
        order.verify(gameStatsScraper).backfill(eq(2025), any());
    }

    @Test
    void scrapeFullSeason_conferenceFailure_abortsPipeline() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(failed());

        orchestrator.scrapeFullSeason(2025);

        verify(conferenceScraper).scrape(eq(2025), any());
        verify(teamScraper, never()).scrape(eq(2025), any());
        verify(standingsScraper, never()).scrape(eq(2025), any());
        verify(gameScraper, never()).scrapeFullSeason(eq(2025), any());
        verify(statsCalculationService, never()).calculateAndUpdateForSeason(2025);
        verify(oddsBackfillScraper, never()).backfill(eq(2025), any());
    }

    @Test
    void scrapeFullSeason_teamFailure_abortsPipeline() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(teamScraper.scrape(eq(2025), any())).thenReturn(failed());

        orchestrator.scrapeFullSeason(2025);

        verify(conferenceScraper).scrape(eq(2025), any());
        verify(teamScraper).scrape(eq(2025), any());
        verify(standingsScraper, never()).scrape(eq(2025), any());
        verify(gameScraper, never()).scrapeFullSeason(eq(2025), any());
    }

    @Test
    void scrapeFullSeason_standingsFailure_doesNotAbortPipeline() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(teamScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(standingsScraper.scrape(eq(2025), any())).thenReturn(failed());

        orchestrator.scrapeFullSeason(2025);

        // Pipeline continues past standings failure
        verify(gameScraper).scrapeFullSeason(eq(2025), any());
        verify(statsCalculationService).calculateAndUpdateForSeason(2025);
        verify(oddsBackfillScraper).backfill(eq(2025), any());
        verify(gameStatsScraper).backfill(eq(2025), any());
    }

    @Test
    void scrapeFullSeason_propagatesSourceToChildScrapers() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(teamScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(standingsScraper.scrape(eq(2025), any())).thenReturn(completed());

        org.mockito.ArgumentCaptor<PipelineContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(PipelineContext.class);

        orchestrator.scrapeFullSeason(2025, ScrapeBatch.Source.SCHEDULED);

        verify(conferenceScraper).scrape(eq(2025), ctxCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(ctxCaptor.getValue().source())
                .isEqualTo(ScrapeBatch.Source.SCHEDULED);
        org.assertj.core.api.Assertions.assertThat(ctxCaptor.getValue().pipelineRunId())
                .as("All child scrapers must share the same pipeline run id").isNotNull();
    }

    @Test
    void scrapeFullSeason_allChildBatchesShareSamePipelineRunId() {
        when(conferenceScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(teamScraper.scrape(eq(2025), any())).thenReturn(completed());
        when(standingsScraper.scrape(eq(2025), any())).thenReturn(completed());

        org.mockito.ArgumentCaptor<PipelineContext> confCtx = org.mockito.ArgumentCaptor.forClass(PipelineContext.class);
        org.mockito.ArgumentCaptor<PipelineContext> teamCtx = org.mockito.ArgumentCaptor.forClass(PipelineContext.class);
        org.mockito.ArgumentCaptor<PipelineContext> standCtx = org.mockito.ArgumentCaptor.forClass(PipelineContext.class);

        orchestrator.scrapeFullSeason(2025);

        verify(conferenceScraper).scrape(eq(2025), confCtx.capture());
        verify(teamScraper).scrape(eq(2025), teamCtx.capture());
        verify(standingsScraper).scrape(eq(2025), standCtx.capture());

        java.util.UUID runId = confCtx.getValue().pipelineRunId();
        org.assertj.core.api.Assertions.assertThat(runId).isNotNull();
        org.assertj.core.api.Assertions.assertThat(teamCtx.getValue().pipelineRunId()).isEqualTo(runId);
        org.assertj.core.api.Assertions.assertThat(standCtx.getValue().pipelineRunId()).isEqualTo(runId);
        // Step order increments across child scrapers
        org.assertj.core.api.Assertions.assertThat(confCtx.getValue().stepOrder()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(teamCtx.getValue().stepOrder()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(standCtx.getValue().stepOrder()).isEqualTo(3);
    }

    // ── scrapeCurrentSeason ───────────────────────────────────────────────────

    @Test
    void scrapeCurrentSeason_skipsConferencesAndTeams() {
        orchestrator.scrapeCurrentSeason(2025);

        verify(conferenceScraper, never()).scrape(eq(2025), any());
        verify(teamScraper, never()).scrape(eq(2025), any());
        verify(standingsScraper).scrape(eq(2025), any());
        verify(gameScraper).scrapeCurrentSeason(eq(2025), any());
        verify(statsCalculationService).calculateAndUpdateForSeason(2025);
        verify(oddsBackfillScraper).backfill(eq(2025), any());
        verify(gameStatsScraper).backfill(eq(2025), any());
    }

    // ── Standalone helpers ────────────────────────────────────────────────────

    @Test
    void scrapeTeams_invokesOnlyTeamScraper() {
        orchestrator.scrapeTeams(2025);

        verify(teamScraper).scrape(2025);
        verify(conferenceScraper, never()).scrape(any(Integer.class));
        verify(standingsScraper, never()).scrape(any(Integer.class));
    }

    @Test
    void scrapeConferencesAndTeams_runsBothInOrder() {
        orchestrator.scrapeConferencesAndTeams(2025);

        InOrder order = inOrder(conferenceScraper, teamScraper);
        order.verify(conferenceScraper).scrape(2025);
        order.verify(teamScraper).scrape(2025);
    }

    @Test
    void backfillOdds_invokesOnlyOddsScraper() {
        orchestrator.backfillOdds(2025);
        verify(oddsBackfillScraper).backfill(2025);
        verify(gameStatsScraper, never()).backfill(2025);
    }

    @Test
    void backfillGameStats_invokesOnlyStatsScraper() {
        orchestrator.backfillGameStats(2025);
        verify(gameStatsScraper).backfill(2025);
        verify(oddsBackfillScraper, never()).backfill(2025);
    }

    @Test
    void calculateStats_delegatesToService() {
        orchestrator.calculateStats(2025);
        verify(statsCalculationService).calculateAndUpdateForSeason(2025);
        verify(timeSeriesService, never()).calculateAndStoreForSeason(2025);
    }

    @Test
    void calculateTimeSeries_delegatesToService() {
        orchestrator.calculateTimeSeries(2025);
        verify(timeSeriesService).calculateAndStoreForSeason(2025);
    }

    @Test
    void calculatePowerRatings_delegatesToService() {
        orchestrator.calculatePowerRatings(2025);
        verify(powerRatingService).calculateAndStoreForSeason(2025);
    }

    @Test
    void scrapeStandings_invokesOnlyStandingsScraper() {
        orchestrator.scrapeStandings(2025);
        verify(standingsScraper).scrape(2025);
    }

    @Test
    void scrapeGames_invokesOnlyGameScraper() {
        orchestrator.scrapeGames(2025);
        verify(gameScraper).scrapeFullSeason(2025);
    }
}
