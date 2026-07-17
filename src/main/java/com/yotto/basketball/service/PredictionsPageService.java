package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.GameStatus;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Assembles the {@code /predictions} browser: a reference-date-anchored view of recent results
 * (with pre-game predictions vs. actual outcomes) and upcoming games, under a user-selected model.
 */
@Service
@Transactional(readOnly = true)
public class PredictionsPageService {

    private final PredictionService predictionService;
    private final GameRepository gameRepository;
    private final MlModelRegistryService mlModelRegistryService;

    public PredictionsPageService(PredictionService predictionService,
                                  GameRepository gameRepository,
                                  MlModelRegistryService mlModelRegistryService) {
        this.predictionService = predictionService;
        this.gameRepository = gameRepository;
        this.mlModelRegistryService = mlModelRegistryService;
    }

    /** A selectable prediction source: {@code ml:<slug>} or {@link PredictionCardView#CLASSICAL_KEY}. */
    public record ModelOption(String key, String label) {}

    /** Everything the predictions page/fragment needs to render. */
    public record PredictionsPage(
            LocalDate refDate,
            int days,
            LocalDate prevDate,
            LocalDate nextDate,
            Map<LocalDate, List<PredictionCardView>> resultsByDate,   // dates ≤ refDate, chronological
            Map<LocalDate, List<PredictionCardView>> upcomingByDate,  // dates > refDate, chronological
            List<ModelOption> modelOptions,
            String selectedModel,
            String selectedModelLabel) {

        public boolean hasResults()  { return !resultsByDate.isEmpty(); }
        public boolean hasUpcoming() { return !upcomingByDate.isEmpty(); }
    }

    public PredictionsPage build(String dateParam, int daysParam, String modelParam) {
        int days = daysParam == 3 ? 3 : 7;
        LocalDate ref = resolveRefDate(dateParam, days);

        List<ModelOption> options = modelOptions();
        String selected = resolveSelected(modelParam, options);
        String selectedLabel = options.stream()
                .filter(o -> o.key().equals(selected))
                .map(ModelOption::label)
                .findFirst().orElse(selected);

        LocalDateTime[] window = EasternDates.rangeWindowUtc(ref.minusDays(days), ref.plusDays(days));

        Map<LocalDate, List<PredictionCardView>> results = new TreeMap<>();   // chronological
        Map<LocalDate, List<PredictionCardView>> upcoming = new TreeMap<>();  // chronological
        for (Game game : gameRepository.findInUtcWindow(window[0], window[1])) {
            if (game.getStatus() == GameStatus.CANCELLED || game.getStatus() == GameStatus.POSTPONED) {
                continue;
            }
            // Bucket by Eastern calendar date (gameDate is stored in UTC), matching the scoreboard.
            LocalDate d = EasternDates.toEasternDate(game.getGameDate());
            PredictionCardView view = PredictionCardView.from(predictionService.buildPrediction(game), selected);
            Map<LocalDate, List<PredictionCardView>> bucket = d.isAfter(ref) ? upcoming : results;
            bucket.computeIfAbsent(d, k -> new ArrayList<>()).add(view);
        }

        return new PredictionsPage(ref, days, ref.minusDays(1), ref.plusDays(1),
                results, upcoming, options, selected, selectedLabel);
    }

    // ── Reference date ──────────────────────────────────────────────────────────

    private LocalDate resolveRefDate(String dateParam, int days) {
        LocalDate parsed = parseDate(dateParam);
        if (parsed != null) return parsed;

        LocalDate today = LocalDate.now(EasternDates.EASTERN);
        LocalDateTime[] window = EasternDates.rangeWindowUtc(today.minusDays(days), today.plusDays(days));
        Optional<LocalDateTime> nearest = gameRepository.findMinGameDateOnOrAfter(window[0]);
        boolean hasNearbyGames = nearest.isPresent() && nearest.get().isBefore(window[1]);
        if (hasNearbyGames) return today;

        return gameRepository.findMaxFinalGameDate()
                .map(EasternDates::toEasternDate)
                .orElse(today);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ── Model selection ─────────────────────────────────────────────────────────

    private List<ModelOption> modelOptions() {
        MlModelRegistryService.ServingPlan plan = mlModelRegistryService.plan();
        String defaultSlug = plan.defaultSlug();

        List<ModelOption> options = new ArrayList<>();
        plan.activeVersions().keySet().stream()
                .sorted(Comparator
                        .comparing((String slug) -> !slug.equals(defaultSlug))          // default first
                        .thenComparing(slug -> plan.displayNames().getOrDefault(slug, slug)))
                .forEach(slug -> options.add(
                        new ModelOption(PredictionCardView.ML_PREFIX + slug,
                                plan.displayNames().getOrDefault(slug, slug))));
        options.add(new ModelOption(PredictionCardView.CLASSICAL_KEY, "Massey / Bradley-Terry"));
        return options;
    }

    private String resolveSelected(String modelParam, List<ModelOption> options) {
        if (modelParam != null && options.stream().anyMatch(o -> o.key().equals(modelParam))) {
            return modelParam;
        }
        String defaultSlug = mlModelRegistryService.plan().defaultSlug();
        String defaultKey = defaultSlug != null ? PredictionCardView.ML_PREFIX + defaultSlug : null;
        if (defaultKey != null && options.stream().anyMatch(o -> o.key().equals(defaultKey))) {
            return defaultKey;
        }
        return PredictionCardView.CLASSICAL_KEY;
    }
}
