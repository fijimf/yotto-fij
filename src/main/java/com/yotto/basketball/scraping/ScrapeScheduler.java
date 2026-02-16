package com.yotto.basketball.scraping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScrapeScheduler.class);

    private final ScrapeOrchestrator orchestrator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScrapeScheduler(ScrapeOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${espn.scraping.schedule:0 0 */12 * * *}")
    public void scheduledScrape() {
        if (!running.compareAndSet(false, true)) {
            log.info("Scheduled scrape skipped - another scrape is already running");
            return;
        }

        try {
            int currentSeasonYear = determineCurrentSeasonYear();
            log.info("Scheduled scrape starting for season {}", currentSeasonYear);
            orchestrator.scrapeCurrentSeason(currentSeasonYear);
            log.info("Scheduled scrape completed for season {}", currentSeasonYear);
        } catch (Exception e) {
            log.error("Scheduled scrape failed", e);
        } finally {
            running.set(false);
        }
    }

    private int determineCurrentSeasonYear() {
        LocalDate today = LocalDate.now();
        // If we're in Nov-Dec, the season year is next year (e.g., Nov 2024 -> season 2025)
        // If we're in Jan-Oct, the season year is this year (e.g., Feb 2025 -> season 2025)
        if (today.getMonthValue() >= 11) {
            return today.getYear() + 1;
        }
        return today.getYear();
    }
}
