package com.yotto.basketball.service;

import com.yotto.basketball.service.DailyStatCalculator.StatMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the standalone catalog against drift from the registry: every computed
 * stat must have display metadata, and the duplicated direction flag must agree.
 */
class StatCatalogTest {

    @Test
    void everyRegistryStatHasCompleteCatalogMetadata() {
        List<StatMeta> registry = BoxScoreStatCalculator.statMetas();

        for (StatMeta meta : registry) {
            StatCatalog.StatInfo info = StatCatalog.require(meta.name());
            assertNotNull(info.title(), () -> "missing title for " + meta.name());
            assertFalse(info.title().isBlank(), () -> "blank title for " + meta.name());
            assertNotNull(info.format(), () -> "missing format for " + meta.name());
            assertNotNull(info.category(), () -> "missing category for " + meta.name());
            assertEquals(meta.higherIsBetter(), info.higherIsBetter(),
                    () -> "higherIsBetter disagrees with registry for " + meta.name());
        }
    }

    @Test
    void catalogHasNoEntriesBeyondTheRegistry() {
        List<String> registryNames = BoxScoreStatCalculator.statMetas().stream().map(StatMeta::name).toList();
        assertEquals(registryNames.size(), StatCatalog.all().size(),
                "catalog and registry must be the same size");
        for (StatCatalog.StatInfo info : StatCatalog.all()) {
            assertEquals(true, registryNames.contains(info.name()),
                    () -> "catalog has a stat not in the registry: " + info.name());
        }
    }
}
