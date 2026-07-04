package com.yotto.basketball.service;

import com.yotto.basketball.controller.dto.StatPageDto;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonPopulationStat;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
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

/** Builds the {@link StatPageDto} for one stat in one season as of a snapshot date. */
@Service
public class StatPageService {

    /** AUC at or above which the entering-value predictiveness block is worth showing. */
    private static final double AUC_SHOW_THRESHOLD = 0.55;
    private static final int KDE_SAMPLES = 100;

    private final SeasonRepository seasonRepository;
    private final TeamStatSnapshotRepository statSnapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;
    private final GameRepository gameRepository;

    public StatPageService(SeasonRepository seasonRepository,
                           TeamStatSnapshotRepository statSnapshotRepository,
                           SeasonPopulationStatRepository popStatRepository,
                           GameRepository gameRepository) {
        this.seasonRepository = seasonRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.popStatRepository = popStatRepository;
        this.gameRepository = gameRepository;
    }

    @Transactional(readOnly = true)
    public StatPageDto build(int year, String statName, LocalDate requestedDate) {
        StatCatalog.StatInfo info = StatCatalog.require(statName);
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        Long seasonId = season.getId();

        LocalDate latestDate = statSnapshotRepository.findLatestSnapshotDate(seasonId).orElse(null);
        LocalDate date = requestedDate != null ? requestedDate : latestDate;

        StatPageDto.Meta meta = new StatPageDto.Meta(
                info.name(), info.title(), info.category(), info.description(),
                info.mechanics(), info.format().name(), info.higherIsBetter());

        List<Integer> availableSeasons = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted(Comparator.reverseOrder())
                .toList();
        StatPageDto.SeasonInfo seasonInfo = new StatPageDto.SeasonInfo(
                year,
                date != null ? date.toString() : null,
                latestDate != null ? latestDate.toString() : null,
                availableSeasons);

        List<TeamStatSnapshot> snapshots = date != null
                ? statSnapshotRepository.findBySeasonStatAndDate(seasonId, statName, date)
                : List.of();

        StatPageDto.Population population = buildPopulation(seasonId, statName, date, snapshots.size());
        int teamCount = population.count() != null ? population.count() : snapshots.size();

        return new StatPageDto(
                meta,
                seasonInfo,
                population,
                buildHistogram(snapshots),
                buildScatter(seasonId, statName, info.higherIsBetter(), date),
                buildRankings(snapshots, teamCount));
    }

    private StatPageDto.Population buildPopulation(Long seasonId, String statName, LocalDate date, int fallbackCount) {
        if (date == null) {
            return new StatPageDto.Population(null, null, null, null, fallbackCount > 0 ? fallbackCount : null);
        }
        SeasonPopulationStat pop = popStatRepository.findLeagueWideBySeasonAndDate(seasonId, date).stream()
                .filter(p -> statName.equals(p.getStatName()))
                .findFirst()
                .orElse(null);
        if (pop == null) {
            return new StatPageDto.Population(null, null, null, null, fallbackCount > 0 ? fallbackCount : null);
        }
        return new StatPageDto.Population(
                pop.getPopMin(), pop.getPopMax(), pop.getPopMean(), pop.getPopStddev(), pop.getTeamCount());
    }

    private StatPageDto.Histogram buildHistogram(List<TeamStatSnapshot> snapshots) {
        double[] values = snapshots.stream()
                .map(TeamStatSnapshot::getValue)
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .toArray();
        StatMath.Histogram h = StatMath.histogram(values);
        StatMath.Kde kde = StatMath.kde(values, KDE_SAMPLES);
        return new StatPageDto.Histogram(h.binEdges(), h.binCounts(), new StatPageDto.Kde(kde.x(), kde.y()));
    }

    private List<StatPageDto.RankRow> buildRankings(List<TeamStatSnapshot> snapshots, int teamCount) {
        List<StatPageDto.RankRow> rows = new ArrayList<>(snapshots.size());
        for (TeamStatSnapshot s : snapshots) {
            Team team = s.getTeam();
            rows.add(new StatPageDto.RankRow(
                    s.getRank(),
                    team.getId(),
                    team.getName(),
                    team.getLogoUrl(),
                    s.getValue(),
                    s.getZscore(),
                    percentile(s.getRank(), teamCount),
                    s.getGamesPlayed()));
        }
        return rows;
    }

