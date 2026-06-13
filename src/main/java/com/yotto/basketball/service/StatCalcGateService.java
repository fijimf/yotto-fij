package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.StatCalcWatermark;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.StatCalcWatermarkRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Decides whether the per-season stats calculation needs to run at all, and if so
 * from which game date, by comparing the current game data against the watermark
 * recorded at the end of the previous run.
 *
 * <p>{@code calcStartedAt} is captured before any game data is read, so writes that
 * commit while a calculation is in flight are picked up by the next cycle.
 */
@Service
public class StatCalcGateService {

    private static final Logger log = LoggerFactory.getLogger(StatCalcGateService.class);

    public enum Mode { SKIP, FULL, INCREMENTAL }

    /**
     * @param mode           SKIP (nothing changed), FULL (recompute and rewrite the whole
     *                       season), or INCREMENTAL (recompute all, rewrite from fromDate)
     * @param fromDate       earliest game date affected by changes; only set for INCREMENTAL
     * @param calcStartedAt  watermark timestamp to record after a successful run
     * @param finalGameCount FINAL-game count to record after a successful run
     */
    public record RecalcScope(Mode mode, LocalDate fromDate,
                              LocalDateTime calcStartedAt, int finalGameCount) {

        public static RecalcScope skip() {
            return new RecalcScope(Mode.SKIP, null, null, 0);
        }
    }

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final TeamGameStatsRepository teamGameStatsRepository;
    private final StatCalcWatermarkRepository watermarkRepository;

    public StatCalcGateService(SeasonRepository seasonRepository,
                               GameRepository gameRepository,
                               TeamGameStatsRepository teamGameStatsRepository,
                               StatCalcWatermarkRepository watermarkRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.teamGameStatsRepository = teamGameStatsRepository;
        this.watermarkRepository = watermarkRepository;
    }

    @Transactional(readOnly = true)
    public RecalcScope check(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            return RecalcScope.skip();
        }

        LocalDateTime calcStartedAt = LocalDateTime.now();
        int finalCount = (int) gameRepository.countBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL);

        StatCalcWatermark wm = watermarkRepository.findBySeasonId(season.getId()).orElse(null);
        if (wm == null) {
            return new RecalcScope(Mode.FULL, null, calcStartedAt, finalCount);
        }

        // Fewer FINAL games than last run means deletions — changed rows are gone,
        // so updated_at can't scope the damage. Recompute everything.
        if (finalCount < wm.getFinalGameCount()) {
            log.info("Season {}: FINAL game count dropped {} -> {}, full recalc",
                    seasonYear, wm.getFinalGameCount(), finalCount);
            return new RecalcScope(Mode.FULL, null, calcStartedAt, finalCount);
        }

        LocalDate minGameChange = gameRepository
                .findMinGameDateUpdatedSince(season.getId(), wm.getLastCalcStartedAt());
        LocalDate minStatsChange = teamGameStatsRepository
                .findMinGameDateWithStatsScrapedSince(season.getId(), wm.getLastCalcStartedAt());
        LocalDate fromDate = earlier(minGameChange, minStatsChange);

        if (fromDate != null) {
            return new RecalcScope(Mode.INCREMENTAL, fromDate, calcStartedAt, finalCount);
        }
        // Count grew but no change was detectable (e.g., writes that bypassed JPA) —
        // fall back to a full recalc rather than trust a stale picture.
        if (finalCount != wm.getFinalGameCount()) {
            return new RecalcScope(Mode.FULL, null, calcStartedAt, finalCount);
        }
        return RecalcScope.skip();
    }

    @Transactional
    public void recordRun(int seasonYear, RecalcScope scope) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null || scope.calcStartedAt() == null) {
            return;
        }
        StatCalcWatermark wm = watermarkRepository.findBySeasonId(season.getId())
                .orElseGet(() -> {
                    StatCalcWatermark fresh = new StatCalcWatermark();
                    fresh.setSeason(season);
                    return fresh;
                });
        wm.setLastCalcStartedAt(scope.calcStartedAt());
        wm.setFinalGameCount(scope.finalGameCount());
        watermarkRepository.save(wm);
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
