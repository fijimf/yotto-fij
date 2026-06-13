package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One season's FINAL games (scores present, teams join-fetched), loaded once per
 * pipeline run and shared by every calculator instead of each service issuing the
 * same query.
 *
 * @param finalGames  sorted ascending by game date
 * @param gamesByDate same games grouped by calendar date, iteration order = date order
 * @param teamsById   every team appearing in any final game
 */
public record SeasonGameData(Season season,
                             List<Game> finalGames,
                             Map<LocalDate, List<Game>> gamesByDate,
                             Map<Long, Team> teamsById) {
}
