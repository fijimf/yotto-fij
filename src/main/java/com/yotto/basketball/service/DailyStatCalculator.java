package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.TeamGameStats;

import java.time.LocalDate;
import java.util.List;

/**
 * A stateful per-season calculator driven by {@link TeamStatTimeSeriesService}'s
 * single pass over the season's games in date order. Implementations accumulate
 * state in {@code onGame} and emit cumulative long-format values per date.
 *
 * <p>Instances are created fresh per pipeline run — they are not Spring beans.
 */
public interface DailyStatCalculator {

    /** Static metadata for one emitted stat. Rank direction drives leaderboard order. */
    record StatMeta(String name, boolean higherIsBetter) {}

    /** One cumulative value for one team on one date. */
    record TeamStatValue(long teamId, String statName, double value, int gamesPlayed) {}

    /** Every stat this calculator can emit; the union across calculators defines table ownership. */
    List<StatMeta> definitions();

    void begin(SeasonGameData data);

    /**
     * Called once per final game in date order. Box-score rows are {@code null}
     * when not yet scraped for this game.
     */
    void onGame(Game game, TeamGameStats homeStats, TeamGameStats awayStats);

    /** Cumulative values through the given date, for every team with data. */
    List<TeamStatValue> snapshot(LocalDate date);
}
