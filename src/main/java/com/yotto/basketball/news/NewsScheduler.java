package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.ScrapeBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven news polling, separate from the game-scrape scheduler (news
 * polls every ~30 min, games every 12 h). Does nothing while news.enabled=false.
 */
@Component
public class NewsScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduler.class);

    private final NewsProperties properties;
    private final NewsScrapeService newsScrapeService;

    public NewsScheduler(NewsProperties properties, NewsScrapeService newsScrapeService) {
        this.properties = properties;
        this.newsScrapeService = newsScrapeService;
    }

    @Scheduled(cron = "${news.schedule:0 */30 * * * *}")
    public void scheduledPoll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            newsScrapeService.pollAll(ScrapeBatch.Source.SCHEDULED);
        } catch (Exception e) {
            log.error("Scheduled news poll failed", e);
        }
    }
}
