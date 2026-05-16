package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScrapeScheduler.class);

    private final ScrapeOrchestrator orchestrator;
    private final SeasonRepository seasonRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScrapeScheduler(ScrapeOrchestrator orchestrator, SeasonRepository seasonRepository) {
        this.orchestrator = orchestrator;
        this.seasonRepository = seasonRepository;
    }

    @Scheduled(cron = "${espn.scraping.schedule:0 0 */12 * * *}")
    public void scheduledScrape() {
        tryRunNow(ScrapeBatch.Source.SCHEDULED);
    }

    /**
     * Runs one full re-scrape cycle over every auto-refresh season, but only
     * if no other cycle is currently in flight. Returns true if the cycle
     * was actually invoked. Shared by the @Scheduled cron and the admin
     * "Run now" button so both paths are bounded by the same lock.
     */
    public boolean tryRunNow(ScrapeBatch.Source source) {
        if (!running.compareAndSet(false, true)) {
            log.info("Scrape cycle skipped - another scrape is already running");
            return false;
        }
        try {
            runCycle(source);
            return true;
        } finally {
            running.set(false);
        }
    }

    private void runCycle(ScrapeBatch.Source source) {
        List<Season> seasons = seasonRepository.findByAutoRefreshTrueOrderByYearDesc();
        if (seasons.isEmpty()) {
            log.info("Scrape cycle skipped - no seasons flagged for auto-refresh");
            return;
        }

        log.info("Scrape cycle ({}) starting for {} season(s): {}",
                source, seasons.size(), seasons.stream().map(Season::getYear).toList());

        for (Season season : seasons) {
            try {
                log.info("Scrape cycle: re-scraping season {}", season.getYear());
                orchestrator.scrapeCurrentSeason(season.getYear(), source);
            } catch (Exception e) {
                log.error("Scrape cycle failed for season {}", season.getYear(), e);
            }
        }

        log.info("Scrape cycle ({}) completed", source);
    }
}
