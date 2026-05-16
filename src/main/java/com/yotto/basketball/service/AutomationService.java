package com.yotto.basketball.service;

import com.yotto.basketball.config.ScrapingProperties;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    private final ScrapingProperties scrapingProperties;
    private final SeasonRepository seasonRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public AutomationService(ScrapingProperties scrapingProperties,
                             SeasonRepository seasonRepository,
                             ScrapeBatchRepository scrapeBatchRepository) {
        this.scrapingProperties = scrapingProperties;
        this.seasonRepository = seasonRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    public AutomationStatus getStatus() {
        String cron = scrapingProperties.getSchedule();

        LocalDateTime nextFire = null;
        try {
            nextFire = CronExpression.parse(cron).next(LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression in espn.scraping.schedule: {}", cron, e);
        }

        ScrapeBatch lastFired = scrapeBatchRepository
                .findFirstBySourceOrderByStartedAtDesc(ScrapeBatch.Source.SCHEDULED)
                .orElse(null);

        List<Integer> seasonsInScope = seasonRepository.findByAutoRefreshTrueOrderByYearDesc()
                .stream().map(Season::getYear).toList();

        return new AutomationStatus(cron, nextFire, lastFired, seasonsInScope);
    }
}
