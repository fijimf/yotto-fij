package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.GameStatus;
import com.yotto.basketball.news.NewsQueryService;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final SeasonPhaseService seasonPhaseService;
    private final GameRepository gameRepository;
    private final PredictionService predictionService;
    private final PredictionsPageService predictionsPageService;
    private final PredictionEvaluationRepository predictionEvaluationRepository;
    private final NewsQueryService newsQueryService;
    private final ConferenceMembershipRepository conferenceMembershipRepository;
    private final TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository;

    public HomePageService(SeasonPhaseService seasonPhaseService,
                           GameRepository gameRepository,
                           PredictionService predictionService,
                           PredictionsPageService predictionsPageService,
                           PredictionEvaluationRepository predictionEvaluationRepository,
                           NewsQueryService newsQueryService,
                           ConferenceMembershipRepository conferenceMembershipRepository,
                           TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository) {
        this.seasonPhaseService = seasonPhaseService;
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
        this.predictionsPageService = predictionsPageService;
        this.predictionEvaluationRepository = predictionEvaluationRepository;
        this.newsQueryService = newsQueryService;
        this.conferenceMembershipRepository = conferenceMembershipRepository;
        this.teamSeasonStatSnapshotRepository = teamSeasonStatSnapshotRepository;
    }

    @Transactional(readOnly = true)
    public HomePage build() {
        SeasonPhase phase = seasonPhaseService.current();
        // POSTSEASON rides the live composition until the bracket takeover ships (plan Phase 4)
        return switch (phase.phase()) {
            case IN_SEASON, POSTSEASON -> liveComposition(phase);
            case PRESEASON, EPILOGUE, OFFSEASON -> quietComposition(phase);
        };
    }

    // ── IN_SEASON / POSTSEASON ────────────────────────────────────────────────

    private HomePage liveComposition(SeasonPhase phase) {
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

    // ── PRESEASON / EPILOGUE / OFFSEASON (fallback until plan Phases 2 and 4) ─

    private HomePage quietComposition(SeasonPhase phase) {
        List<HomePanel> panels = new ArrayList<>();
        newsPanel(false).ifPresent(panels::add);
        panels.add(explorePanel());
        return new HomePage(phase, quietTagline(phase), panels);
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
        return "College Basketball · Quantified";
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
