package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ranks every catalog stat by how well it predicts game winners on its own
 * (AUC over completed games, scored on each team's season-to-date value
 * entering the game — same leakage-free construction as the per-stat scatter).
 *
 * <p>Computing this means one pass over ~5k games × ~33 stats, so results are
 * cached in-process keyed by (seasonId, latest snapshot date): a stats-calc
 * run that adds a newer snapshot date naturally rolls the key over (per spec
 * OQ-9 — no persistence). A same-day recalculation serves the previous numbers
 * until the next day's run or a restart; {@link #clearCache()} forces a
 * recompute.
 */
@Service
public class StatPredictivenessService {

    /** Below this AUC a stat is labelled a weak standalone predictor (matches StatPageService). */
    public static final double AUC_SHOW_THRESHOLD = 0.55;

    private final SeasonRepository seasonRepository;
    private final TeamStatSnapshotRepository statSnapshotRepository;
    private final GameRepository gameRepository;

    private final Map<CacheKey, List<StatPredictiveness>> cache = new ConcurrentHashMap<>();

    public StatPredictivenessService(SeasonRepository seasonRepository,
                                     TeamStatSnapshotRepository statSnapshotRepository,
                                     GameRepository gameRepository) {
        this.seasonRepository = seasonRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.gameRepository = gameRepository;
    }

    private record CacheKey(long seasonId, LocalDate latestDate) {}

    /** One stat's standalone predictiveness over a season's completed games. */
    public record StatPredictiveness(StatCatalog.StatInfo info,
                                     Double auc,
                                     Double naiveAccuracy,
                                     int gamesPlotted,
                                     int gamesTotal) {

        public boolean strongEnoughToShow() {
            return auc != null && auc >= AUC_SHOW_THRESHOLD;
        }

        /** Width of the index page's AUC bar: 0% at 0.50 (coin flip), 100% at 0.85. */
        public double aucBarPercent() {
            if (auc == null) return 0;
            return Math.max(0, Math.min(100, (auc - 0.5) * 285.7));
        }
    }

    /** The page payload: rows sorted by AUC descending (unmeasurable stats last). */
    public record PredictorIndex(int year,
                                 List<Integer> availableSeasons,
                                 LocalDate asOfDate,
                                 List<StatPredictiveness> rows) {}

    @Transactional(readOnly = true)
    public PredictorIndex build(int year) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        LocalDate latestDate = statSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);

        List<StatPredictiveness> rows;
        if (latestDate == null) {
            rows = List.of();
        } else {
            CacheKey key = new CacheKey(season.getId(), latestDate);
            // Evict older keys for this season so the map stays one-entry-per-season
            cache.keySet().removeIf(k -> k.seasonId() == season.getId() && !k.equals(key));
            rows = cache.computeIfAbsent(key, k -> compute(season.getId(), latestDate));
        }

        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted(Comparator.reverseOrder())
                .toList();
        return new PredictorIndex(year, years, latestDate, rows);
    }

    public void clearCache() {
        cache.clear();
    }

    private List<StatPredictiveness> compute(long seasonId, LocalDate asOf) {
        // Load the season's completed games once; every stat reuses this list
        List<Game> games = gameRepository.findBySeasonIdAndStatus(seasonId, Game.GameStatus.FINAL).stream()
                .filter(g -> g.getHomeScore() != null && g.getAwayScore() != null)
                .filter(g -> !g.getGameDate().toLocalDate().isAfter(asOf))
                .sorted(Comparator.comparing(Game::getGameDate))
                .toList();
        int gamesTotal = games.size();

        List<StatPredictiveness> rows = new ArrayList<>();
        for (StatCatalog.StatInfo info : StatCatalog.all()) {
            rows.add(computeForStat(seasonId, info, games, gamesTotal, asOf));
        }
        rows.sort(Comparator.comparing(
                (StatPredictiveness r) -> r.auc() != null ? r.auc() : Double.NEGATIVE_INFINITY).reversed());
        return List.copyOf(rows);
    }

    private StatPredictiveness computeForStat(long seasonId, StatCatalog.StatInfo info,
                                              List<Game> games, int gamesTotal, LocalDate asOf) {
        // Snapshot series per team; entering value = latest snapshot strictly
        // before the game date (a same-day snapshot would leak the game itself)
        Map<Long, TreeMap<LocalDate, Double>> seriesByTeam = new HashMap<>();
        for (TeamStatSnapshotRepository.SnapshotValue v :
                statSnapshotRepository.findValuesBySeasonStatUpTo(seasonId, info.name(), asOf)) {
            if (v.getValue() == null) continue;
            seriesByTeam.computeIfAbsent(v.getTeamId(), k -> new TreeMap<>())
                    .put(v.getSnapshotDate(), v.getValue());
        }

        List<Double> diffs = new ArrayList<>();
        List<Boolean> wins = new ArrayList<>();
        for (Game game : games) {
            LocalDate gameDate = game.getGameDate().toLocalDate();
            Double home = enteringValue(seriesByTeam.get(game.getHomeTeam().getId()), gameDate);
            Double away = enteringValue(seriesByTeam.get(game.getAwayTeam().getId()), gameDate);
            if (home == null || away == null) continue;
            diffs.add(info.higherIsBetter() ? home - away : away - home);
            wins.add(game.getHomeScore() > game.getAwayScore());
        }

        if (diffs.size() < 2) {
            return new StatPredictiveness(info, null, null, diffs.size(), gamesTotal);
        }
        double[] d = diffs.stream().mapToDouble(Double::doubleValue).toArray();
        boolean[] w = new boolean[wins.size()];
        for (int i = 0; i < wins.size(); i++) w[i] = wins.get(i);

        double auc = StatMath.auc(d, w);
        double acc = StatMath.naiveAccuracy(d, w);
        return new StatPredictiveness(info,
                Double.isNaN(auc) ? null : auc,
                Double.isNaN(acc) ? null : acc,
                diffs.size(), gamesTotal);
    }

    private static Double enteringValue(TreeMap<LocalDate, Double> series, LocalDate gameDate) {
        if (series == null) return null;
        Map.Entry<LocalDate, Double> entry = series.lowerEntry(gameDate);
        return entry != null ? entry.getValue() : null;
    }
}
