package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.config.ScrapingProperties;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class GameScraper {

    private static final Logger log = LoggerFactory.getLogger(GameScraper.class);

    private final EspnApiClient espnApiClient;
    private final ScrapingProperties scrapingProperties;
    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final BettingOddsRepository bettingOddsRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public GameScraper(EspnApiClient espnApiClient, ScrapingProperties scrapingProperties,
                       TeamRepository teamRepository, SeasonRepository seasonRepository,
                       GameRepository gameRepository, BettingOddsRepository bettingOddsRepository,
                       ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.scrapingProperties = scrapingProperties;
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.bettingOddsRepository = bettingOddsRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch scrapeFullSeason(int seasonYear) {
        LocalDate start = LocalDate.of(seasonYear - 1, scrapingProperties.getSeasonStartMonth(),
                scrapingProperties.getSeasonStartDay());
        LocalDate end = LocalDate.of(seasonYear, scrapingProperties.getSeasonEndMonth(),
                scrapingProperties.getSeasonEndDay());

        return scrapeDateRange(seasonYear, start, end);
    }

    @Transactional
    public ScrapeBatch scrapeCurrentSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found, cannot re-scrape current season", seasonYear);
            return null;
        }

        // Collect dates to re-fetch: today + dates of non-final games
        Set<LocalDate> datesToFetch = new HashSet<>();
        datesToFetch.add(LocalDate.now());

        List<Game> nonFinalGames = gameRepository.findBySeasonIdAndStatusNot(season.getId(), Game.GameStatus.FINAL);
        for (Game game : nonFinalGames) {
            if (game.getScrapeDate() != null) {
                datesToFetch.add(game.getScrapeDate());
            }
        }

        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.GAMES);
        batch = scrapeBatchRepository.save(batch);

        for (LocalDate date : datesToFetch) {
            try {
                scrapeDate(date, seasonYear, batch);
                batch.incrementDatesSucceeded();
            } catch (Exception e) {
                log.error("Failed to scrape date {}", date, e);
                batch.incrementDatesFailed();
            }
        }

        batch.complete();
        log.info("Current season re-scrape for {}: {} created, {} updated, {}/{} dates",
                seasonYear, batch.getRecordsCreated(), batch.getRecordsUpdated(),
                batch.getDatesSucceeded(), batch.getDatesSucceeded() + batch.getDatesFailed());

        return scrapeBatchRepository.save(batch);
    }

    private ScrapeBatch scrapeDateRange(int seasonYear, LocalDate start, LocalDate end) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.GAMES);
        batch = scrapeBatchRepository.save(batch);

        LocalDate current = start;
        while (!current.isAfter(end)) {
            try {
                scrapeDate(current, seasonYear, batch);
                batch.incrementDatesSucceeded();
            } catch (Exception e) {
                log.error("Failed to scrape date {}", current, e);
                batch.incrementDatesFailed();
            }
            current = current.plusDays(1);

            // Periodic save to track progress
            if (batch.getDatesSucceeded() % 10 == 0) {
                batch = scrapeBatchRepository.save(batch);
            }
        }

        batch.complete();
        log.info("Full season scrape for {}: {} created, {} updated, {}/{} dates OK/failed",
                seasonYear, batch.getRecordsCreated(), batch.getRecordsUpdated(),
                batch.getDatesSucceeded(), batch.getDatesFailed());

        return scrapeBatchRepository.save(batch);
    }

    private void scrapeDate(LocalDate date, int seasonYear, ScrapeBatch batch) {
        JsonNode root = espnApiClient.fetchScoreboard(date);
        JsonNode events = root.path("sports").path(0).path("leagues").path(0).path("events");

        if (!events.isArray()) return;

        for (JsonNode event : events) {
            try {
                upsertGame(event, date, seasonYear, batch);
            } catch (Exception e) {
                log.warn("Failed to process event {} on {}", event.path("id").asText(), date, e);
            }
        }
    }

    private void upsertGame(JsonNode event, LocalDate scrapeDate, int seasonYear, ScrapeBatch batch) {
        String espnId = event.path("id").asText();

        // Find home and away teams
        JsonNode competitors = event.path("competitors");
        if (!competitors.isArray() || competitors.size() < 2) return;

        String homeTeamEspnId = null;
        String awayTeamEspnId = null;
        String homeScore = null;
        String awayScore = null;

        for (JsonNode comp : competitors) {
            String homeAway = comp.path("homeAway").asText();
            if ("home".equals(homeAway)) {
                homeTeamEspnId = comp.path("id").asText();
                homeScore = comp.path("score").asText("");
            } else if ("away".equals(homeAway)) {
                awayTeamEspnId = comp.path("id").asText();
                awayScore = comp.path("score").asText("");
            }
        }

        if (homeTeamEspnId == null || awayTeamEspnId == null) return;

        Team homeTeam = teamRepository.findByEspnId(homeTeamEspnId).orElse(null);
        Team awayTeam = teamRepository.findByEspnId(awayTeamEspnId).orElse(null);
        if (homeTeam == null || awayTeam == null) {
            log.warn("Unknown team(s) for event {}: home={}, away={}", espnId, homeTeamEspnId, awayTeamEspnId);
            return;
        }

        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) return;

        // Parse game date
        LocalDateTime gameDate = parseGameDate(event.path("date").asText());
        if (gameDate == null) return;

        // Parse status
        Game.GameStatus status = mapStatus(event.path("fullStatus").path("type"));

        // Upsert game
        Game game = gameRepository.findByEspnId(espnId).orElse(null);
        boolean isNew = (game == null);
        if (isNew) {
            game = new Game();
            game.setEspnId(espnId);
        }

        game.setHomeTeam(homeTeam);
        game.setAwayTeam(awayTeam);
        game.setGameDate(gameDate);
        game.setVenue(event.path("location").asText(null));
        game.setNeutralSite(event.path("neutralSite").asBoolean(false));
        game.setSeason(season);
        game.setStatus(status);
        game.setScrapeDate(scrapeDate);

        if (!homeScore.isEmpty()) {
            try {
                game.setHomeScore(Integer.parseInt(homeScore));
            } catch (NumberFormatException ignored) {}
        }
        if (!awayScore.isEmpty()) {
            try {
                game.setAwayScore(Integer.parseInt(awayScore));
            } catch (NumberFormatException ignored) {}
        }

        game = gameRepository.save(game);

        if (isNew) {
            batch.incrementCreated();
        } else {
            batch.incrementUpdated();
        }

        // Extract odds from scoreboard if pre-game
        JsonNode odds = event.path("odds");
        if (odds != null && !odds.isMissingNode() && odds.isObject()) {
            upsertScoreboardOdds(game, odds);
        }
    }

    private void upsertScoreboardOdds(Game game, JsonNode odds) {
        BigDecimal spread = parseBigDecimal(odds.path("spread"));
        BigDecimal overUnder = parseBigDecimal(odds.path("overUnder"));

        if (spread == null && overUnder == null) return;

        BettingOdds bo = bettingOddsRepository.findByGameId(game.getId()).orElse(null);
        if (bo == null) {
            bo = new BettingOdds();
            bo.setGame(game);
        }

        bo.setSpread(spread);
        bo.setOverUnder(overUnder);

        Integer homeML = parseMoneyline(odds.path("home").path("moneyLine"));
        Integer awayML = parseMoneyline(odds.path("away").path("moneyLine"));
        bo.setHomeMoneyline(homeML);
        bo.setAwayMoneyline(awayML);

        String provider = odds.path("provider").path("name").asText(null);
        bo.setSource(provider);
        bo.setLastUpdated(LocalDateTime.now());

        bettingOddsRepository.save(bo);
    }

    private Game.GameStatus mapStatus(JsonNode statusType) {
        String name = statusType.path("name").asText("");
        return switch (name) {
            case "STATUS_SCHEDULED" -> Game.GameStatus.SCHEDULED;
            case "STATUS_IN_PROGRESS", "STATUS_HALFTIME", "STATUS_END_PERIOD" -> Game.GameStatus.IN_PROGRESS;
            case "STATUS_FINAL" -> Game.GameStatus.FINAL;
            case "STATUS_POSTPONED" -> Game.GameStatus.POSTPONED;
            case "STATUS_CANCELED", "STATUS_CANCELLED" -> Game.GameStatus.CANCELLED;
            default -> {
                String state = statusType.path("state").asText("");
                yield switch (state) {
                    case "pre" -> Game.GameStatus.SCHEDULED;
                    case "in" -> Game.GameStatus.IN_PROGRESS;
                    case "post" -> Game.GameStatus.FINAL;
                    default -> Game.GameStatus.SCHEDULED;
                };
            }
        };
    }

    private LocalDateTime parseGameDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            // ESPN format: "2025-02-15T21:00:00Z" or "2025-02-15T21:00Z"
            dateStr = dateStr.replace("Z", "");
            if (dateStr.length() == 16) {
                dateStr += ":00"; // add seconds if missing
            }
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse date: {}", dateStr);
            return null;
        }
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
