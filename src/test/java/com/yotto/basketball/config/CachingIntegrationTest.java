package com.yotto.basketball.config;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.service.ModelAboutService;
import com.yotto.basketball.service.PageCacheEvictionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Smoke test for the page-data cache wiring: @Cacheable actually caches
 * (same instance back on a second call) and PageCacheEvictionService drops
 * every cache (fresh instance afterwards).
 */
class CachingIntegrationTest extends BaseIntegrationTest {

    @Autowired ModelAboutService modelAboutService;
    @Autowired PageCacheEvictionService evictionService;

    @Test
    void headlinesAreCachedUntilEvicted() {
        // BaseIntegrationTest wipes tables, not caches — start clean.
        evictionService.evictAll();

        Map<String, ModelAboutService.Headline> first = modelAboutService.headlines();
        Map<String, ModelAboutService.Headline> second = modelAboutService.headlines();
        assertSame(first, second, "second call should be served from the cache");

        evictionService.evictAll();
        Map<String, ModelAboutService.Headline> third = modelAboutService.headlines();
        assertNotSame(first, third, "eviction should force a recompute");
    }
}
