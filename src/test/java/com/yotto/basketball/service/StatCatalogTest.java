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

    /** The union of every DailyStatCalculator's registry — must mirror createCalculators(). */
    private static List<StatMeta> allRegistryMetas() {
        List<StatMeta> all = new java.util.ArrayList<>(ResultsStatCalculator.statMetas());
        all.addAll(BoxScoreStatCalculator.statMetas());
        return all;
    }

    @Test
    void everyRegistryStatHasCompleteCatalogMetadata() {
        List<StatMeta> registry = allRegistryMetas();

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

    /**
     * season_population_stats deletes are stat-name-scoped and the table is shared
     * between the wide-snapshot service and the long-format calculators — a name
     * collision would make the two writers clobber each other's rows.
     */
    @Test
    void calculatorStatNamesDisjointFromWideSnapshotPopulationNames() {
        List<String> calculatorNames = allRegistryMetas().stream().map(StatMeta::name).toList();
        for (String wideName : StatisticsTimeSeriesService.STAT_NAMES) {
            assertFalse(calculatorNames.contains(wideName),
                    () -> "calculator stat name collides with wide population-stat name: " + wideName);
        }
    }

    @Test
    void catalogHasNoEntriesBeyondTheRegistry() {
        List<String> registryNames = allRegistryMetas().stream().map(StatMeta::name).toList();
        assertEquals(registryNames.size(), StatCatalog.all().size(),
                "catalog and registry must be the same size");
        for (StatCatalog.StatInfo info : StatCatalog.all()) {
            assertEquals(true, registryNames.contains(info.name()),
                    () -> "catalog has a stat not in the registry: " + info.name());
        }
    }
}
