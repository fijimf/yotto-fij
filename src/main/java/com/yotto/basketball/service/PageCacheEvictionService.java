package com.yotto.basketball.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Drops every page-data cache after the data underneath it changes: the end of
 * a scrape's stats-calc block, an admin evaluation (re)build, and an ML bundle
 * reload all call {@link #evictAll()}. Also clears the two pre-existing
 * hand-rolled caches (season wrap, stat predictiveness) that previously had no
 * production eviction call site.
 */
@Service
public class PageCacheEvictionService {

    private static final Logger log = LoggerFactory.getLogger(PageCacheEvictionService.class);

    private final CacheManager cacheManager;
    private final SeasonWrapService seasonWrapService;
    private final StatPredictivenessService statPredictivenessService;

    public PageCacheEvictionService(CacheManager cacheManager,
                                    SeasonWrapService seasonWrapService,
                                    StatPredictivenessService statPredictivenessService) {
        this.cacheManager = cacheManager;
        this.seasonWrapService = seasonWrapService;
        this.statPredictivenessService = statPredictivenessService;
    }

    public void evictAll() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        seasonWrapService.clearCache();
        statPredictivenessService.clearCache();
        log.info("Page caches evicted");
    }
}
