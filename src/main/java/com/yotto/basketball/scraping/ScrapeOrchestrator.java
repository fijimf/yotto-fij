package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.service.StatsCalculationService;
import com.yotto.basketball.service.StatisticsTimeSeriesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ScrapeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ScrapeOrchestrator.class);

    private final ConferenceScraper conferenceScraper;
    private final TeamScraper teamScraper;
    private final StandingsScraper standingsScraper;
    private final GameScraper gameScraper;
    private final OddsBackfillScraper oddsBackfillScraper;
    private final StatsCalculationService statsCalculationService;
    private final StatisticsTimeSeriesService timeSeriesService;

    public ScrapeOrchestrator(ConferenceScraper conferenceScraper, TeamScraper teamScraper,
                              StandingsScraper standingsScraper, GameScraper gameScraper,
                              OddsBackfillScraper oddsBackfillScraper,
                              StatsCalculationService statsCalculationService,
                              StatisticsTimeSeriesService timeSeriesService) {
        this.conferenceScraper = conferenceScraper;
        this.teamScraper = teamScraper;
        this.standingsScraper = standingsScraper;
        this.gameScraper = gameScraper;
        this.oddsBackfillScraper = oddsBackfillScraper;
        this.statsCalculationService = statsCalculationService;
        this.timeSeriesService = timeSeriesService;
    }

    public void scrapeFullSeason(int seasonYear) {
        log.info("Starting full season scrape for {}", seasonYear);

        ScrapeBatch confBatch = conferenceScraper.scrape(seasonYear);
        if (confBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.error("Conference scrape failed, aborting full season scrape");
            return;
        }

        ScrapeBatch teamBatch = teamScraper.scrape(seasonYear);
        if (teamBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.error("Team scrape failed, aborting full season scrape");
            return;
        }

        ScrapeBatch standingsBatch = standingsScraper.scrape(seasonYear);
        if (standingsBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.warn("Standings scrape failed, continuing with game scrape");
        }

        gameScraper.scrapeFullSeason(seasonYear);

        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
        timeSeriesService.calculateAndStoreForSeason(seasonYear);

        oddsBackfillScraper.backfill(seasonYear);

        log.info("Full season scrape completed for {}", seasonYear);
    }

    public void scrapeCurrentSeason(int seasonYear) {
        log.info("Starting current season re-scrape for {}", seasonYear);

        standingsScraper.scrape(seasonYear);
        gameScraper.scrapeCurrentSeason(seasonYear);
        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
        timeSeriesService.calculateAndStoreForSeason(seasonYear);
        oddsBackfillScraper.backfill(seasonYear);

        log.info("Current season re-scrape completed for {}", seasonYear);
    }

    public void scrapeTeams(int seasonYear) {
        teamScraper.scrape(seasonYear);
    }

    public void scrapeConferencesAndTeams(int seasonYear) {
        conferenceScraper.scrape(seasonYear);
        teamScraper.scrape(seasonYear);
    }

    public void scrapeStandings(int seasonYear) {
        standingsScraper.scrape(seasonYear);
    }

    public void scrapeGames(int seasonYear) {
        gameScraper.scrapeFullSeason(seasonYear);
    }

    public void backfillOdds(int seasonYear) {
        oddsBackfillScraper.backfill(seasonYear);
    }

    public void calculateStats(int seasonYear) {
        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
    }

    public void calculateTimeSeries(int seasonYear) {
        timeSeriesService.calculateAndStoreForSeason(seasonYear);
    }
}
