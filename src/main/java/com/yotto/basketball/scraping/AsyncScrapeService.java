package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncScrapeService {

    private static final Logger log = LoggerFactory.getLogger(AsyncScrapeService.class);

    private final ScrapeOrchestrator orchestrator;
    private final ScrapeScheduler scheduler;

    public AsyncScrapeService(ScrapeOrchestrator orchestrator, ScrapeScheduler scheduler) {
        this.orchestrator = orchestrator;
        this.scheduler = scheduler;
    }

    /**
     * Fires the scheduled re-scrape cycle on demand from the admin UI.
     * Shares the scheduler's lock, so it's a no-op if the cron is already
     * running.
     */
    @Async("scrapeExecutor")
    public void runScheduledCycleAsync() {
        log.info("Async scheduled cycle kicked off manually from admin UI");
        boolean ran = scheduler.tryRunNow(ScrapeBatch.Source.SCHEDULED);
        if (!ran) {
            log.info("Manual scheduled-cycle invocation skipped — already running");
        }
    }

    @Async("scrapeExecutor")
    public void scrapeFullSeasonAsync(int seasonYear) {
        scrapeFullSeasonAsync(seasonYear, ScrapeBatch.Source.MANUAL);
    }

    @Async("scrapeExecutor")
    public void scrapeFullSeasonAsync(int seasonYear, ScrapeBatch.Source source) {
        log.info("Async full season scrape started for {} (source={})", seasonYear, source);
        try {
            orchestrator.scrapeFullSeason(seasonYear, source);
        } catch (Exception e) {
            log.error("Async full season scrape failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void scrapeCurrentSeasonAsync(int seasonYear) {
        log.info("Async current season re-scrape started for {}", seasonYear);
        try {
            orchestrator.scrapeCurrentSeason(seasonYear);
        } catch (Exception e) {
            log.error("Async current season re-scrape failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void backfillOddsAsync(int seasonYear) {
        log.info("Async odds backfill started for {}", seasonYear);
        try {
            orchestrator.backfillOdds(seasonYear);
        } catch (Exception e) {
            log.error("Async odds backfill failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void backfillGameStatsAsync(int seasonYear) {
        log.info("Async game stats backfill started for {}", seasonYear);
        try {
            orchestrator.backfillGameStats(seasonYear);
        } catch (Exception e) {
            log.error("Async game stats backfill failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void scrapeTeamsAsync(int seasonYear) {
        log.info("Async team scrape started for {}", seasonYear);
        try {
            orchestrator.scrapeTeams(seasonYear);
        } catch (Exception e) {
            log.error("Async team scrape failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void calculateStatsAsync(int seasonYear) {
        log.info("Async stats calculation started for {}", seasonYear);
        try {
            orchestrator.calculateStats(seasonYear);
        } catch (Exception e) {
            log.error("Async stats calculation failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void calculateTimeSeriesAsync(int seasonYear) {
        log.info("Async time-series calculation started for {}", seasonYear);
        try {
            orchestrator.calculateTimeSeries(seasonYear);
        } catch (Exception e) {
            log.error("Async time-series calculation failed for {}", seasonYear, e);
        }
    }

    @Async("scrapeExecutor")
    public void calculatePowerRatingsAsync(int seasonYear) {
        log.info("Async power ratings calculation started for {}", seasonYear);
        try {
            orchestrator.calculatePowerRatings(seasonYear);
        } catch (Exception e) {
            log.error("Async power ratings calculation failed for {}", seasonYear, e);
        }
    }
}
