package com.yotto.basketball.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GameStatsScraper {

    private static final Logger log = LoggerFactory.getLogger(GameStatsScraper.class);

    private final EspnApiClient espnApiClient;
    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final TeamRepository teamRepository;
    private final TeamGameStatsRepository teamGameStatsRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;

    public GameStatsScraper(EspnApiClient espnApiClient, GameRepository gameRepository,
                            SeasonRepository seasonRepository, TeamRepository teamRepository,
                            TeamGameStatsRepository teamGameStatsRepository,
                            ScrapeBatchRepository scrapeBatchRepository) {
        this.espnApiClient = espnApiClient;
        this.gameRepository = gameRepository;
        this.seasonRepository = seasonRepository;
        this.teamRepository = teamRepository;
        this.teamGameStatsRepository = teamGameStatsRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
    }

    @Transactional
    public ScrapeBatch backfill(int seasonYear) {
        return backfill(seasonYear, PipelineContext.manual());
    }

    @Transactional
    public ScrapeBatch backfill(int seasonYear, PipelineContext ctx) {
        ScrapeBatch batch = ScrapeBatch.start(seasonYear, ScrapeBatch.ScrapeType.GAME_STATS,
                ctx.source(), ctx.pipelineRunId(), ctx.stepOrder());
        batch.setCurrentStep("GAME_STATS");
        batch = scrapeBatchRepository.save(batch);

        try {
            Season season = seasonRepository.findByYear(seasonYear).orElse(null);
            if (season == null) {
                batch.fail("Season " + seasonYear + " not found");
                return scrapeBatchRepository.save(batch);
            }

            List<Game> games = gameRepository.findFinalGamesWithoutStats(season.getId());
            int total = games.size();
            batch.setProgressTotal(total);
            log.info("Game stats backfill for season {}: {} games to process", seasonYear, total);

            int idx = 0;
            for (Game game : games) {
                idx++;
                batch.setCurrentStep("GAME_STATS " + idx + "/" + total);
                try {
                    int rowsWritten = scrapeForGame(game);
                    if (rowsWritten > 0) {
                        for (int i = 0; i < rowsWritten; i++) batch.incrementCreated();
                    }
                    batch.incrementDatesSucceeded();
                } catch (Exception e) {
                    log.warn("Failed to scrape stats for game {} (ESPN ID: {})",
                            game.getId(), game.getEspnId(), e);
                    batch.incrementDatesFailed();
                }

                if ((batch.getDatesSucceeded() + batch.getDatesFailed()) % 20 == 0) {
                    batch = scrapeBatchRepository.save(batch);
                }
            }

            batch.complete();
            log.info("Game stats backfill for season {}: {} rows, {}/{} succeeded/failed",
                    seasonYear, batch.getRecordsCreated(),
                    batch.getDatesSucceeded(), batch.getDatesFailed());
        } catch (Exception e) {
            log.error("Game stats backfill failed for season {}", seasonYear, e);
            batch.fail(e.getMessage());
        }

        return scrapeBatchRepository.save(batch);
    }

    /** Fetches the summary endpoint for a game and upserts a row per team. Returns rows written. */
    @Transactional
    public int scrapeForGame(Game game) {
        if (game.getEspnId() == null) return 0;

        JsonNode root = espnApiClient.fetchGameSummary(game.getEspnId());
        if (root == null) return 0;

        JsonNode teams = root.path("boxscore").path("teams");
        if (!teams.isArray() || teams.isEmpty()) return 0;

        int written = 0;
        for (JsonNode teamNode : teams) {
            String espnTeamId = teamNode.path("team").path("id").asText("");
            String homeAway = teamNode.path("homeAway").asText("");
            if (espnTeamId.isEmpty() || homeAway.isEmpty()) continue;

            Team team = teamRepository.findByEspnId(espnTeamId).orElse(null);
            if (team == null) {
                log.warn("Unknown team {} on summary for game {}", espnTeamId, game.getEspnId());
                continue;
            }

            TeamGameStats stats = teamGameStatsRepository
                    .findByGameIdAndTeamId(game.getId(), team.getId())
                    .orElseGet(TeamGameStats::new);

            stats.setGame(game);
            stats.setTeam(team);
            stats.setHomeAway(homeAway);
            stats.setScrapeDate(LocalDateTime.now());

            applyStatistics(stats, teamNode.path("statistics"));

            teamGameStatsRepository.save(stats);
            written++;
        }
        return written;
    }

    private void applyStatistics(TeamGameStats stats, JsonNode statistics) {
        if (!statistics.isArray()) return;

        for (JsonNode stat : statistics) {
            String name = stat.path("name").asText("");
            String displayValue = stat.path("displayValue").asText("");
            if (name.isEmpty() || displayValue.isEmpty()) continue;

            switch (name) {
                case "fieldGoalsMade-fieldGoalsAttempted" ->
                        parseMadeAttempted(displayValue, stats::setFgMade, stats::setFgAttempted);
                case "threePointFieldGoalsMade-threePointFieldGoalsAttempted" ->
                        parseMadeAttempted(displayValue, stats::setFg3Made, stats::setFg3Attempted);
                case "freeThrowsMade-freeThrowsAttempted" ->
                        parseMadeAttempted(displayValue, stats::setFtMade, stats::setFtAttempted);
                case "totalRebounds" -> stats.setTotalReb(parseInt(displayValue));
                case "offensiveRebounds" -> stats.setOffensiveReb(parseInt(displayValue));
                case "defensiveRebounds" -> stats.setDefensiveReb(parseInt(displayValue));
                case "assists" -> stats.setAssists(parseInt(displayValue));
                case "steals" -> stats.setSteals(parseInt(displayValue));
                case "blocks" -> stats.setBlocks(parseInt(displayValue));
                case "turnovers" -> stats.setTurnovers(parseInt(displayValue));
                case "fouls" -> stats.setFouls(parseInt(displayValue));
                case "technicalFouls" -> stats.setTechnicalFouls(parseInt(displayValue));
                case "flagrantFouls" -> stats.setFlagrantFouls(parseInt(displayValue));
                case "largestLead" -> stats.setLargestLead(parseInt(displayValue));
                case "pointsInPaint" -> stats.setPointsInPaint(parseInt(displayValue));
                case "fastBreakPoints" -> stats.setFastBreakPts(parseInt(displayValue));
                case "turnoverPoints" -> stats.setTurnoverPts(parseInt(displayValue));
                default -> { /* ignored: percentages, player-level, etc. */ }
            }
        }
    }

    static void parseMadeAttempted(String displayValue,
                                   java.util.function.Consumer<Integer> setMade,
                                   java.util.function.Consumer<Integer> setAttempted) {
        if (displayValue == null) return;
        int dash = displayValue.indexOf('-');
        if (dash <= 0 || dash == displayValue.length() - 1) return;
        Integer made = parseInt(displayValue.substring(0, dash));
        Integer attempted = parseInt(displayValue.substring(dash + 1));
        if (made != null) setMade.accept(made);
        if (attempted != null) setAttempted.accept(attempted);
    }

    static Integer parseInt(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed) || "--".equals(trimmed)) return null;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
