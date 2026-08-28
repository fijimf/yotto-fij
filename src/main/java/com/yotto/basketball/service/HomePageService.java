package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.GameStatus;
import com.yotto.basketball.news.NewsQueryService;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Composes the front page for the current {@link SeasonPhase}: an ordered list of panels, each a
 * fragment name plus its model. This is the ONLY place that knows per-phase panel ordering — the
 * template just iterates, and the future daily-digest email reuses the same composition.
 *
 * <p>Panel builders return {@code Optional.empty()} when there's nothing to show; the page never
 * renders an empty panel shell.
 */
@Service
public class HomePageService {

    /** One renderable panel: fragment file under {@code templates/fragments/home/} + its model. */
    public record HomePanel(String fragment, Map<String, Object> model) {}

    /** A ranked game row: the prediction card, its Eastern tip time, and its interest score. */
    public record HomeGameRow(PredictionCardView v, LocalDateTime tipEastern, double interest) {}

    public record HomePage(SeasonPhase phase, String heroTagline, List<HomePanel> panels) {}

    static final int PANEL_GAME_LIMIT = 6;
    private static final int NEWS_COMPACT_COUNT = 5;
    private static final int MIN_REPORT_CARD_GAMES = 5;
    /** Results/slate lookback/lookahead: beyond this the panels go quiet instead of showing stale games. */
    private static final int GAME_WINDOW_DAYS = 6;

    /** Front-page game panels feature these conferences (by current abbreviation) + the RPI top N. */
    private static final List<String> MARQUEE_CONFERENCE_ABBRS =
            List.of("ACC", "SEC", "Big Ten", "Big 12", "Big East");
    private static final int MARQUEE_RPI_TOP_N = 50;

    /** Off-season news: widen count and decay so the panel stays full when volume drops ~10×. */
    private static final int OFFSEASON_NEWS_COUNT_MULTIPLIER = 2;
    private static final double OFFSEASON_NEWS_HALF_LIFE_MULTIPLIER = 3.0;
    private static final int RANKINGS_PANEL_SIZE = 10;
    private static final int OPENING_NIGHT_HEADLINERS = 3;
    /** The archive chooser cycles through this many days starting Nov 1 (through early April). */
    private static final int ARCHIVE_SEASON_SPAN_DAYS = 158;

    private final SeasonPhaseService seasonPhaseService;
    private final GameRepository gameRepository;
    private final PredictionService predictionService;
    private final PredictionsPageService predictionsPageService;
    private final PredictionEvaluationRepository predictionEvaluationRepository;
    private final NewsQueryService newsQueryService;
    private final ConferenceMembershipRepository conferenceMembershipRepository;
    private final TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository;
    private final TeamPowerRatingSnapshotRepository teamPowerRatingSnapshotRepository;
    private final SeasonRepository seasonRepository;
    private final com.yotto.basketball.config.NewsProperties newsProperties;
    private final FavoriteTeamService favoriteTeamService;
    private final com.yotto.basketball.repository.SeasonStatisticsRepository seasonStatisticsRepository;
    private final BracketService bracketService;
    private final SeasonWrapService seasonWrapService;

    public HomePageService(SeasonPhaseService seasonPhaseService,
                           GameRepository gameRepository,
                           PredictionService predictionService,
                           PredictionsPageService predictionsPageService,
                           PredictionEvaluationRepository predictionEvaluationRepository,
                           NewsQueryService newsQueryService,
                           ConferenceMembershipRepository conferenceMembershipRepository,
                           TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository,
                           TeamPowerRatingSnapshotRepository teamPowerRatingSnapshotRepository,
                           SeasonRepository seasonRepository,
                           com.yotto.basketball.config.NewsProperties newsProperties,
                           FavoriteTeamService favoriteTeamService,
                           com.yotto.basketball.repository.SeasonStatisticsRepository seasonStatisticsRepository,
                           BracketService bracketService,
                           SeasonWrapService seasonWrapService) {
        this.seasonPhaseService = seasonPhaseService;
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
        this.predictionsPageService = predictionsPageService;
        this.predictionEvaluationRepository = predictionEvaluationRepository;
        this.newsQueryService = newsQueryService;
        this.conferenceMembershipRepository = conferenceMembershipRepository;
        this.teamSeasonStatSnapshotRepository = teamSeasonStatSnapshotRepository;
        this.teamPowerRatingSnapshotRepository = teamPowerRatingSnapshotRepository;
        this.seasonRepository = seasonRepository;
        this.newsProperties = newsProperties;
        this.favoriteTeamService = favoriteTeamService;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.bracketService = bracketService;
        this.seasonWrapService = seasonWrapService;
    }

    /** Anonymous build. */
    @Transactional(readOnly = true)
    public HomePage build() {
        return build(null);
    }

    /** @param userId the signed-in user, or null for anonymous visitors */
    @Transactional(readOnly = true)
    public HomePage build(Long userId) {
        SeasonPhase phase = seasonPhaseService.current();
        return switch (phase.phase()) {
            case IN_SEASON -> liveComposition(phase, userId);
            case POSTSEASON -> postseasonComposition(phase, userId);
            case EPILOGUE -> epilogueComposition(phase);
            case PRESEASON, OFFSEASON -> quietComposition(phase, userId);
        };
    }

    // ── IN_SEASON / POSTSEASON ────────────────────────────────────────────────

    private HomePage liveComposition(SeasonPhase phase, Long userId) {
        LocalDate today = phase.today();
        String modelKey = predictionsPageService.defaultModelKey();
        String modelLabel = predictionsPageService.modelLabel(modelKey);

        List<HomePanel> panels = new ArrayList<>();
        java.util.Set<Long> marquee = marqueeTeamIds(phase);

        Optional<LocalDate> resultsDate = resultsDate(today);
        List<HomeGameRow> allResults = resultsDate.map(d -> rankedRows(d, modelKey, true)).orElse(List.of());
        List<HomeGameRow> resultRows = marqueeFilter(allResults, marquee);

        Optional<LocalDate> slateDate = slateDate(today);
        List<HomeGameRow> allSlate = slateDate.map(d -> rankedRows(d, modelKey, false)).orElse(List.of());
        List<HomeGameRow> slateRows = marqueeFilter(allSlate, marquee);

        reportCardPanel(resultsDate.orElse(null), modelKey, modelLabel).ifPresent(panels::add);
        yourTeamsPanel(phase, userId, modelKey).ifPresent(panels::add);
        resultsPanel(resultsDate.orElse(null), resultRows, allResults.size(), today).ifPresent(panels::add);
        slatePanel(slateDate.orElse(null), slateRows, allSlate.size(), today, modelLabel).ifPresent(panels::add);
        newsPanel(true).ifPresent(panels::add);
        panels.add(explorePanel());

        // the tagline describes the WHOLE slate/night, not just the marquee slice
        return new HomePage(phase, liveTagline(today, allSlate, allResults), panels);
    }

