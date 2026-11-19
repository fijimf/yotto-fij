package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.ScrapeBatch.ScrapeStatus;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Marks scrape batches that were RUNNING when the previous process died as FAILED.
 *
 * <p>A batch only ever leaves RUNNING through the thread that started it, so a
 * restart (deploy, crash, OOM) mid-scrape leaves the row RUNNING forever and the
 * admin dashboard shows a phantom in-progress job. On startup nothing can be
 * legitimately running yet, so any RUNNING batch that started before this bean was
 * constructed is an orphan. The cutoff matters: scheduled polls may fire before
 * {@link ApplicationRunner}s execute, and those must be left alone.
 *
 * <p>Uses {@code LocalDateTime.now()} rather than the Eastern {@code Clock} bean
 * because {@link ScrapeBatch#start} stamps {@code startedAt} the same way; mixing
 * the two would shift the cutoff by the UTC/Eastern offset.
 */
@Component
public class OrphanedScrapeBatchCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedScrapeBatchCleanup.class);
    static final String ERROR_MESSAGE = "Orphaned: application restarted while this batch was running";

    private final ScrapeBatchRepository repository;
    private final LocalDateTime startupTime;

    public OrphanedScrapeBatchCleanup(ScrapeBatchRepository repository) {
        this.repository = repository;
        this.startupTime = LocalDateTime.now();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ScrapeBatch> orphans = repository.findByStatusAndStartedAtBefore(ScrapeStatus.RUNNING, startupTime);
        if (orphans.isEmpty()) {
            return;
        }
        for (ScrapeBatch batch : orphans) {
            batch.fail(ERROR_MESSAGE);
        }
        repository.saveAll(orphans);
        log.warn("Marked {} orphaned RUNNING scrape batch(es) as FAILED: {}", orphans.size(),
                orphans.stream().map(b -> b.getId() + ":" + b.getScrapeType()).toList());
    }
}
