package com.yotto.basketball.controller;

import com.yotto.basketball.service.BoxScoreStatCalculator;
import com.yotto.basketball.service.DailyStatCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test — guards the presentation catalog against the calc registry. */
class TeamStatDisplayTest {

    @Test
    void everyCalculatedStatHasACatalogEntry() {
        for (DailyStatCalculator.StatMeta meta : BoxScoreStatCalculator.statMetas()) {
            assertThat(TeamStatDisplay.forStat(meta.name()))
                    .as("missing TeamStatDisplay entry for calc stat '%s'", meta.name())
                    .isNotNull();
        }
    }

    @Test
    void formatsRenderSampleValues() {
        assertThat(TeamStatDisplay.Format.PERCENT_1.render(0.532)).isEqualTo("53.2%");
        assertThat(TeamStatDisplay.Format.DECIMAL_1.render(108.45)).isEqualTo("108.5");
        assertThat(TeamStatDisplay.Format.DECIMAL_2.render(1.453)).isEqualTo("1.45");
        assertThat(TeamStatDisplay.Format.RAW.render(0.5)).isEqualTo("0.50");
    }

    @Test
    void catalogedStatFormatsViaItsFormat() {
        assertThat(TeamStatDisplay.forStat("ts_pct").format(0.532)).isEqualTo("53.2%");
        assertThat(TeamStatDisplay.forStat("ast_to_ratio").format(1.453)).isEqualTo("1.45");
        assertThat(TeamStatDisplay.forStat("pf_per_game").format(16.34)).isEqualTo("16.3");
    }

    @Test
    void unknownStatIsUncatalogued() {
        assertThat(TeamStatDisplay.forStat("not_a_real_stat")).isNull();
    }
}