    /**
     * The teams the front-page game panels feature: power-conference members plus the RPI top 50 as
     * of the latest stat snapshot. Falls back to everything when the filter would empty a panel
     * (a mid-major-only night) or when the data to build it doesn't exist.
     */
    private java.util.Set<Long> marqueeTeamIds(SeasonPhase phase) {
        if (phase.season() == null) return java.util.Set.of();
        Long seasonId = phase.season().getId();
        java.util.Set<Long> ids = new java.util.HashSet<>(
                conferenceMembershipRepository.findTeamIdsBySeasonAndConferenceAbbreviations(
                        seasonId, MARQUEE_CONFERENCE_ABBRS));
        LocalDate snapshotDate = teamSeasonStatSnapshotRepository.findMaxSnapshotDateOnOrBefore(seasonId, phase.today());
        if (snapshotDate != null) {
            ids.addAll(teamSeasonStatSnapshotRepository.findTopRpiTeamIds(seasonId, snapshotDate, MARQUEE_RPI_TOP_N));
        }
        return ids;
    }

    private static List<HomeGameRow> marqueeFilter(List<HomeGameRow> rows, java.util.Set<Long> marquee) {
        if (marquee.isEmpty()) return rows;
        List<HomeGameRow> filtered = rows.stream()
                .filter(r -> marquee.contains(r.v().homeTeam().id()) || marquee.contains(r.v().awayTeam().id()))
                .toList();
        return filtered.isEmpty() ? rows : filtered;
    }

    /** Most recent Eastern date with a FINAL game as of today, if within the lookback window. */
    private Optional<LocalDate> resultsDate(LocalDate today) {
        LocalDateTime endOfToday = EasternDates.dayWindowUtc(today)[1];
        return gameRepository.findMaxFinalGameDateBefore(endOfToday)
                .map(EasternDates::toEasternDate)
                .filter(d -> !d.isBefore(today.minusDays(GAME_WINDOW_DAYS)));
    }

    /** Today if it has playable (non-final, non-cancelled) games, else the next date that does. */
    private Optional<LocalDate> slateDate(LocalDate today) {
        for (LocalDate d = today; !d.isAfter(today.plusDays(GAME_WINDOW_DAYS)); ) {
            LocalDateTime[] w = EasternDates.dayWindowUtc(d);
            boolean playable = gameRepository.findInUtcWindow(w[0], w[1]).stream().anyMatch(HomePageService::isPlayable);
            if (playable) return Optional.of(d);
            Optional<LocalDate> next = gameRepository.findMinGameDateOnOrAfter(w[1]).map(EasternDates::toEasternDate);
            if (next.isEmpty() || next.get().isAfter(today.plusDays(GAME_WINDOW_DAYS))) return Optional.empty();
            d = next.get();
        }
        return Optional.empty();
    }

    private static boolean isPlayable(Game g) {
        return g.getStatus() == GameStatus.SCHEDULED || g.getStatus() == GameStatus.IN_PROGRESS;
    }

    /** All of a date's games as prediction cards, ranked by interest (descending). */
    private List<HomeGameRow> rankedRows(LocalDate date, String modelKey, boolean finalsOnly) {
        LocalDateTime[] w = EasternDates.dayWindowUtc(date);
        List<HomeGameRow> rows = new ArrayList<>();
        for (Game game : gameRepository.findInUtcWindow(w[0], w[1])) {
            if (finalsOnly ? game.getStatus() != GameStatus.FINAL : !isPlayable(game)) continue;
            PredictionCardView v = PredictionCardView.from(predictionService.buildPrediction(game), modelKey);
            if (finalsOnly && !v.isFinal()) continue;
            // betting_odds.spread is a handicap (negative = home favored); normalize to home margin
            // HERE, so HomeInterestScore never sees a raw book spread.
            Double bookHomeMargin = v.bookSpread() == null ? null : -v.bookSpread().doubleValue();
            double score = finalsOnly
                    ? HomeInterestScore.result(v.predHomeWinProb(), v.actualMargin(), v.predSpread(), bookHomeMargin)
                    : HomeInterestScore.upcoming(v.predSpread(), bookHomeMargin, v.predHomeWinProb());
            rows.add(new HomeGameRow(v, EasternDates.toEasternTime(game.getGameDate()), score));
        }
        rows.sort(Comparator.comparingDouble(HomeGameRow::interest).reversed());
        return rows;
    }

    private Optional<HomePanel> resultsPanel(LocalDate date, List<HomeGameRow> rows, int total, LocalDate today) {
        if (date == null || rows.isEmpty()) return Optional.empty();
        String title = date.equals(today) ? "Today's Scores"
                : date.equals(today.minusDays(1)) ? "Last Night" : "Latest Scores";
        return Optional.of(new HomePanel("results", model(
                "title", title, "date", date, "rows", cap(rows), "total", total)));
    }

    private Optional<HomePanel> slatePanel(LocalDate date, List<HomeGameRow> rows, int total,
                                           LocalDate today, String modelLabel) {
        if (date == null || rows.isEmpty()) return Optional.empty();
        String title = date.equals(today) ? "Tonight" : "Next Up";
        // pick by interest, but DISPLAY in tip order — a shuffled evening schedule reads broken
        List<HomeGameRow> display = new ArrayList<>(cap(rows));
        display.sort(Comparator.comparing(HomeGameRow::tipEastern));
        return Optional.of(new HomePanel("slate", model(
                "title", title, "date", date, "rows", display, "total", total, "modelLabel", modelLabel)));
    }

    private Optional<HomePanel> reportCardPanel(LocalDate resultsDate, String modelKey, String modelLabel) {
        if (resultsDate == null) return Optional.empty();
        PredictionEvaluationRepository.DailyRecord r =
                predictionEvaluationRepository.dailyRecord(evaluationModelType(modelKey), resultsDate);
        if (r == null || r.getSuN() < MIN_REPORT_CARD_GAMES) return Optional.empty();
        return Optional.of(new HomePanel("report-card", model(
                "date", resultsDate, "modelLabel", modelLabel,
                "suWins", r.getSuWins(), "suLosses", r.getSuN() - r.getSuWins(),
                "atsWins", r.getAtsWins(), "atsLosses", r.getAtsN() - r.getAtsWins(),
                "hasAts", r.getAtsN() > 0)));
    }

