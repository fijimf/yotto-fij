package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScrapeSchedulerTest {

    @Mock private ScrapeOrchestrator orchestrator;
    @Mock private SeasonRepository seasonRepository;

    private ScrapeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ScrapeScheduler(orchestrator, seasonRepository);
    }

    private Season mkSeason(int year) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(LocalDate.of(year - 1, 11, 1));
        s.setEndDate(LocalDate.of(year, 4, 30));
        s.setAutoRefresh(true);
        return s;
    }

    @Test
    void scheduledScrape_invokesScrapeCurrentSeasonForEveryAutoRefreshSeasonInOrder() {
        Season s2026 = mkSeason(2026);
        Season s2025 = mkSeason(2025);
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(s2026, s2025));

        scheduler.scheduledScrape();

        InOrder order = inOrder(orchestrator);
        order.verify(orchestrator).scrapeCurrentSeason(2026, ScrapeBatch.Source.SCHEDULED);
        order.verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);
    }

    @Test
    void scheduledScrape_noAutoRefreshSeasons_doesNothing() {
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc()).thenReturn(List.of());

        scheduler.scheduledScrape();

        verifyNoInteractions(orchestrator);
    }

    @Test
    void scheduledScrape_oneSeasonFails_otherSeasonsStillProcessed() {
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(mkSeason(2026), mkSeason(2025)));
        doThrow(new RuntimeException("season 2026 boom"))
                .when(orchestrator).scrapeCurrentSeason(2026, ScrapeBatch.Source.SCHEDULED);

        scheduler.scheduledScrape();

        verify(orchestrator).scrapeCurrentSeason(2026, ScrapeBatch.Source.SCHEDULED);
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);
    }

    @Test
    void tryRunNow_returnsTrueWhenNotRunning() {
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(mkSeason(2025)));

        boolean ran = scheduler.tryRunNow(ScrapeBatch.Source.MANUAL);

        assertThat(ran).isTrue();
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.MANUAL);
    }

    @Test
    void tryRunNow_secondConcurrentCallReturnsFalseAndDoesNotInvokeOrchestrator() throws Exception {
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun  = new CountDownLatch(1);

        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(mkSeason(2025)));
        // The first call will block inside orchestrator until released; the second
        // concurrent call must return false because the running flag is set.
        AtomicInteger orchestratorCalls = new AtomicInteger(0);
        doAnswer(inv -> {
            orchestratorCalls.incrementAndGet();
            firstRunStarted.countDown();
            releaseFirstRun.await(5, TimeUnit.SECONDS);
            return null;
        }).when(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            var future = exec.submit(() -> scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED));

            // Wait until the first run is genuinely inside orchestrator
            firstRunStarted.await(5, TimeUnit.SECONDS);

            // Second invocation while the first is still running must short-circuit
            boolean secondRan = scheduler.tryRunNow(ScrapeBatch.Source.MANUAL);
            assertThat(secondRan).isFalse();

            releaseFirstRun.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(orchestratorCalls.get()).isEqualTo(1);
        } finally {
            releaseFirstRun.countDown();
            exec.shutdownNow();
        }
    }

    @Test
    void tryRunNow_releasesLockAfterCompletion() {
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(mkSeason(2025)));

        boolean first  = scheduler.tryRunNow(ScrapeBatch.Source.MANUAL);
        boolean second = scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.MANUAL);
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);
    }

    @Test
    void tryRunNow_releasesLockEvenWhenOrchestratorThrows() {
        when(seasonRepository.findByAutoRefreshTrueOrderByYearDesc())
                .thenReturn(List.of(mkSeason(2025)));
        doThrow(new RuntimeException("first fails"))
                .when(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);

        scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED);
        // After the lock release, a follow-up tryRunNow should still acquire.
        boolean second = scheduler.tryRunNow(ScrapeBatch.Source.MANUAL);

        assertThat(second).isTrue();
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.SCHEDULED);
        verify(orchestrator).scrapeCurrentSeason(2025, ScrapeBatch.Source.MANUAL);
    }
}
