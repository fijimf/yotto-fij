package com.yotto.basketball.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PowerRatingService {

    private static final Logger log = LoggerFactory.getLogger(PowerRatingService.class);

    private final MasseyRatingService masseyRatingService;
    private final BradleyTerryRatingService bradleyTerryRatingService;

    public PowerRatingService(MasseyRatingService masseyRatingService,
                              BradleyTerryRatingService bradleyTerryRatingService) {
        this.masseyRatingService = masseyRatingService;
        this.bradleyTerryRatingService = bradleyTerryRatingService;
    }

    public void calculateAndStoreForSeason(int seasonYear) {
        calculateAndStoreForSeason(seasonYear, null);
    }

    /** @param fromDate watermark — only snapshots dated {@code >= fromDate} are rewritten; null = full. */
    public void calculateAndStoreForSeason(int seasonYear, java.time.LocalDate fromDate) {
        log.info("Calculating power ratings for season {}", seasonYear);
        masseyRatingService.calculateAndStoreForSeason(seasonYear, fromDate);
        bradleyTerryRatingService.calculateAndStoreForSeason(seasonYear, fromDate);
        log.info("Power ratings complete for season {}", seasonYear);
    }

    /** Shared-data path: the orchestrator loads the season's games once for all calculators. */
    public void calculateAndStoreForSeason(SeasonGameData data, java.time.LocalDate fromDate) {
        log.info("Calculating power ratings for season {}", data.season().getYear());
        masseyRatingService.calculateAndStoreForSeason(data, fromDate);
        bradleyTerryRatingService.calculateAndStoreForSeason(data, fromDate);
        log.info("Power ratings complete for season {}", data.season().getYear());
    }
}