    /** {@code ml:<slug>} card keys map to {@code ML:<slug>} evaluation rows; classical maps to MASSEY. */
    private static String evaluationModelType(String modelKey) {
        if (modelKey != null && modelKey.startsWith(PredictionCardView.ML_PREFIX)) {
            return PredictionEvaluationService.ML_TYPE_PREFIX
                    + modelKey.substring(PredictionCardView.ML_PREFIX.length());
        }
        return "MASSEY";
    }

    private String liveTagline(LocalDate today, List<HomeGameRow> slateRows, List<HomeGameRow> resultRows) {
        if (!slateRows.isEmpty()) {
            long upsets = slateRows.stream().filter(r -> {
                Double model = r.v().predSpread();
                Double book = r.v().bookSpread() == null ? null : -r.v().bookSpread().doubleValue();
                return model != null && book != null && Math.signum(model) != Math.signum(book);
            }).count();
            int n = slateRows.size();
            // stable per day, no JS: alternate phrasing by date parity
            String base = today.toEpochDay() % 2 == 0
                    ? n + (n == 1 ? " game" : " games") + " tonight"
                    : n + (n == 1 ? " game" : " games") + " on the slate";
            return upsets > 0
                    ? base + " — the model likes " + upsets + (upsets == 1 ? " upset." : " upsets.")
                    : base + ".";
        }
        if (!resultRows.isEmpty()) {
            return "Fresh scores from " + resultRows.size()
                    + (resultRows.size() == 1 ? " game." : " games.");
        }
        return "College Basketball · Quantified";
    }

    // ── PRESEASON / OFFSEASON / EPILOGUE ──────────────────────────────────────

    private HomePage quietComposition(SeasonPhase phase, Long userId) {
        List<HomePanel> panels = new ArrayList<>();
        String modelKey = predictionsPageService.defaultModelKey();
        switch (phase.phase()) {
            case PRESEASON -> {
                preseasonSplitPanel(phase).ifPresent(panels::add);
                openingNightPanel(phase).ifPresent(panels::add);
                newsPanel(false).ifPresent(panels::add);
                yourTeamsPanel(phase, userId, modelKey).ifPresent(panels::add);
            }
            default -> { // OFFSEASON
                offseasonNewsPanel().ifPresent(panels::add);
                historyPanel(phase).ifPresent(panels::add);
                yourTeamsPanel(phase, userId, modelKey).ifPresent(panels::add);
            }
        }
        panels.add(explorePanel());
        return new HomePage(phase, quietTagline(phase), panels);
    }

    // ── POSTSEASON ────────────────────────────────────────────────────────────

    /** A tournament game row: prediction card + tip + seeds + round. */
    public record TourneyRow(PredictionCardView v, LocalDateTime tipEastern,
                             Integer homeSeed, Integer awaySeed, String round, double interest) {}

    /** A conference-title-game row for Selection Sunday. */
    public record ConfChampRow(PredictionCardView v, LocalDateTime tipEastern, String tournamentName,
                               String winnerName, Integer winnerSeed, String pick) {}

    private HomePage postseasonComposition(SeasonPhase phase, Long userId) {
        LocalDate today = phase.today();
        String modelKey = predictionsPageService.defaultModelKey();
        String modelLabel = predictionsPageService.modelLabel(modelKey);
        List<Game> ncaa = phase.season() == null ? List.of()
                : gameRepository.findBySeasonIdAndTournamentTypeWithDetails(
                        phase.season().getId(), Game.TournamentType.NCAA_TOURNAMENT);

        List<HomePanel> panels = new ArrayList<>();
        resultsDate(today).flatMap(d -> reportCardPanel(d, modelKey, modelLabel)).ifPresent(panels::add);
        yourTeamsPanel(phase, userId, modelKey).ifPresent(panels::add);
        bracketPanel(phase).ifPresent(panels::add);
        if (phase.selectionSunday()) {
            confChampDayPanel(phase, modelKey, seedByTeam(ncaa)).ifPresent(panels::add);
        }
        tourneyResultsPanel(phase, ncaa, modelKey).ifPresent(panels::add);
        tourneySlatePanel(phase, ncaa, modelKey, modelLabel).ifPresent(panels::add);
        newsPanel(true).ifPresent(panels::add);
        panels.add(explorePanel());

        return new HomePage(phase, postseasonTagline(today, ncaa), panels);
    }

    private Optional<HomePanel> bracketPanel(SeasonPhase phase) {
        if (phase.season() == null) return Optional.empty();
        return bracketService.buildBracket(phase.season().getYear())
                .map(b -> new HomePanel("bracket", model("bracket", b, "year", phase.season().getYear())));
    }

    /** Most recent day (≤ today) with FINAL NCAA games, plus that day's other-postseason scores. */
    private Optional<HomePanel> tourneyResultsPanel(SeasonPhase phase, List<Game> ncaa, String modelKey) {
        LocalDate today = phase.today();
        Optional<LocalDate> day = ncaa.stream()
                .filter(g -> g.getStatus() == GameStatus.FINAL)
                .map(g -> EasternDates.toEasternDate(g.getGameDate()))
                .filter(d -> !d.isAfter(today))
                .max(LocalDate::compareTo);
        if (day.isEmpty()) return Optional.empty();

        List<TourneyRow> rows = ncaa.stream()
                .filter(g -> g.getStatus() == GameStatus.FINAL
                        && EasternDates.toEasternDate(g.getGameDate()).equals(day.get()))
                .sorted(Comparator.comparing(Game::getGameDate))
                .map(g -> toTourneyRow(g, modelKey))
                .toList();

        // NIT/CBI/Crown etc.: scores reported, nothing else — collapsed below the NCAA results
        LocalDateTime[] w = EasternDates.dayWindowUtc(day.get());
        List<Game> other = gameRepository.findInUtcWindow(w[0], w[1]).stream()
                .filter(g -> g.getStatus() == GameStatus.FINAL && isOtherPostseason(g))
                .sorted(Comparator.comparing(Game::getGameDate))
                .toList();
        List<Map<String, Object>> otherRows = other.stream()
                .map(g -> Map.<String, Object>of(
                        "label", (g.getTournamentName() != null ? g.getTournamentName() + ": " : "")
                                + g.getAwayTeam().getName() + " " + g.getAwayScore()
                                + ", " + g.getHomeTeam().getName() + " " + g.getHomeScore(),
                        "gameId", g.getId()))
                .toList();

        String title = day.get().equals(today) ? "Today's Tournament Scores" : "Tournament Scores";
        return Optional.of(new HomePanel("tourney-results", model(
                "title", title, "date", day.get(), "rows", rows, "otherRows", otherRows)));
    }

