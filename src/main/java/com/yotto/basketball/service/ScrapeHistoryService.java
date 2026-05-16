package com.yotto.basketball.service;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScrapeHistoryService {

    private final ScrapeBatchRepository scrapeBatchRepository;

    public ScrapeHistoryService(ScrapeBatchRepository scrapeBatchRepository) {
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    /**
     * Returns the most recent scrape history as a list of entries. Children of
     * a pipeline run are collapsed into one entry; standalone batches each get
     * their own entry. Entries are sorted by startedAt descending.
     */
    public List<ScrapeHistoryEntry> recentEntries() {
        // Pull more raw batches than we plan to display, so a pipeline run
        // that produced 6 children doesn't crowd out earlier history.
        List<ScrapeBatch> recent = scrapeBatchRepository.findTop20ByOrderByStartedAtDesc();

        Map<UUID, List<ScrapeBatch>> byRun = new LinkedHashMap<>();
        List<ScrapeBatch> standalone = new ArrayList<>();
        for (ScrapeBatch b : recent) {
            if (b.getPipelineRunId() == null) {
                standalone.add(b);
            } else {
                byRun.computeIfAbsent(b.getPipelineRunId(), k -> new ArrayList<>()).add(b);
            }
        }

        List<ScrapeHistoryEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, List<ScrapeBatch>> e : byRun.entrySet()) {
            entries.add(ScrapeHistoryEntry.fromPipeline(e.getKey(), e.getValue()));
        }
        for (ScrapeBatch b : standalone) {
            entries.add(ScrapeHistoryEntry.fromStandalone(b));
        }

        entries.sort(Comparator.comparing(ScrapeHistoryEntry::startedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return entries;
    }
}
