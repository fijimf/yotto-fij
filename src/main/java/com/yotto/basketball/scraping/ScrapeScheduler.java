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

    /** Which orchestrator cycle a run performs. */
    public enum Mode { FULL, RECENT }

    @Scheduled(cron = "${espn.scraping.schedule:0 0 */12 * * *}")
    public void scheduledScrape() {
        tryRunNow(ScrapeBatch.Source.SCHEDULED);
    }

    /**
     * Light scores-only cycle (see {@link ScrapeOrchestrator#scrapeRecentScores}).
     * Disabled unless {@code espn.scraping.recent-schedule} is set; shares the
     * in-flight lock with the full cycle, so it simply skips if one is running.
     */
    @Scheduled(cron = "${espn.scraping.recent-schedule:-}")
    public void scheduledRecentScrape() {
        tryRunNow(ScrapeBatch.Source.SCHEDULED, Mode.RECENT);
    }

    /**
     * Runs one full re-scrape cycle over every auto-refresh season, but only
     * if no other cycle is currently in flight. Returns true if the cycle
     * was actually invoked. Shared by the @Scheduled cron and the admin
     * "Run now" button so both paths are bounded by the same lock.
     */
    public boolean tryRunNow(ScrapeBatch.Source source) {
        return tryRunNow(source, Mode.FULL);
    }

    public boolean tryRunNow(ScrapeBatch.Source source, Mode mode) {
        if (!running.compareAndSet(false, true)) {
            log.info("Scrape cycle ({}) skipped - another scrape is already running", mode);
            return false;
        }
        try {
            runCycle(source, mode);
            return true;
        } finally {
            running.set(false);
        }
    }

    private void runCycle(ScrapeBatch.Source source, Mode mode) {
        List<Season> seasons = seasonRepository.findByAutoRefreshTrueOrderByYearDesc();
        if (seasons.isEmpty()) {
            log.info("Scrape cycle skipped - no seasons flagged for auto-refresh");
            return;
        }

        log.info("Scrape cycle ({}, {}) starting for {} season(s): {}",
                source, mode, seasons.size(), seasons.stream().map(Season::getYear).toList());

        for (Season season : seasons) {
            try {
                if (mode == Mode.RECENT) {
                    orchestrator.scrapeRecentScores(season.getYear(), source);
                } else {
                    log.info("Scrape cycle: re-scraping season {}", season.getYear());
                    orchestrator.scrapeCurrentSeason(season.getYear(), source);
                }
            } catch (Exception e) {
                log.error("Scrape cycle ({}) failed for season {}", mode, season.getYear(), e);
            }
        }

        log.info("Scrape cycle ({}, {}) completed", source, mode);
    }
}