    /** Next day (≥ today) with playable NCAA games: the survival slate, v1. */
    private Optional<HomePanel> tourneySlatePanel(SeasonPhase phase, List<Game> ncaa,
                                                  String modelKey, String modelLabel) {
        LocalDate today = phase.today();
        Optional<LocalDate> day = ncaa.stream()
                .filter(HomePageService::isPlayable)
                .map(g -> EasternDates.toEasternDate(g.getGameDate()))
                .filter(d -> !d.isBefore(today))
                .min(LocalDate::compareTo);
        if (day.isEmpty()) return Optional.empty();
        List<TourneyRow> rows = ncaa.stream()
                .filter(g -> isPlayable(g) && EasternDates.toEasternDate(g.getGameDate()).equals(day.get()))
                .sorted(Comparator.comparing(Game::getGameDate))
                .map(g -> toTourneyRow(g, modelKey))
                .toList();
        String title = day.get().equals(today) ? "Tournament Tonight" : "Next Round";
        return Optional.of(new HomePanel("tourney-slate", model(
                "title", title, "date", day.get(), "rows", rows, "modelLabel", modelLabel)));
    }

    /** Selection Sunday only: today's conference title games, winners badged with their auto-bid. */
    private Optional<HomePanel> confChampDayPanel(SeasonPhase phase, String modelKey,
                                                  Map<Long, Integer> seedByTeam) {
        LocalDateTime[] w = EasternDates.dayWindowUtc(phase.today());
        List<ConfChampRow> rows = new ArrayList<>();
        for (Game g : gameRepository.findInUtcWindow(w[0], w[1])) {
            if (g.getTournamentType() != Game.TournamentType.CONFERENCE_TOURNAMENT) continue;
            String round = g.getTournamentRound() == null ? "" : g.getTournamentRound().toLowerCase(Locale.ROOT);
            if (!round.contains("final") && !round.contains("championship")) continue;
            if (g.getStatus() == GameStatus.CANCELLED || g.getStatus() == GameStatus.POSTPONED) continue;
            PredictionCardView v = PredictionCardView.from(predictionService.buildPrediction(g), modelKey);
            String winnerName = null;
            Integer winnerSeed = null;
            if (v.isFinal()) {
                Team winner = g.getHomeScore() > g.getAwayScore() ? g.getHomeTeam() : g.getAwayTeam();
                winnerName = winner.getName();
                winnerSeed = seedByTeam.get(winner.getId());
            }
            String pick = null;
            if (!v.isFinal() && v.predSpread() != null) {
                PredictionResult.TeamSummary fav = v.predSpread() >= 0 ? v.homeTeam() : v.awayTeam();
                pick = (fav.abbreviation() != null ? fav.abbreviation() : fav.name())
                        + " −" + String.format(Locale.US, "%.1f", Math.abs(v.predSpread()));
            }
            rows.add(new ConfChampRow(v, EasternDates.toEasternTime(g.getGameDate()),
                    g.getTournamentName(), winnerName, winnerSeed, pick));
        }
        if (rows.isEmpty()) return Optional.empty();
        rows.sort(Comparator.comparing(ConfChampRow::tipEastern));
        return Optional.of(new HomePanel("conf-champ-day", model("rows", rows)));
    }

    private TourneyRow toTourneyRow(Game g, String modelKey) {
        PredictionCardView v = PredictionCardView.from(predictionService.buildPrediction(g), modelKey);
        Double bookHomeMargin = v.bookSpread() == null ? null : -v.bookSpread().doubleValue();
        double interest = g.getStatus() == GameStatus.FINAL
                ? HomeInterestScore.result(v.predHomeWinProb(), v.actualMargin(), v.predSpread(), bookHomeMargin)
                : HomeInterestScore.upcoming(v.predSpread(), bookHomeMargin, v.predHomeWinProb());
        return new TourneyRow(v, EasternDates.toEasternTime(g.getGameDate()),
                g.getHomeSeed(), g.getAwaySeed(), g.getTournamentRound(), interest);
    }

    /** Team id → NCAA seed, from any tournament game the team is seeded in. */
    private static Map<Long, Integer> seedByTeam(List<Game> ncaa) {
        Map<Long, Integer> seeds = new java.util.HashMap<>();
        for (Game g : ncaa) {
            if (g.getHomeSeed() != null) seeds.putIfAbsent(g.getHomeTeam().getId(), g.getHomeSeed());
            if (g.getAwaySeed() != null) seeds.putIfAbsent(g.getAwayTeam().getId(), g.getAwaySeed());
        }
        return seeds;
    }

    private static boolean isOtherPostseason(Game g) {
        return g.getTournamentType() == Game.TournamentType.NIT
                || g.getTournamentType() == Game.TournamentType.CBI
                || g.getTournamentType() == Game.TournamentType.CROWN
                || g.getTournamentType() == Game.TournamentType.OTHER_POSTSEASON;
    }

