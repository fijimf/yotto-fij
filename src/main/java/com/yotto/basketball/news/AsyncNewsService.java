package com.yotto.basketball.news;

import com.yotto.basketball.entity.ScrapeBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** @Async wrappers for admin-triggered news operations (same executor as game scrapes). */
@Service
public class AsyncNewsService {

    private static final Logger log = LoggerFactory.getLogger(AsyncNewsService.class);

    private final NewsScrapeService newsScrapeService;
    private final NewsAdminService newsAdminService;

    public AsyncNewsService(NewsScrapeService newsScrapeService, NewsAdminService newsAdminService) {
        this.newsScrapeService = newsScrapeService;
        this.newsAdminService = newsAdminService;
    }

    @Async("scrapeExecutor")
    public void pollAllAsync() {
        log.info("Async news poll kicked off from admin UI");
        newsScrapeService.pollAll(ScrapeBatch.Source.MANUAL);
    }

    @Async("scrapeExecutor")
    public void retagAsync(int days) {
        log.info("Async news retag kicked off from admin UI ({} days)", days);
        newsAdminService.retag(days);
    }
}
