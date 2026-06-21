package com.yotto.basketball.controller;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Scoreboard: all games for a single Eastern calendar date, with prev/next navigation.
 *
 * <p>{@code Game.gameDate} is stored in UTC, so "games on Eastern date D" is resolved by querying
 * the UTC window that D maps to ({@link #easternDayWindowUtc}). All "today"/"noon" comparisons use
 * {@link #EASTERN}.
 */
@Controller
public class GameWebController {

    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final LocalTime NOON = LocalTime.NOON;

    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final TournamentBadgeFormatter tournamentBadgeFormatter;

    public GameWebController(GameRepository gameRepository,
                            SeasonRepository seasonRepository,
                            TournamentBadgeFormatter tournamentBadgeFormatter) {
        this.gameRepository = gameRepository;
        this.seasonRepository = seasonRepository;
        this.tournamentBadgeFormatter = tournamentBadgeFormatter;
    }

    @GetMapping("/games")
    public String games(@RequestParam(required = false) String date, Model model) {
        LocalDate target = parseDate(date).orElseGet(this::resolveDefaultDate);
        populate(target, model);
        model.addAttribute("currentPage", "games");
        return "pages/games";
    }

    /** HTMX fragment: just the games list for a date. */
    @GetMapping("/games/on/{date}")
    public String gamesOn(@PathVariable String date, Model model) {
        LocalDate target = parseDate(date).orElseGet(this::resolveDefaultDate);
        populate(target, model);
        return "fragments/games-list :: games-list";
    }

    // ── Model assembly ─────────────────────────────────────────────────────────

    private void populate(LocalDate date, Model model) {
        LocalDateTime[] window = easternDayWindowUtc(date);
        List<Game> games = gameRepository.findInUtcWindow(window[0], window[1]);

        model.addAttribute("date", date);
        model.addAttribute("todayDate", LocalDate.now(EASTERN));
        model.addAttribute("prevDate", prevDateWithGames(window[0]).orElse(null));
        model.addAttribute("nextDate", nextDateWithGames(window[1]).orElse(null));
        model.addAttribute("games", games.stream().map(this::toRow).toList());
        model.addAttribute("gameCount", games.size());
    }

    /** Most recent Eastern date with games strictly before this window. */
    private Optional<LocalDate> prevDateWithGames(LocalDateTime startUtc) {
        return gameRepository.findMaxGameDateBefore(startUtc).map(GameWebController::toEasternDate);
    }

    /** Earliest Eastern date with games on/after this window. */
    private Optional<LocalDate> nextDateWithGames(LocalDateTime endUtc) {
        return gameRepository.findMinGameDateOnOrAfter(endUtc).map(GameWebController::toEasternDate);
    }

    // ── Default-date logic ─────────────────────────────────────────────────────

    /**
     * In season: before noon ET → yesterday, else today (clamped to the opener).
     * Out of season: November → season opener; any other month → season finale.
     * No seasons/games → today.
     */
    LocalDate resolveDefaultDate() {
        ZonedDateTime now = ZonedDateTime.now(EASTERN);
        LocalDate today = now.toLocalDate();

        Season season = seasonRepository.findTopByOrderByYearDesc().orElse(null);
        if (season == null) return today;

        LocalDate firstGame = gameRepository.findMinGameDate(season.getId())
                .map(GameWebController::toEasternDate).orElse(null);
        LocalDate lastGame = gameRepository.findMaxGameDate(season.getId())
                .map(GameWebController::toEasternDate).orElse(null);
        if (firstGame == null || lastGame == null) return today;

        boolean inSeason = !today.isBefore(firstGame) && !today.isAfter(lastGame);
        if (inSeason) {
            LocalDate def = now.toLocalTime().isBefore(NOON) ? today.minusDays(1) : today;
            return def.isBefore(firstGame) ? firstGame : def;
        }
        return today.getMonth() == Month.NOVEMBER ? firstGame : lastGame;
    }

    // ── Timezone helpers ───────────────────────────────────────────────────────

    /** The [startUtc, endUtc) UTC window for the given Eastern calendar date. */
    static LocalDateTime[] easternDayWindowUtc(LocalDate easternDate) {
        LocalDateTime start = easternDate.atStartOfDay(EASTERN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime end = easternDate.plusDays(1).atStartOfDay(EASTERN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new LocalDateTime[]{start, end};
    }

    private static LocalDate toEasternDate(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(EASTERN).toLocalDate();
    }

    private static LocalDateTime toEasternTime(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(EASTERN).toLocalDateTime();
    }

    private static Optional<LocalDate> parseDate(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(s));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Row mapping ────────────────────────────────────────────────────────────

    private GameDayRow toRow(Game g) {
        Team home = g.getHomeTeam();
        Team away = g.getAwayTeam();

        String result = null;
        if (g.getStatus() == Game.GameStatus.FINAL && g.getHomeScore() != null && g.getAwayScore() != null) {
            result = g.getHomeScore() > g.getAwayScore() ? "HOME"
                   : g.getAwayScore() > g.getHomeScore() ? "AWAY" : "TIE";
        }

        // Spread is stored from the home team's perspective.
        BigDecimal spread = null;
        BigDecimal overUnder = null;
        if (g.getBettingOdds() != null) {
            BettingOdds odds = g.getBettingOdds();
            spread = odds.getSpread();
            overUnder = odds.getOverUnder();
        }

        String location = Boolean.TRUE.equals(g.getNeutralSite()) ? "N" : "@";

        return new GameDayRow(
                g.getId(),
                toEasternTime(g.getGameDate()),
                away.getId(), away.getName(), away.getLogoUrl(), away.getAbbreviation(),
                g.getAwaySeed(), g.getAwayScore(),
                home.getId(), home.getName(), home.getLogoUrl(), home.getAbbreviation(),
                g.getHomeSeed(), g.getHomeScore(),
                g.getStatus(), location, result, g.getPeriods(),
                spread, overUnder,
                Boolean.TRUE.equals(g.getConferenceGame()),
                tournamentBadgeFormatter.format(g));
    }

    // ── DTO ────────────────────────────────────────────────────────────────────

    /** One game on the scoreboard. {@code result} is "HOME"/"AWAY"/"TIE"/null (FINAL only). */
    public record GameDayRow(
            Long gameId,
            LocalDateTime easternTime,
            Long awayTeamId, String awayName, String awayLogoUrl, String awayAbbr,
            Integer awaySeed, Integer awayScore,
            Long homeTeamId, String homeName, String homeLogoUrl, String homeAbbr,
            Integer homeSeed, Integer homeScore,
            Game.GameStatus status, String location, String result, Integer periods,
            BigDecimal spread, BigDecimal overUnder,
            boolean conferenceGame,
            TournamentBadgeFormatter.Badge tournamentBadge
    ) {
        public boolean homeWon() { return "HOME".equals(result); }
        public boolean awayWon() { return "AWAY".equals(result); }
        public boolean isFinal() { return status == Game.GameStatus.FINAL; }
        public boolean isScheduled() { return status == Game.GameStatus.SCHEDULED; }
        public boolean isLive() { return status == Game.GameStatus.IN_PROGRESS; }

        public String scoreDisplay(Integer s) { return s == null ? "" : String.valueOf(s); }

        public String statusLabel() {
            return switch (status) {
                case POSTPONED -> "PPD";
                case CANCELLED -> "Cancelled";
                default -> "";
            };
        }

        public String overtimeLabel() {
            if (periods == null || periods <= 2) return "";
            int ots = periods - 2;
            return ots == 1 ? "OT" : ots + "OT";
        }

        /** Spread shown from the favorite's side, e.g. "DUKE -3.5"; null when no line. */
        public String spreadDisplay() {
            if (spread == null) return null;
            double s = spread.doubleValue();
            if (s == 0) return "PK";
            String fav = s < 0 ? homeAbbr : awayAbbr;
            return fav + " " + String.format(java.util.Locale.US, "%.1f", -Math.abs(s));
        }
    }
}
