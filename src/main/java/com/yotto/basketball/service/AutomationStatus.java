package com.yotto.basketball.service;

import com.yotto.basketball.entity.ScrapeBatch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only summary of the scheduled-scrape automation, surfaced in the
 * Automation panel on the admin dashboard.
 */
public record AutomationStatus(
        String cron,
        LocalDateTime nextFireAt,
        ScrapeBatch lastFired,
        List<Integer> seasonsInScope
) {
    public boolean hasSeasonsInScope() {
        return seasonsInScope != null && !seasonsInScope.isEmpty();
    }

    public boolean hasLastFired() {
        return lastFired != null;
    }

    public Duration lastFiredDuration() {
        if (lastFired == null || lastFired.getStartedAt() == null) return null;
        LocalDateTime end = lastFired.getCompletedAt() != null
                ? lastFired.getCompletedAt() : LocalDateTime.now();
        return Duration.between(lastFired.getStartedAt(), end);
    }
}
