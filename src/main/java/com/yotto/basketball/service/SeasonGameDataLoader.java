package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class SeasonGameDataLoader {

    private static final Logger log = LoggerFactory.getLogger(SeasonGameDataLoader.class);

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;

    public SeasonGameDataLoader(SeasonRepository seasonRepository, GameRepository gameRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
    }

    @Transactional(readOnly = true)
    public Optional<SeasonGameData> load(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found", seasonYear);
            return Optional.empty();
        }

        List<Game> finalGames = gameRepository
                .findBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL)
                .stream()
                .filter(g -> g.getHomeScore() != null && g.getAwayScore() != null)
                .sorted(Comparator.comparing(g -> g.getGameDate().toLocalDate()))
                .collect(Collectors.toList());

        Map<LocalDate, List<Game>> gamesByDate = finalGames.stream()
                .collect(Collectors.groupingBy(g -> g.getGameDate().toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, Team> teamsById = new HashMap<>();
        for (Game g : finalGames) {
            teamsById.put(g.getHomeTeam().getId(), g.getHomeTeam());
            teamsById.put(g.getAwayTeam().getId(), g.getAwayTeam());
        }

        return Optional.of(new SeasonGameData(season, finalGames, gamesByDate, teamsById));
    }
}