    private String postseasonTagline(LocalDate today, List<Game> ncaa) {
        List<Game> todays = ncaa.stream()
                .filter(g -> EasternDates.toEasternDate(g.getGameDate()).equals(today)
                        && g.getStatus() != GameStatus.CANCELLED && g.getStatus() != GameStatus.POSTPONED)
                .toList();
        if (!todays.isEmpty()) {
            String round = todays.get(0).getTournamentRound();
            int n = todays.size();
            return (round != null ? round : "Tournament") + ": "
                    + n + (n == 1 ? " game today." : " games today.");
        }
        Optional<Game> next = ncaa.stream()
                .filter(g -> isPlayable(g) && EasternDates.toEasternDate(g.getGameDate()).isAfter(today))
                .min(Comparator.comparing(Game::getGameDate));
        if (next.isPresent()) {
            String round = next.get().getTournamentRound();
            String day = EasternDates.toEasternDate(next.get().getGameDate())
                    .getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.US);
            return (round != null ? round : "The next round") + " starts " + day + ".";
        }
        return "The bracket is live.";
    }

    // ── EPILOGUE ──────────────────────────────────────────────────────────────

    private HomePage epilogueComposition(SeasonPhase phase) {
        List<HomePanel> panels = new ArrayList<>();
        if (phase.season() != null) {
            String modelKey = predictionsPageService.defaultModelKey();
            SeasonWrapService.SeasonWrap wrap = seasonWrapService.wrap(phase.season(),
                    evaluationModelType(modelKey), probEvaluationModelType(modelKey));
            if (!wrap.stats().isEmpty()) {
                panels.add(new HomePanel("season-wrap", model("wrap", wrap)));
            }
            seasonWrapService.championshipGame(phase.season())
                    .map(g -> new HomePanel("championship-result", model(
                            "row", toTourneyRow(g, modelKey), "year", phase.season().getYear())))
                    .ifPresent(panels::add);
        }
        newsPanel(false).ifPresent(panels::add);
        panels.add(explorePanel());
        return new HomePage(phase, quietTagline(phase), panels);
    }

    /** Win-prob evaluation rows: the ML slug when one is default, else classical Bradley-Terry. */
    private static String probEvaluationModelType(String modelKey) {
        if (modelKey != null && modelKey.startsWith(PredictionCardView.ML_PREFIX)) {
            return PredictionEvaluationService.ML_TYPE_PREFIX
                    + modelKey.substring(PredictionCardView.ML_PREFIX.length());
        }
        return "BRADLEY_TERRY";
    }

    private String quietTagline(SeasonPhase phase) {
        LocalDate today = phase.today();
        LocalDate firstGame = phase.firstGameDate();
        if (phase.phase() == SeasonPhase.Phase.EPILOGUE && phase.season() != null) {
            return "That's a wrap on the " + phase.season().getYear() + " season.";
        }
        if (firstGame != null && firstGame.isAfter(today)) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, firstGame);
            return "Tip-off in " + days + (days == 1 ? " day — " : " days — ")
                    + firstGame.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
                    + " " + firstGame.getDayOfMonth() + ".";
        }
        if (phase.phase() == SeasonPhase.Phase.PRESEASON) {
            return "The season is almost here.";
        }
        // deep off-season: rotate a few archive-flavored lines, stable per day
        return switch ((int) (today.toEpochDay() % 3)) {
            case 0 -> "The archive never sleeps.";
            case 1 -> "See you in November.";
            default -> "College Basketball · Quantified";
        };
    }

    /** One row of the never-too-early rankings panel. */
    public record RankRow(int rank, Long teamId, String name, String logoUrl, Double rating) {}

    private record RankingsData(List<RankRow> rows, int year) {}

    /**
     * The preseason lead panel: never-too-early top 10 on the left, a live countdown clock to the
     * opening tip on the right. Present when either half has data.
     */
    private Optional<HomePanel> preseasonSplitPanel(SeasonPhase phase) {
        Optional<RankingsData> rankings = rankingRows(phase);
        Long tipMs = null;
        String tipLabel = null;
        if (phase.season() != null && phase.firstGameDate() != null
                && phase.firstGameDate().isAfter(phase.today())) {
            Optional<LocalDateTime> tipUtc = gameRepository.findMinGameDate(phase.season().getId());
            if (tipUtc.isPresent()) {
                tipMs = tipUtc.get().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                LocalDateTime tipEastern = EasternDates.toEasternTime(tipUtc.get());
                tipLabel = tipEastern.format(java.time.format.DateTimeFormatter
                        .ofPattern("EEEE, MMMM d · h:mm a 'ET'", java.util.Locale.US));
            }
        }
        if (rankings.isEmpty() && tipMs == null) return Optional.empty();
        return Optional.of(new HomePanel("preseason-split", model(
                "title", "Never-Too-Early Top " + rankings.map(r -> r.rows().size()).orElse(0),
                "subtitle", rankings.map(r ->
                        "Where last season left off — final " + r.year() + " Massey ratings").orElse(""),
                "rows", rankings.map(RankingsData::rows).orElse(List.of()),
                "tipInstantMs", tipMs,
                "tipLabel", tipLabel)));
    }

    /**
     * The most recent completed Massey ratings, labeled honestly as where last season left off.
     * Prefers the phase's season (a just-finished one during forced previews), else the season
     * before it (the real preseason case, where the upcoming season has no data).
     */
    private Optional<RankingsData> rankingRows(SeasonPhase phase) {
        if (phase.season() == null) return Optional.empty();
        for (Integer year : List.of(phase.season().getYear(), phase.season().getYear() - 1)) {
            Optional<Season> s = seasonRepository.findByYear(year);
            if (s.isEmpty()) continue;
            Optional<LocalDate> snap = teamPowerRatingSnapshotRepository
                    .findLatestSnapshotDate(s.get().getId(), "MASSEY");
            if (snap.isEmpty()) continue;
            List<RankRow> rows = teamPowerRatingSnapshotRepository
                    .findBySeasonModelAndDate(s.get().getId(), "MASSEY", snap.get()).stream()
                    .limit(RANKINGS_PANEL_SIZE)
                    .map(HomePageService::toRankRow)
                    .toList();
            if (rows.isEmpty()) continue;
            return Optional.of(new RankingsData(rows, year));
        }
        return Optional.empty();
    }

    private static RankRow toRankRow(TeamPowerRatingSnapshot s) {
        Team t = s.getTeam();
        int rank = s.getRank() != null ? s.getRank() : 0;
        return new RankRow(rank, t.getId(), t.getName(), t.getLogoUrl(), s.getRating());
    }

    /** Opening-night teaser once the upcoming schedule is scraped: date, game count, headliners. */
    private Optional<HomePanel> openingNightPanel(SeasonPhase phase) {
        LocalDate opener = phase.firstGameDate();
        if (opener == null || !opener.isAfter(phase.today())) return Optional.empty();
        LocalDateTime[] w = EasternDates.dayWindowUtc(opener);
        List<HomeGameRow> games = new ArrayList<>();
        for (Game game : gameRepository.findInUtcWindow(w[0], w[1])) {
            if (!isPlayable(game)) continue;
            PredictionCardView v = PredictionCardView.from(
                    predictionService.buildPrediction(game), predictionsPageService.defaultModelKey());
            games.add(new HomeGameRow(v, EasternDates.toEasternTime(game.getGameDate()), 0));
        }
        if (games.isEmpty()) return Optional.empty();
        int total = games.size();
        List<HomeGameRow> headliners = marqueeFilter(games, marqueeHeadlinerIds(phase));
        headliners = headliners.subList(0, Math.min(OPENING_NIGHT_HEADLINERS, headliners.size()));
        headliners = new ArrayList<>(headliners);
        headliners.sort(Comparator.comparing(HomeGameRow::tipEastern));
        return Optional.of(new HomePanel("opening-night", model(
                "date", opener, "total", total, "rows", headliners)));
    }

    /** Headliner filter for the opener: last completed season's Massey top 25. */
    private java.util.Set<Long> marqueeHeadlinerIds(SeasonPhase phase) {
        if (phase.season() == null) return java.util.Set.of();
        for (Integer year : List.of(phase.season().getYear(), phase.season().getYear() - 1)) {
            Optional<Season> s = seasonRepository.findByYear(year);
            if (s.isEmpty()) continue;
            Optional<LocalDate> snap = teamPowerRatingSnapshotRepository
                    .findLatestSnapshotDate(s.get().getId(), "MASSEY");
            if (snap.isEmpty()) continue;
            return teamPowerRatingSnapshotRepository
                    .findBySeasonModelAndDate(s.get().getId(), "MASSEY", snap.get()).stream()
                    .limit(25)
                    .map(r -> r.getTeam().getId())
                    .collect(java.util.stream.Collectors.toSet());
        }
        return java.util.Set.of();
    }

    private Optional<HomePanel> offseasonNewsPanel() {
        List<NewsQueryService.NewsCard> cards = newsQueryService.frontPage(
                newsProperties.getRanking().getFrontPageCount() * OFFSEASON_NEWS_COUNT_MULTIPLIER,
                newsProperties.getRanking().getHalfLifeHours() * OFFSEASON_NEWS_HALF_LIFE_MULTIPLIER);
        if (cards.isEmpty()) return Optional.empty();
        return Optional.of(new HomePanel("news", model("cards", cards, "compact", false)));
    }

    /** The archive view backing the "this day in season history" panel. */
    public record HistoryView(int seasonYear, LocalDate gameDate, Long gameId,
                              String homeName, String awayName, String homeLogo, String awayLogo,
                              Integer homeScore, Integer awayScore, boolean neutralSite,
                              String framing) {}

    /**
     * Off-season days have no basketball history of their own, so the chooser walks the archive:
     * today's epoch day picks a stable in-season month/day (Nov 1 → early April span).
     */
    public static java.time.MonthDay archiveMonthDay(LocalDate today) {
        LocalDate reference = LocalDate.of(2025, 11, 1)
                .plusDays(Math.floorMod(today.toEpochDay(), ARCHIVE_SEASON_SPAN_DAYS));
        return java.time.MonthDay.of(reference.getMonth(), reference.getDayOfMonth());
    }

    /** Sundays feature the model's biggest miss on the chosen date; other days, the closest game. */
    private Optional<HomePanel> historyPanel(SeasonPhase phase) {
        java.time.MonthDay md = archiveMonthDay(phase.today());
        boolean missDay = phase.today().getDayOfWeek() == DayOfWeek.SUNDAY;

        Optional<HistoryView> view = Optional.empty();
        if (missDay) {
            view = predictionEvaluationRepository
                    .findBiggestMissOnMonthDay("MASSEY", md.getMonthValue(), md.getDayOfMonth())
                    .flatMap(miss -> gameRepository.findByIdWithDetails(miss.getGameId())
                            .map(g -> toHistoryView(g, "The model missed this one by "
                                    + String.format(java.util.Locale.US, "%.1f", Math.abs(miss.getSpreadError()))
                                    + " points.")));
        }
        if (view.isEmpty()) {
            view = gameRepository.findClosestGameIdOnMonthDay(md.getMonthValue(), md.getDayOfMonth())
                    .flatMap(gameRepository::findByIdWithDetails)
                    .map(g -> toHistoryView(g, closestFraming(g)));
        }
        return view.map(v -> new HomePanel("history", model(
                "title", HISTORY_TITLE, "view", v)));
    }

    private HistoryView toHistoryView(Game g, String framing) {
        return new HistoryView(
                g.getSeason() != null ? g.getSeason().getYear() : 0,
                EasternDates.toEasternDate(g.getGameDate()), g.getId(),
                g.getHomeTeam().getName(), g.getAwayTeam().getName(),
                g.getHomeTeam().getLogoUrl(), g.getAwayTeam().getLogoUrl(),
                g.getHomeScore(), g.getAwayScore(),
                Boolean.TRUE.equals(g.getNeutralSite()), framing);
    }

    private String closestFraming(Game g) {
        int margin = Math.abs(g.getHomeScore() - g.getAwayScore());
        String base = margin == 0 ? "Decided at the wire."
                : "Decided by " + margin + (margin == 1 ? " point." : " points.");
        return predictionEvaluationRepository.findByGameId(g.getId()).stream()
                .filter(pe -> "MASSEY".equals(pe.getModelType()) && pe.getPredictedSpread() != null)
                .findFirst()
                .map(pe -> {
                    String fav = pe.getPredictedSpread() >= 0
                            ? g.getHomeTeam().getName() : g.getAwayTeam().getName();
                    return base + " The model had " + fav + " by "
                            + String.format(java.util.Locale.US, "%.1f", Math.abs(pe.getPredictedSpread())) + ".";
                })
                .orElse(base);
    }

    // ── Your Teams (registered users) ─────────────────────────────────────────

    /** One followed-team row; nullable fields simply don't render. */
    public record YourTeamRow(Long teamId, String name, String logoUrl, Integer rank,
                              String record, String streak,
                              String nextGame, Long nextGameId, String pick,
                              String newsTitle, String newsUrl, String postseasonNote) {}

    /**
     * Signed-in users with favorites get the strip; signed-in users without favorites (and
     * anonymous visitors during PRESEASON/IN_SEASON) get a one-line teaser instead.
     */
    private Optional<HomePanel> yourTeamsPanel(SeasonPhase phase, Long userId, String modelKey) {
        boolean teaserPhase = phase.phase() == SeasonPhase.Phase.IN_SEASON
                || phase.phase() == SeasonPhase.Phase.POSTSEASON
                || phase.phase() == SeasonPhase.Phase.PRESEASON;
        if (userId == null) {
            return teaserPhase
                    ? Optional.of(new HomePanel("your-teams", model("teaser", "anonymous")))
                    : Optional.empty();
        }
        List<Team> favorites = favoriteTeamService.getFavorites(userId);
        if (favorites.isEmpty()) {
            return teaserPhase
                    ? Optional.of(new HomePanel("your-teams", model("teaser", "no-favorites")))
                    : Optional.empty();
        }
        List<YourTeamRow> rows = favorites.stream()
                .map(t -> yourTeamRow(t, phase, modelKey))
                .toList();
        return Optional.of(new HomePanel("your-teams", model("teaser", null, "rows", rows)));
    }

    private YourTeamRow yourTeamRow(Team team, SeasonPhase phase, String modelKey) {
        Long seasonId = phase.season() != null ? phase.season().getId() : null;
        LocalDateTime nowUtc = EasternDates.dayWindowUtc(phase.today())[0];

        Integer rank = null;
        String record = null;
        String streak = null;
        String nextGame = null;
        Long nextGameId = null;
        String pick = null;
        String newsTitle = null;
        String newsUrl = null;

        boolean live = phase.phase() == SeasonPhase.Phase.IN_SEASON
                || phase.phase() == SeasonPhase.Phase.POSTSEASON;

        if (seasonId != null && live) {
            rank = teamPowerRatingSnapshotRepository
                    .findLatestForTeam(team.getId(), seasonId, "MASSEY", org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().map(TeamPowerRatingSnapshot::getRank).orElse(null);
            SeasonStatistics stats = seasonStatisticsRepository
                    .findByTeamIdAndSeasonId(team.getId(), seasonId).orElse(null);
            if (stats != null) {
                // calc values (from our own game rows) preferred over scraped standings
                Integer wins = stats.getCalcWins() != null ? stats.getCalcWins() : stats.getWins();
                Integer losses = stats.getCalcLosses() != null ? stats.getCalcLosses() : stats.getLosses();
                if (wins != null && losses != null) {
                    record = wins + "–" + losses;
                }
                Integer s = stats.getCalcStreak() != null ? stats.getCalcStreak() : stats.getStreak();
                if (s != null && s != 0) {
                    streak = (s > 0 ? "W" : "L") + Math.abs(s);
                }
            }
        }
        if (live || phase.phase() == SeasonPhase.Phase.PRESEASON) {
            Game next = gameRepository.findNextScheduledForTeam(team.getId(), nowUtc,
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().orElse(null);
            if (next != null) {
                boolean home = next.getHomeTeam().getId().equals(team.getId());
                Team opp = home ? next.getAwayTeam() : next.getHomeTeam();
                LocalDateTime tip = EasternDates.toEasternTime(next.getGameDate());
                nextGame = tip.format(java.time.format.DateTimeFormatter
                        .ofPattern("EEE h:mm a", java.util.Locale.US))
                        + (home ? " vs " : " at ") + opp.getName();
                nextGameId = next.getId();
                PredictionCardView v = PredictionCardView.from(predictionService.buildPrediction(next), modelKey);
                if (v.predSpread() != null) {
                    PredictionResult.TeamSummary fav = v.predSpread() >= 0 ? v.homeTeam() : v.awayTeam();
                    String abbr = fav.abbreviation() != null ? fav.abbreviation() : fav.name();
                    pick = abbr + " −" + String.format(java.util.Locale.US, "%.1f", Math.abs(v.predSpread()));
                }
            }
        }
        if (!live) {
            NewsQueryService.NewsCard card = newsQueryService.teamNews(team.getId(), 1)
                    .stream().findFirst().orElse(null);
            if (card != null) {
                newsTitle = card.title();
                newsUrl = card.url();
            }
        }
        String postseasonNote = null;
        if (phase.phase() == SeasonPhase.Phase.POSTSEASON && seasonId != null && nextGame == null) {
            postseasonNote = eliminationNote(team, seasonId);
        }
        return new YourTeamRow(team.getId(), team.getName(), team.getLogoUrl(), rank,
                record, streak, nextGame, nextGameId, pick, newsTitle, newsUrl, postseasonNote);
    }

    /** "Eliminated by X (2nd Round)" once a followed team's tournament is over; null if not in field. */
    private String eliminationNote(Team team, Long seasonId) {
        List<Game> tourneyGames = gameRepository.findByTeamAndSeasonWithDetails(team.getId(), seasonId).stream()
                .filter(g -> g.getTournamentType() == Game.TournamentType.NCAA_TOURNAMENT)
                .toList();
        if (tourneyGames.isEmpty()) return null;
        if (tourneyGames.stream().anyMatch(HomePageService::isPlayable)) return null;
        Game lastGame = tourneyGames.stream()
                .filter(g -> g.getStatus() == GameStatus.FINAL
                        && g.getHomeScore() != null && g.getAwayScore() != null)
                .max(Comparator.comparing(Game::getGameDate)).orElse(null);
        if (lastGame == null) return null;
        boolean home = lastGame.getHomeTeam().getId().equals(team.getId());
        boolean won = home ? lastGame.getHomeScore() > lastGame.getAwayScore()
                : lastGame.getAwayScore() > lastGame.getHomeScore();
        if (won) return "Still dancing.";
        Team opp = home ? lastGame.getAwayTeam() : lastGame.getHomeTeam();
        return "Eliminated by " + opp.getName()
                + (lastGame.getTournamentRound() != null ? " (" + lastGame.getTournamentRound() + ")" : "");
    }

    // ── Daily digest (email rendering of the same composition) ────────────────

    /** One digest line: text + optional muted detail + optional site-relative link path. */
    public record DigestLine(String text, String detail, String path) {}

    public record DigestSection(String title, List<DigestLine> lines) {}

    /**
     * The front page pared down for email. Built from the SAME panels as the web page — the email
     * has its own markup but no queries of its own. {@code hasContent} gates sending: archive
     * trivia alone doesn't justify an email, so the history panel never counts toward it.
     */
    public record DigestView(LocalDate date, String tagline, List<DigestSection> sections,
                             List<NewsQueryService.NewsCard> news, boolean hasContent) {}

    private static final int DIGEST_GAME_LINES = 4;
    private static final int DIGEST_NEWS_LINES = 3;
    private static final String HISTORY_TITLE = "Hits and Misses";

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public DigestView buildDigest(Long userId) {
        HomePage page = build(userId);
        List<DigestSection> sections = new ArrayList<>();
        List<NewsQueryService.NewsCard> news = List.of();
        boolean meaningful = false;

        for (HomePanel p : page.panels()) {
            Map<String, Object> m = p.model();
            switch (p.fragment()) {
                case "your-teams" -> {
                    if (m.get("teaser") != null) break;
                    List<DigestLine> lines = ((List<YourTeamRow>) m.get("rows")).stream()
                            .map(HomePageService::digestTeamLine).toList();
                    if (!lines.isEmpty()) {
                        sections.add(new DigestSection("Your Teams", lines));
                        meaningful = true;
                    }
                }
                case "report-card" -> {
                    sections.add(new DigestSection("Model Report Card", List.of(new DigestLine(
                            m.get("suWins") + "–" + m.get("suLosses") + " straight up · "
                                    + m.get("atsWins") + "–" + m.get("atsLosses") + " against the spread",
                            m.get("modelLabel").toString(), "/models/compare"))));
                    meaningful = true;
                }
                case "results", "tourney-results" -> {
                    List<DigestLine> lines = ((List<?>) m.get("rows")).stream()
                            .limit(DIGEST_GAME_LINES)
                            .map(HomePageService::digestResultLine).toList();
                    if (!lines.isEmpty()) {
                        sections.add(new DigestSection(String.valueOf(m.get("title")), lines));
                        meaningful = true;
                    }
                }
                case "slate", "tourney-slate", "opening-night" -> {
                    List<DigestLine> lines = ((List<?>) m.get("rows")).stream()
                            .limit(DIGEST_GAME_LINES)
                            .map(HomePageService::digestUpcomingLine).toList();
                    if (!lines.isEmpty()) {
                        sections.add(new DigestSection(
                                "opening-night".equals(p.fragment()) ? "Opening Night" : String.valueOf(m.get("title")),
                                lines));
                        meaningful = true;
                    }
                }
                case "season-wrap" -> {
                    SeasonWrapService.SeasonWrap wrap = (SeasonWrapService.SeasonWrap) m.get("wrap");
                    sections.add(new DigestSection("The " + wrap.year() + " Season, Wrapped",
                            wrap.stats().stream().map(s -> new DigestLine(
                                    s.label() + ": " + s.headline(), s.detail(),
                                    s.gameId() != null ? "/games/" + s.gameId() : null)).toList()));
                    meaningful = true;
                }
                case "history" -> {
                    HistoryView v = (HistoryView) m.get("view");
                    String sep = v.neutralSite() ? " vs " : " @ ";
                    sections.add(new DigestSection(HISTORY_TITLE, List.of(new DigestLine(
                            v.awayName() + " " + v.awayScore() + sep + v.homeName() + " " + v.homeScore()
                                    + " (" + v.gameDate() + ")",
                            v.framing(), "/games/" + v.gameId()))));
                    // deliberately NOT meaningful: archive trivia alone doesn't justify an email
                }
                case "news" -> news = ((List<NewsQueryService.NewsCard>) m.get("cards")).stream()
                        .limit(DIGEST_NEWS_LINES).toList();
                default -> { /* bracket, splits, chips: web-only */ }
            }
        }
        boolean hasContent = meaningful || !news.isEmpty();
        return new DigestView(page.phase().today(), page.heroTagline(), sections, news, hasContent);
    }

    private static DigestLine digestTeamLine(YourTeamRow r) {
        StringBuilder detail = new StringBuilder();
        if (r.record() != null) detail.append(r.record());
        if (r.streak() != null) detail.append(detail.isEmpty() ? "" : " ").append(r.streak());
        if (r.nextGame() != null) {
            detail.append(detail.isEmpty() ? "" : " · ").append("Next: ").append(r.nextGame());
            if (r.pick() != null) detail.append(" · ").append(r.pick());
        }
        if (r.postseasonNote() != null) detail.append(detail.isEmpty() ? "" : " · ").append(r.postseasonNote());
        if (detail.isEmpty() && r.newsTitle() != null) detail.append(r.newsTitle());
        String name = r.rank() != null ? "#" + r.rank() + " " + r.name() : r.name();
        return new DigestLine(name, detail.toString(), "/teams/" + r.teamId());
    }

    private static DigestLine digestResultLine(Object row) {
        PredictionCardView v = cardOf(row);
        String seeds = row instanceof TourneyRow t
                ? seedPrefix(t.awaySeed()) + v.awayTeam().name() + " " + v.awayScore()
                + " @ " + seedPrefix(t.homeSeed()) + v.homeTeam().name() + " " + v.homeScore()
                : v.awayTeam().name() + " " + v.awayScore() + " @ " + v.homeTeam().name() + " " + v.homeScore();
        String detail = v.winnerCorrect() == null ? null
                : (v.winnerCorrect() ? "model called it" : "model missed it");
        return new DigestLine(seeds, detail, "/games/" + v.gameId());
    }

    private static DigestLine digestUpcomingLine(Object row) {
        PredictionCardView v = cardOf(row);
        LocalDateTime tip = row instanceof TourneyRow t ? t.tipEastern() : ((HomeGameRow) row).tipEastern();
        String text = tip.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                + " — " + v.awayTeam().name() + " @ " + v.homeTeam().name();
        String detail = null;
        if (v.predSpread() != null) {
            PredictionResult.TeamSummary fav = v.predSpread() >= 0 ? v.homeTeam() : v.awayTeam();
            detail = "model: " + (fav.abbreviation() != null ? fav.abbreviation() : fav.name())
                    + " −" + String.format(Locale.US, "%.1f", Math.abs(v.predSpread()));
        }
        return new DigestLine(text, detail, "/games/" + v.gameId());
    }

    private static PredictionCardView cardOf(Object row) {
        return row instanceof TourneyRow t ? t.v() : ((HomeGameRow) row).v();
    }

    private static String seedPrefix(Integer seed) {
        return seed != null ? "(" + seed + ") " : "";
    }

    // ── Shared panels ─────────────────────────────────────────────────────────

    private Optional<HomePanel> newsPanel(boolean compact) {
        List<NewsQueryService.NewsCard> cards = newsQueryService.frontPage();
        if (cards.isEmpty()) return Optional.empty();
        if (compact && cards.size() > NEWS_COMPACT_COUNT) {
            cards = cards.subList(0, NEWS_COMPACT_COUNT);
        }
        return Optional.of(new HomePanel("news", model("cards", cards, "compact", compact)));
    }

    private HomePanel explorePanel() {
        return new HomePanel("explore", Map.of());
    }

    private List<HomeGameRow> cap(List<HomeGameRow> rows) {
        return rows.size() <= PANEL_GAME_LIMIT ? rows : rows.subList(0, PANEL_GAME_LIMIT);
    }

    /** Small ordered-map literal helper (Map.of rejects a null value; panel models never need one). */
    private static Map<String, Object> model(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
