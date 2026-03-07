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
        log.info("Calculating power ratings for season {}", seasonYear);
        masseyRatingService.calculateAndStoreForSeason(seasonYear);
        bradleyTerryRatingService.calculateAndStoreForSeason(seasonYear);
        log.info("Power ratings complete for season {}", seasonYear);
    }
}
