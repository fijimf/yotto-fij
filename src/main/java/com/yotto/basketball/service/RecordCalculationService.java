package com.yotto.basketball.service;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecordCalculationService {

    private final GameRepository gameRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final SeasonRepository seasonRepository;

    public RecordCalculationService(GameRepository gameRepository,
                                    ConferenceMembershipRepository membershipRepository,
                                    SeasonRepository seasonRepository) {
        this.gameRepository = gameRepository;
        this.membershipRepository = membershipRepository;
        this.seasonRepository = seasonRepository;
    }

    @Transactional(readOnly = true)
    public Map<Long, TeamRecord> calculateRecords(Long seasonId) {
        List<Game> finalGames = gameRepository.findBySeasonIdAndStatus(seasonId, Game.GameStatus.FINAL);

        Map<Long, Long> conferenceByTeamId = buildConferenceMap(seasonId);

        Map<Long, TeamRecord> records = new HashMap<>();

        for (Game game : finalGames) {
            if (game.getHomeScore() == null || game.getAwayScore() == null) {
                continue;
            }

            Long homeId = game.getHomeTeam().getId();
            Long awayId = game.getAwayTeam().getId();

            TeamRecord homeRecord = records.computeIfAbsent(homeId, k -> new TeamRecord());
            TeamRecord awayRecord = records.computeIfAbsent(awayId, k -> new TeamRecord());

            boolean isConferenceGame = isConferenceGame(game, homeId, awayId, conferenceByTeamId);

            if (game.getHomeScore() > game.getAwayScore()) {
                homeRecord.addWin(isConferenceGame);
                awayRecord.addLoss(isConferenceGame);
            } else if (game.getAwayScore() > game.getHomeScore()) {
                awayRecord.addWin(isConferenceGame);
                homeRecord.addLoss(isConferenceGame);
            }
        }

        return records;
    }

    @Transactional(readOnly = true)
    public Map<Long, TeamRecord> calculateCurrentSeasonRecords() {
        return seasonRepository.findTopByOrderByYearDesc()
                .map(season -> calculateRecords(season.getId()))
                .orElse(Map.of());
    }

    private boolean isConferenceGame(Game game, Long homeId, Long awayId,
                                     Map<Long, Long> conferenceByTeamId) {
        if (Boolean.TRUE.equals(game.getConferenceGame())) {
            return true;
        }
        Long homeConf = conferenceByTeamId.get(homeId);
        Long awayConf = conferenceByTeamId.get(awayId);
        return homeConf != null && homeConf.equals(awayConf);
    }

    private Map<Long, Long> buildConferenceMap(Long seasonId) {
        List<ConferenceMembership> memberships = membershipRepository.findBySeasonId(seasonId);
        Map<Long, Long> map = new HashMap<>();
        for (ConferenceMembership cm : memberships) {
            map.put(cm.getTeam().getId(), cm.getConference().getId());
        }
        return map;
    }

    public static class TeamRecord {
        private int wins;
        private int losses;
        private int conferenceWins;
        private int conferenceLosses;

        public void addWin(boolean isConferenceGame) {
            wins++;
            if (isConferenceGame) conferenceWins++;
        }

        public void addLoss(boolean isConferenceGame) {
            losses++;
            if (isConferenceGame) conferenceLosses++;
        }

        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public int getConferenceWins() { return conferenceWins; }
        public int getConferenceLosses() { return conferenceLosses; }

        public String getRecord() { return wins + "-" + losses; }
        public String getConferenceRecord() { return conferenceWins + "-" + conferenceLosses; }
    }
}