    /** Percentile from a direction-aware rank (1 = best): 100 at rank 1, 0 at last. */
    private Double percentile(Integer rank, int count) {
        if (rank == null || count <= 1) return null;
        return 100.0 * (count - rank) / (count - 1);
    }

    private StatPageDto.Scatter buildScatter(Long seasonId, String statName, boolean higherIsBetter, LocalDate date) {
        List<Game> games = gameRepository.findBySeasonIdAndStatus(seasonId, Game.GameStatus.FINAL).stream()
                .filter(g -> date == null || !g.getGameDate().toLocalDate().isAfter(date))
                .sorted(Comparator.comparing(Game::getGameDate))
                .toList();
        int gamesTotal = games.size();

        // Each team's snapshot series, keyed by date for the entering-value lookup.
        Map<Long, TreeMap<LocalDate, Double>> seriesByTeam = new HashMap<>();
        if (date != null) {
            for (TeamStatSnapshotRepository.SnapshotValue v :
                    statSnapshotRepository.findValuesBySeasonStatUpTo(seasonId, statName, date)) {
                if (v.getValue() == null) continue;
                seriesByTeam.computeIfAbsent(v.getTeamId(), k -> new TreeMap<>())
                        .put(v.getSnapshotDate(), v.getValue());
            }
        }

        List<StatPageDto.Point> points = new ArrayList<>();
        List<Double> diffs = new ArrayList<>();
        List<Boolean> wins = new ArrayList<>();

        for (Game game : games) {
            if (game.getHomeScore() == null || game.getAwayScore() == null) continue;
            LocalDate gameDate = game.getGameDate().toLocalDate();
            Double home = enteringValue(seriesByTeam.get(game.getHomeTeam().getId()), gameDate);
            Double away = enteringValue(seriesByTeam.get(game.getAwayTeam().getId()), gameDate);
            if (home == null || away == null) continue;

            boolean homeWin = game.getHomeScore() > game.getAwayScore();
            points.add(new StatPageDto.Point(home, away, homeWin,
                    abbr(game.getHomeTeam()), abbr(game.getAwayTeam())));
            diffs.add(higherIsBetter ? home - away : away - home);
            wins.add(homeWin);
        }

        double[] axis = axisBounds(points);
        StatPageDto.Predictive predictive = buildPredictive(diffs, wins);
        return new StatPageDto.Scatter(points, axis[0], axis[1], gamesTotal, points.size(), predictive);
    }

    /** Tooltip label for a scatter point: abbreviation when set, else team name. */
    private static String abbr(Team team) {
        String a = team.getAbbreviation();
        return a != null && !a.isBlank() ? a : team.getName();
    }

    /**
     * The team's season-to-date value entering the game: the latest snapshot
     * strictly before the game date. Snapshots on date D include games played
     * on D, so a same-day snapshot would leak the game's own box score.
     */
    private static Double enteringValue(TreeMap<LocalDate, Double> series, LocalDate gameDate) {
        if (series == null) return null;
        Map.Entry<LocalDate, Double> entry = series.lowerEntry(gameDate);
        return entry != null ? entry.getValue() : null;
    }

    /** Shared, padded square domain covering both axes; defaults to [0,1] when empty. */
    private double[] axisBounds(List<StatPageDto.Point> points) {
        if (points.isEmpty()) return new double[]{0, 1};
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (StatPageDto.Point p : points) {
            min = Math.min(min, Math.min(p.x(), p.y()));
            max = Math.max(max, Math.max(p.x(), p.y()));
        }
        if (max <= min) {
            return new double[]{min - 1, max + 1};
        }
        double pad = (max - min) * 0.05;
        return new double[]{min - pad, max + pad};
    }

    private StatPageDto.Predictive buildPredictive(List<Double> diffs, List<Boolean> wins) {
        if (diffs.size() < 2) {
            return new StatPageDto.Predictive(null, null, false);
        }
        double[] d = diffs.stream().mapToDouble(Double::doubleValue).toArray();
        boolean[] w = new boolean[wins.size()];
        for (int i = 0; i < wins.size(); i++) w[i] = wins.get(i);

        double auc = StatMath.auc(d, w);
        double acc = StatMath.naiveAccuracy(d, w);
        Double aucOut = Double.isNaN(auc) ? null : auc;
        Double accOut = Double.isNaN(acc) ? null : acc;
        boolean show = aucOut != null && auc >= AUC_SHOW_THRESHOLD;
        return new StatPageDto.Predictive(aucOut, accOut, show);
    }
}
