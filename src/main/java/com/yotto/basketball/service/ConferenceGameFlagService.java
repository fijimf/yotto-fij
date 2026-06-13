package com.yotto.basketball.service;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single source of truth for {@code Game.conferenceGame}: a game is a conference
 * game when both teams belong to the same conference for that season. Runs over
 * every game (any status) so schedule pages are correct too; dirty checking
 * persists only the rows whose flag actually changed.
 */
@Service
public class ConferenceGameFlagService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceGameFlagService.class);

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final ConferenceMembershipRepository membershipRepository;

    public ConferenceGameFlagService(SeasonRepository seasonRepository,
                                     GameRepository gameRepository,
                                     ConferenceMembershipRepository membershipRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public void updateForSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found, skipping conference-game flag update", seasonYear);
            return;
        }

        Map<Long, Long> confIdByTeamId = new HashMap<>();
        for (ConferenceMembership cm : membershipRepository.findBySeasonId(season.getId())) {
            confIdByTeamId.put(cm.getTeam().getId(), cm.getConference().getId());
        }

        List<Game> games = gameRepository.findBySeasonId(season.getId());
        int flipped = 0;
        for (Game game : games) {
            Long homeConfId = confIdByTeamId.get(game.getHomeTeam().getId());
            Long awayConfId = confIdByTeamId.get(game.getAwayTeam().getId());
            boolean confGame = homeConfId != null && homeConfId.equals(awayConfId);
            if (!Objects.equals(game.getConferenceGame(), confGame)) {
                flipped++;
            }
            game.setConferenceGame(confGame);
        }
        log.info("Conference-game flags for season {}: {} games checked, {} updated",
                seasonYear, games.size(), flipped);
    }
}
