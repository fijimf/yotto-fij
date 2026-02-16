package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OddsBackfillScraper {

    private static final Logger log = LoggerFactory.getLogger(OddsBackfillScraper.class);

    private final EspnApiClient espnApiClient;
    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final BettingOddsRepository bettingOddsRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public OddsBackfillScraper(EspnApiClient espnApiClient, GameRepository gameRepository,
                                SeasonRepository seasonRepository, BettingOddsRepository bettingOddsRepository,
                                ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.gameRepository = gameRepository;
        this.seasonRepository = seasonRepository;
        this.bettingOddsRepository = bettingOddsRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch backfill(int seasonYear) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.ODDS_BACKFILL);
        batch = scrapeBatchRepository.save(batch);

        try {
            Season season = seasonRepository.findByYear(seasonYear).orElse(null);
            if (season == null) {
                batch.fail("Season " + seasonYear + " not found");
                return scrapeBatchRepository.save(batch);
            }

            List<Game> gamesWithoutOdds = gameRepository.findFinalGamesWithoutOdds(season.getId());
            log.info("Odds backfill for season {}: {} games to process", seasonYear, gamesWithoutOdds.size());

            for (Game game : gamesWithoutOdds) {
                try {
                    boolean success = backfillGameOdds(game);
                    if (success) {
                        batch.incrementCreated();
                    }
                    batch.incrementDatesSucceeded();
                } catch (Exception e) {
                    log.warn("Failed to backfill odds for game {} (ESPN ID: {})", game.getId(), game.getEspnId(), e);
                    batch.incrementDatesFailed();
                }

                // Periodic save
                if ((batch.getDatesSucceeded() + batch.getDatesFailed()) % 20 == 0) {
                    batch = scrapeBatchRepository.save(batch);
                }
            }

            batch.complete();
            log.info("Odds backfill for season {}: {} created, {}/{} succeeded/failed",
                    seasonYear, batch.getRecordsCreated(),
                    batch.getDatesSucceeded(), batch.getDatesFailed());
        } catch (Exception e) {
            log.error("Odds backfill failed for season {}", seasonYear, e);
            batch.fail(e.getMessage());
        }

        return scrapeBatchRepository.save(batch);
    }

    private boolean backfillGameOdds(Game game) {
        if (game.getEspnId() == null) return false;

        JsonNode root = espnApiClient.fetchGameOdds(game.getEspnId());
        if (root == null) return false;

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return false;

        JsonNode firstProvider = items.path(0);

        BigDecimal spread = parseBigDecimal(firstProvider.path("spread"));
        BigDecimal overUnder = parseBigDecimal(firstProvider.path("overUnder"));

        if (spread == null && overUnder == null) return false;

        BettingOdds bo = bettingOddsRepository.findByGameId(game.getId()).orElse(null);
        if (bo == null) {
            bo = new BettingOdds();
            bo.setGame(game);
        }

        bo.setSpread(spread);
        bo.setOverUnder(overUnder);

        // Moneylines
        Integer homeML = parseMoneyline(firstProvider.path("homeTeamOdds").path("moneyLine"));
        Integer awayML = parseMoneyline(firstProvider.path("awayTeamOdds").path("moneyLine"));
        bo.setHomeMoneyline(homeML);
        bo.setAwayMoneyline(awayML);

        // Opening lines
        BigDecimal openingSpread = parseOpeningLine(firstProvider.path("pointSpread").path("home").path("open").path("line"));
        BigDecimal openingOU = parseOpeningOverUnder(firstProvider.path("total").path("over").path("open").path("line"));
        bo.setOpeningSpread(openingSpread);
        bo.setOpeningOverUnder(openingOU);

        // Provider
        String provider = firstProvider.path("provider").path("name").asText(null);
        bo.setSource(provider);
        bo.setLastUpdated(LocalDateTime.now());

        bettingOddsRepository.save(bo);
        return true;
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            if (node.isNumber()) return BigDecimal.valueOf(node.asDouble());
            String text = node.asText("");
            if (text.isEmpty()) return null;
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseOpeningLine(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText("");
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseOpeningOverUnder(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText("");
        if (text.isEmpty()) return null;
        // Strip "o" or "u" prefix (e.g., "o134.5")
        if (text.startsWith("o") || text.startsWith("u") || text.startsWith("O") || text.startsWith("U")) {
            text = text.substring(1);
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseMoneyline(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText("");
        if (text.isEmpty() || "OFF".equalsIgnoreCase(text)) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
