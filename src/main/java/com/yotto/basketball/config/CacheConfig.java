package com.yotto.basketball.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process page-data caches for the expensive read paths (load testing showed
 * /models/compare, /stats/predictor detail, and the team page collapsing the
 * 2-core box at 5-20 concurrent requests). Everything cached here is anonymous-
 * identical and derived from data that only changes after scrapes, so entries
 * are dropped by {@link com.yotto.basketball.service.PageCacheEvictionService}
 * at the end of every stats-calc/evaluation run; the expireAfterWrite below is
 * only a backstop. Values include Spring Data projection proxies, so these
 * caches must stay in-process (never a serializing store).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String MODEL_COMPARE = "modelCompare";
    public static final String MODEL_ABOUT = "modelAbout";
    public static final String MODEL_HEADLINES = "modelHeadlines";
    public static final String STAT_PAGE = "statPage";
    public static final String TEAM_SCHEDULE = "teamSchedule";
    public static final String TEAM_STAT_PANEL = "teamStatPanel";

    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                MODEL_COMPARE, MODEL_ABOUT, MODEL_HEADLINES, STAT_PAGE,
                TEAM_SCHEDULE, TEAM_STAT_PANEL);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofHours(6)));
        return manager;
    }
}
