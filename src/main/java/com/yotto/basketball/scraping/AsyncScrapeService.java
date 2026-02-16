package com.yotto.basketball.scraping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncScrapeService {

    private static final Logger log = LoggerFactory.getLogger(AsyncScrapeService.class);

    private final ScrapeOrchestrator orchestrator;

    public AsyncScrapeService(ScrapeOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async("scrapeExecutor")
    public void scrapeFullSeasonAsync(int seasonYear) {
        log.info("Async full season scrape started for {}", seasonYear);
        try {
            orchestrator.scrapeFullSeason(seasonYear);
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
}
