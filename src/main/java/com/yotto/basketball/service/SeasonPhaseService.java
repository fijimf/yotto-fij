package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The single source of truth for "what phase of the basketball calendar is it".
 *
 * <p>Decisions are data-driven where real signals exist (a FINAL NCAA championship game, scheduled
 * tournament games) and fall back to calendar rules where they don't (Oct 15 preseason start).
 * NIT/CBI/Crown/other postseason games are invisible to phase logic — only {@code NCAA_TOURNAMENT}
 * games mark the postseason.
 *
 * <p>{@link #current()} is cached for a few minutes because a {@code @ControllerAdvice} exposes it
 * on every page render. An admin can force a phase (and optionally an as-of date) to preview any
 * state; the override is in-memory only and clears on restart.
 */
@Service
public class SeasonPhaseService {

    /** EPILOGUE (season-wrap) window after the championship game, inclusive. */
    static final int EPILOGUE_DAYS = 14;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final Clock clock;

    private volatile SeasonPhase.Phase forcedPhase;
    private volatile LocalDate forcedDate;
    private volatile CacheEntry cache;

    private record CacheEntry(SeasonPhase value, Instant computedAt) {}

    public SeasonPhaseService(SeasonRepository seasonRepository,
                              GameRepository gameRepository,
                              Clock clock) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.clock = clock;
    }

    /** The phase as of now (Eastern), honoring any admin override. Cached briefly. */
    public SeasonPhase current() {
        CacheEntry c = cache;
        Instant now = clock.instant();
        if (c != null && Duration.between(c.computedAt(), now).compareTo(CACHE_TTL) < 0) {
            return c.value();
        }
        SeasonPhase computed = computeWithOverride();
        cache = new CacheEntry(computed, now);
        return computed;
    }

    private SeasonPhase computeWithOverride() {
        LocalDate today = forcedDate != null ? forcedDate : LocalDate.now(clock);
        SeasonPhase base = asOf(today);
        SeasonPhase.Phase force = forcedPhase;
        if (force != null && force != base.phase()) {
            return new SeasonPhase(force, base.season(), base.today(), base.firstGameDate(),
                    base.lastGameDate(), base.championshipDate(),
                    base.confTourneyWeek(), base.selectionSunday());
        }
        return base;
    }

    /** The phase as of an arbitrary Eastern date, ignoring any override. */
    @Transactional(readOnly = true)
    public SeasonPhase asOf(LocalDate today) {
        Season season = resolveSeason(today);
        if (season == null) {
            return new SeasonPhase(SeasonPhase.Phase.OFFSEASON, null, today,
                    null, null, null, false, false);
        }

        LocalDate firstGame = gameRepository.findMinGameDate(season.getId())
                .map(EasternDates::toEasternDate).orElse(null);
        LocalDate lastGame = gameRepository.findMaxGameDate(season.getId())
                .map(EasternDates::toEasternDate).orElse(null);

        List<Game> ncaaGames = gameRepository.findBySeasonIdAndTournamentType(
                season.getId(), Game.TournamentType.NCAA_TOURNAMENT);
        LocalDate championship = championshipDate(ncaaGames);

        // 1. Championship decided: EPILOGUE for two weeks, then OFFSEASON.
        if (championship != null && today.isAfter(championship)) {
            SeasonPhase.Phase p = today.isAfter(championship.plusDays(EPILOGUE_DAYS))
                    ? SeasonPhase.Phase.OFFSEASON : SeasonPhase.Phase.EPILOGUE;
            return new SeasonPhase(p, season, today, firstGame, lastGame, championship, false, false);
        }

        // 2. NCAA tournament known and not finished: POSTSEASON from Selection Sunday on.
        if (!ncaaGames.isEmpty()) {
            LocalDate entry = selectionSundayFor(ncaaGames);
            if (entry != null && !today.isBefore(entry)) {
                return new SeasonPhase(SeasonPhase.Phase.POSTSEASON, season, today,
                        firstGame, lastGame, championship, false, today.isEqual(entry));
            }
        }

        // 3. Season underway. Without NCAA data a season can't end in EPILOGUE — after the last
        //    game plus a grace window it just goes quiet.
        if (firstGame != null && !today.isBefore(firstGame)) {
            if (ncaaGames.isEmpty() && lastGame != null
                    && today.isAfter(lastGame.plusDays(EPILOGUE_DAYS))) {
                return new SeasonPhase(SeasonPhase.Phase.OFFSEASON, season, today,
                        firstGame, lastGame, null, false, false);
            }
            boolean confWeek = confTourneyWeek(season, today);
            return new SeasonPhase(SeasonPhase.Phase.IN_SEASON, season, today,
                    firstGame, lastGame, championship, confWeek, false);
        }

        // 4. Before the first game: PRESEASON from Oct 15 / first game minus 3 weeks.
        if (!today.isBefore(preseasonStart(season, firstGame))) {
            return new SeasonPhase(SeasonPhase.Phase.PRESEASON, season, today,
                    firstGame, lastGame, null, false, false);
        }

        return new SeasonPhase(SeasonPhase.Phase.OFFSEASON, season, today,
                firstGame, lastGame, championship, false, false);
    }

    /** Season covering today; otherwise the latest season (past or upcoming). Null on empty DB. */
    private Season resolveSeason(LocalDate today) {
        List<Season> covering = seasonRepository.findAllByDate(today);
        if (!covering.isEmpty()) {
            return covering.get(0);
        }
        return seasonRepository.findTopByOrderByYearDesc().orElse(null);
    }

    /**
     * Eastern date of the NCAA title game once decided. Primary signal: a FINAL NCAA game whose
     * round names the championship. Fallback for unclassified rounds: the tournament ran and every
     * game is settled — its last FINAL game decided it.
     */
    private static LocalDate championshipDate(List<Game> ncaaGames) {
        Optional<LocalDate> byRound = ncaaGames.stream()
                .filter(g -> g.getStatus() == Game.GameStatus.FINAL)
                .filter(g -> g.getTournamentRound() != null
                        && g.getTournamentRound().toLowerCase(Locale.ROOT).contains("championship"))
                .map(g -> EasternDates.toEasternDate(g.getGameDate()))
                .max(LocalDate::compareTo);
        if (byRound.isPresent()) {
            return byRound.get();
        }
        boolean allSettled = !ncaaGames.isEmpty() && ncaaGames.stream()
                .allMatch(g -> g.getStatus() == Game.GameStatus.FINAL
                        || g.getStatus() == Game.GameStatus.CANCELLED);
        if (!allSettled) {
            return null;
        }
        return ncaaGames.stream()
                .filter(g -> g.getStatus() == Game.GameStatus.FINAL)
                .map(g -> EasternDates.toEasternDate(g.getGameDate()))
                .max(LocalDate::compareTo).orElse(null);
    }

    /**
     * Selection Sunday, derived: the Sunday on or before (first NCAA game − 2 days). The First
     * Four tips Tuesday, so with full data this lands exactly on the reveal Sunday; if the First
     * Four is missing and the earliest game is Thursday, it still resolves to the same Sunday.
     */
    private static LocalDate selectionSundayFor(List<Game> ncaaGames) {
        return ncaaGames.stream()
                .map(g -> EasternDates.toEasternDate(g.getGameDate()))
                .min(LocalDate::compareTo)
                .map(first -> first.minusDays(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)))
                .orElse(null);
    }

    /** Conference-tournament games within [today−1, today+5]. */
    private boolean confTourneyWeek(Season season, LocalDate today) {
        LocalDateTime[] window = EasternDates.rangeWindowUtc(today.minusDays(1), today.plusDays(5));
        return gameRepository.countTournamentGamesInWindow(season.getId(),
                Game.TournamentType.CONFERENCE_TOURNAMENT, window[0], window[1]) > 0;
    }

    /** Oct 15 of the season's opening fall, or 3 weeks before the first game — whichever is earlier. */
    private static LocalDate preseasonStart(Season season, LocalDate firstGame) {
        int fallYear = season.getStartDate() != null
                ? season.getStartDate().getYear()
                : season.getYear() - 1;
        LocalDate oct15 = LocalDate.of(fallYear, 10, 15);
        LocalDate anchor = firstGame != null ? firstGame
                : (season.getStartDate() != null ? season.getStartDate() : LocalDate.of(fallYear, 11, 1));
        LocalDate threeWeeksOut = anchor.minusDays(21);
        return threeWeeksOut.isBefore(oct15) ? threeWeeksOut : oct15;
    }

    // ── Admin override ────────────────────────────────────────────────────────

    /** Force a phase (and optionally an as-of date) for previewing; null phase+date clears. */
    public void setOverride(SeasonPhase.Phase phase, LocalDate date) {
        this.forcedPhase = phase;
        this.forcedDate = date;
        this.cache = null;
    }

    public void clearOverride() {
        setOverride(null, null);
    }

    public boolean isOverridden() {
        return forcedPhase != null || forcedDate != null;
    }

    public SeasonPhase.Phase getForcedPhase() {
        return forcedPhase;
    }

    public LocalDate getForcedDate() {
        return forcedDate;
    }
}
