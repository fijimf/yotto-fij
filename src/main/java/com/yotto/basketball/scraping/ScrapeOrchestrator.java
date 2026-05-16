package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.service.PowerRatingService;
import com.yotto.basketball.service.StatsCalculationService;
import com.yotto.basketball.service.StatisticsTimeSeriesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ScrapeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ScrapeOrchestrator.class);

    private final ConferenceScraper conferenceScraper;
    private final TeamScraper teamScraper;
    private final StandingsScraper standingsScraper;
    private final GameScraper gameScraper;
    private final OddsBackfillScraper oddsBackfillScraper;
    private final GameStatsScraper gameStatsScraper;
    private final StatsCalculationService statsCalculationService;
    private final StatisticsTimeSeriesService timeSeriesService;
    private final PowerRatingService powerRatingService;

    public ScrapeOrchestrator(ConferenceScraper conferenceScraper, TeamScraper teamScraper,
                              StandingsScraper standingsScraper, GameScraper gameScraper,
                              OddsBackfillScraper oddsBackfillScraper,
                              GameStatsScraper gameStatsScraper,
                              StatsCalculationService statsCalculationService,
                              StatisticsTimeSeriesService timeSeriesService,
                              PowerRatingService powerRatingService) {
        this.conferenceScraper = conferenceScraper;
        this.teamScraper = teamScraper;
        this.standingsScraper = standingsScraper;
        this.gameScraper = gameScraper;
        this.oddsBackfillScraper = oddsBackfillScraper;
        this.gameStatsScraper = gameStatsScraper;
        this.statsCalculationService = statsCalculationService;
        this.timeSeriesService = timeSeriesService;
        this.powerRatingService = powerRatingService;
    }

    public void scrapeFullSeason(int seasonYear) {
        scrapeFullSeason(seasonYear, ScrapeBatch.Source.MANUAL);
    }

    public void scrapeFullSeason(int seasonYear, ScrapeBatch.Source source) {
        PipelineContext run = new PipelineContext(UUID.randomUUID(), null, source);
        log.info("Starting full season scrape for {} (pipeline {})", seasonYear, run.pipelineRunId());

        ScrapeBatch confBatch = conferenceScraper.scrape(seasonYear, run.step(1));
        if (confBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.error("Conference scrape failed, aborting full season scrape");
            return;
        }

        ScrapeBatch teamBatch = teamScraper.scrape(seasonYear, run.step(2));
        if (teamBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.error("Team scrape failed, aborting full season scrape");
            return;
        }

        ScrapeBatch standingsBatch = standingsScraper.scrape(seasonYear, run.step(3));
        if (standingsBatch.getStatus() == ScrapeBatch.ScrapeStatus.FAILED) {
            log.warn("Standings scrape failed, continuing with game scrape");
        }

        gameScraper.scrapeFullSeason(seasonYear, run.step(4));

        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
        timeSeriesService.calculateAndStoreForSeason(seasonYear);
        powerRatingService.calculateAndStoreForSeason(seasonYear);

        oddsBackfillScraper.backfill(seasonYear, run.step(5));
        gameStatsScraper.backfill(seasonYear, run.step(6));

        log.info("Full season scrape completed for {} (pipeline {})", seasonYear, run.pipelineRunId());
    }

    public void scrapeCurrentSeason(int seasonYear) {
        scrapeCurrentSeason(seasonYear, ScrapeBatch.Source.MANUAL);
    }

    public void scrapeCurrentSeason(int seasonYear, ScrapeBatch.Source source) {
        PipelineContext run = new PipelineContext(UUID.randomUUID(), null, source);
        log.info("Starting current season re-scrape for {} (pipeline {})", seasonYear, run.pipelineRunId());

        standingsScraper.scrape(seasonYear, run.step(1));
        gameScraper.scrapeCurrentSeason(seasonYear, run.step(2));
        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
        timeSeriesService.calculateAndStoreForSeason(seasonYear);
        powerRatingService.calculateAndStoreForSeason(seasonYear);
        oddsBackfillScraper.backfill(seasonYear, run.step(3));
        gameStatsScraper.backfill(seasonYear, run.step(4));

        log.info("Current season re-scrape completed for {} (pipeline {})", seasonYear, run.pipelineRunId());
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

    public void backfillGameStats(int seasonYear) {
        gameStatsScraper.backfill(seasonYear);
    }

    public void calculateStats(int seasonYear) {
        statsCalculationService.calculateAndUpdateForSeason(seasonYear);
    }

    public void calculateTimeSeries(int seasonYear) {
        timeSeriesService.calculateAndStoreForSeason(seasonYear);
    }

    public void calculatePowerRatings(int seasonYear) {
        powerRatingService.calculateAndStoreForSeason(seasonYear);
    }
}
