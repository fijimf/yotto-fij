package com.yotto.basketball.scraping;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.ScrapeBatch.ScrapeStatus;
import com.yotto.basketball.entity.ScrapeBatch.ScrapeType;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OrphanedScrapeBatchCleanupTest extends BaseIntegrationTest {

    @Autowired OrphanedScrapeBatchCleanup cleanup;
    @Autowired ScrapeBatchRepository repository;

    @Test
    void run_failsRunningBatchesStartedBeforeStartup_andLeavesEverythingElseAlone() {
        // The cleanup bean was constructed when the test context started, so a batch
        // started an hour ago predates startup and one started a minute from now postdates it.
        ScrapeBatch orphan = ScrapeBatch.start(2027, ScrapeType.NEWS);
        orphan.setStartedAt(LocalDateTime.now().minusHours(1));
        ScrapeBatch live = ScrapeBatch.start(2027, ScrapeType.GAMES);
        live.setStartedAt(LocalDateTime.now().plusMinutes(1));
        ScrapeBatch done = ScrapeBatch.start(2027, ScrapeType.STANDINGS);
        done.setStartedAt(LocalDateTime.now().minusHours(2));
        done.complete();
        repository.saveAll(java.util.List.of(orphan, live, done));

        cleanup.run(null);

        ScrapeBatch orphanAfter = repository.findById(orphan.getId()).orElseThrow();
        assertThat(orphanAfter.getStatus()).isEqualTo(ScrapeStatus.FAILED);
        assertThat(orphanAfter.getCompletedAt()).isNotNull();
        assertThat(orphanAfter.getErrorMessage()).isEqualTo(OrphanedScrapeBatchCleanup.ERROR_MESSAGE);

        assertThat(repository.findById(live.getId()).orElseThrow().getStatus()).isEqualTo(ScrapeStatus.RUNNING);
        assertThat(repository.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(ScrapeStatus.COMPLETED);
    }

    @Test
    void run_isANoOpWhenNothingIsOrphaned() {
        ScrapeBatch done = ScrapeBatch.start(2027, ScrapeType.GAMES);
        done.complete();
        repository.save(done);

        cleanup.run(null);

        assertThat(repository.findByStatus(ScrapeStatus.FAILED)).isEmpty();
        assertThat(repository.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(ScrapeStatus.COMPLETED);
    }
}
