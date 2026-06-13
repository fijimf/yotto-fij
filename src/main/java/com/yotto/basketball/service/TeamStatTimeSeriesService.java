package com.yotto.basketball.service;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import com.yotto.basketball.service.DailyStatCalculator.StatMeta;
import com.yotto.basketball.service.DailyStatCalculator.TeamStatValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Generic long-format stat pipeline: one pass over the season's games in date
 * order, feeding every registered {@link DailyStatCalculator}; per snapshot date it
 * computes league and per-conference population distributions, z-scores, and ranks
 * for each stat name, then batch-writes {@code team_stat_snapshots} and the
 * matching {@code season_population_stats} rows.
 *
 * <p>Adding a calculator (or a stat to an existing registry) requires no changes
 * here. Watermark semantics match the other snapshot services: replay everything
 * in memory, persist only dates {@code >= fromDate}.
 */
@Service
public class TeamStatTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(TeamStatTimeSeriesService.class);

    private final SeasonGameDataLoader seasonGameDataLoader;
    private final TeamGameStatsRepository teamGameStatsRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final TeamStatSnapshotRepository teamStatSnapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;
    private final SnapshotJdbcWriter snapshotJdbcWriter;

    public TeamStatTimeSeriesService(SeasonGameDataLoader seasonGameDataLoader,
                                     TeamGameStatsRepository teamGameStatsRepository,
                                     ConferenceMembershipRepository membershipRepository,
                                     TeamStatSnapshotRepository teamStatSnapshotRepository,
                                     SeasonPopulationStatRepository popStatRepository,
                                     SnapshotJdbcWriter snapshotJdbcWriter) {
        this.seasonGameDataLoader = seasonGameDataLoader;
        this.teamGameStatsRepository = teamGameStatsRepository;
        this.membershipRepository = membershipRepository;
        this.teamStatSnapshotRepository = teamStatSnapshotRepository;
        this.popStatRepository = popStatRepository;
        this.snapshotJdbcWriter = snapshotJdbcWriter;
    }

    /** Calculators are stateful per run — create fresh instances, never share beans. */
    private List<DailyStatCalculator> createCalculators() {
        return List.of(new BoxScoreStatCalculator());
    }

    @Transactional
    public void calculateAndStoreForSeason(int seasonYear) {
        calculateAndStoreForSeason(seasonYear, null);
    }

    @Transactional
    public void calculateAndStoreForSeason(int seasonYear, LocalDate fromDate) {
        seasonGameDataLoader.load(seasonYear)
                .ifPresent(data -> calculateAndStoreForSeason(data, fromDate));
    }

    @Transactional
    public void calculateAndStoreForSeason(SeasonGameData data, LocalDate fromDate) {
        Season season = data.season();
        log.info("Calculating team stat time series for season {}{}", season.getYear(),
                fromDate != null ? " from " + fromDate : "");
        long startMs = System.currentTimeMillis();

        List<DailyStatCalculator> calculators = createCalculators();
        Map<String, Boolean> higherIsBetterByStat = new HashMap<>();
        for (DailyStatCalculator calc : calculators) {
            for (StatMeta meta : calc.definitions()) {
                higherIsBetterByStat.put(meta.name(), meta.higherIsBetter());
            }
        }
        Set<String> ownedStatNames = higherIsBetterByStat.keySet();

        // Wipe existing rows in scope; population deletes scoped to our stat names
        if (fromDate == null) {
            teamStatSnapshotRepository.deleteBySeasonId(season.getId());
            popStatRepository.deleteBySeasonIdAndStatNames(season.getId(), ownedStatNames);
        } else {
            teamStatSnapshotRepository.deleteBySeasonIdFromDate(season.getId(), fromDate);
            popStatRepository.deleteBySeasonIdFromDateAndStatNames(season.getId(), fromDate, ownedStatNames);
        }

        // Conference membership maps (conference z-scores and scoped population rows)
        Map<Long, Long> confIdByTeamId = new HashMap<>();
        Map<Long, Conference> conferencesById = new HashMap<>();
        for (ConferenceMembership cm : membershipRepository.findBySeasonId(season.getId())) {
            Conference conf = cm.getConference();
            confIdByTeamId.put(cm.getTeam().getId(), conf.getId());
            conferencesById.put(conf.getId(), conf);
        }

        // Box scores, paired per game: [0] = home row, [1] = away row
        Map<Long, TeamGameStats[]> boxByGameId = new HashMap<>();
        for (TeamGameStats tgs : teamGameStatsRepository.findBySeasonId(season.getId())) {
            TeamGameStats[] pair = boxByGameId.computeIfAbsent(tgs.getGame().getId(), k -> new TeamGameStats[2]);
            boolean isHome = tgs.getTeam().getId().equals(tgs.getGame().getHomeTeam().getId());
            pair[isHome ? 0 : 1] = tgs;
        }

        calculators.forEach(c -> c.begin(data));

        List<TeamStatSnapshot> allSnapshots = new ArrayList<>();
        List<SeasonPopulationStat> allPopStats = new ArrayList<>();

        for (Map.Entry<LocalDate, List<Game>> entry : data.gamesByDate().entrySet()) {
            LocalDate date = entry.getKey();

            for (Game game : entry.getValue()) {
                TeamGameStats[] pair = boxByGameId.getOrDefault(game.getId(), new TeamGameStats[2]);
                for (DailyStatCalculator calc : calculators) {
                    calc.onGame(game, pair[0], pair[1]);
                }
            }

            if (fromDate != null && date.isBefore(fromDate)) {
                continue;
            }

            // Group this date's values by stat name
            Map<String, List<TeamStatValue>> valuesByStat = new LinkedHashMap<>();
            for (DailyStatCalculator calc : calculators) {
                for (TeamStatValue v : calc.snapshot(date)) {
                    valuesByStat.computeIfAbsent(v.statName(), k -> new ArrayList<>()).add(v);
                }
            }

            for (Map.Entry<String, List<TeamStatValue>> statEntry : valuesByStat.entrySet()) {
                String statName = statEntry.getKey();
                List<TeamStatValue> values = statEntry.getValue();
                boolean higherIsBetter = higherIsBetterByStat.get(statName);

                // Rank: best value = rank 1
                values.sort(higherIsBetter
                        ? Comparator.comparingDouble(TeamStatValue::value).reversed()
                        : Comparator.comparingDouble(TeamStatValue::value));

                PopData leaguePop = popDataOf(values);
                Map<Long, PopData> confPop = confPopDataOf(values, confIdByTeamId);

                for (int rank = 0; rank < values.size(); rank++) {
                    TeamStatValue v = values.get(rank);
                    TeamStatSnapshot snap = new TeamStatSnapshot();
                    snap.setTeam(data.teamsById().get(v.teamId()));
                    snap.setSeason(season);
                    snap.setSnapshotDate(date);
                    snap.setStatName(statName);
                    snap.setValue(v.value());
                    snap.setGamesPlayed(v.gamesPlayed());
                    snap.setRank(rank + 1);
                    snap.setZscore(leaguePop.zscore(v.value()));
                    Long confId = confIdByTeamId.get(v.teamId());
                    PopData cp = confId != null ? confPop.get(confId) : null;
                    snap.setConfZscore(cp != null ? cp.zscore(v.value()) : null);
                    allSnapshots.add(snap);
                }

                allPopStats.add(toPopEntity(season, null, date, statName, leaguePop));
                for (Map.Entry<Long, PopData> ce : confPop.entrySet()) {
                    allPopStats.add(toPopEntity(season, conferencesById.get(ce.getKey()),
                            date, statName, ce.getValue()));
                }
            }
        }

        long saveStartMs = System.currentTimeMillis();
        snapshotJdbcWriter.writeTeamStatSnapshots(allSnapshots);
        snapshotJdbcWriter.writeSeasonPopulationStats(allPopStats);

        long now = System.currentTimeMillis();
        log.info("Team stat time series complete for season {} — {} snapshots, {} population rows in {} ms (save {} ms)",
                season.getYear(), allSnapshots.size(), allPopStats.size(), now - startMs, now - saveStartMs);
    }

    // ── Population helpers ────────────────────────────────────────────────────

    private record PopData(double mean, double stddev, double min, double max, int count) {

        Double zscore(double value) {
            return stddev > 0 ? (value - mean) / stddev : null;
        }
    }

    private static PopData popDataOf(List<TeamStatValue> values) {
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (TeamStatValue v : values) {
            sum += v.value();
            min = Math.min(min, v.value());
            max = Math.max(max, v.value());
        }
        int n = values.size();
        double mean = sum / n;
        double stddev = 0;
        if (n > 1) {
            double ss = 0;
            for (TeamStatValue v : values) {
                ss += (v.value() - mean) * (v.value() - mean);
            }
            stddev = Math.sqrt(ss / (n - 1));
        }
        return new PopData(mean, stddev, min, max, n);
    }

    private static Map<Long, PopData> confPopDataOf(List<TeamStatValue> values,
                                                    Map<Long, Long> confIdByTeamId) {
        Map<Long, List<TeamStatValue>> byConf = new HashMap<>();
        for (TeamStatValue v : values) {
            Long confId = confIdByTeamId.get(v.teamId());
            if (confId != null) {
                byConf.computeIfAbsent(confId, k -> new ArrayList<>()).add(v);
            }
        }
        Map<Long, PopData> result = new HashMap<>();
        byConf.forEach((confId, confValues) -> result.put(confId, popDataOf(confValues)));
        return result;
    }

    private static SeasonPopulationStat toPopEntity(Season season, Conference conference,
                                                    LocalDate date, String statName, PopData pd) {
        SeasonPopulationStat e = new SeasonPopulationStat();
        e.setSeason(season);
        e.setConference(conference);
        e.setStatDate(date);
        e.setStatName(statName);
        e.setPopMean(pd.mean());
        e.setPopStddev(pd.stddev());
        e.setPopMin(pd.min());
        e.setPopMax(pd.max());
        e.setTeamCount(pd.count());
        return e;
    }
}
