package com.yotto.basketball.service;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class StatsCalculationService {

    private static final Logger log = LoggerFactory.getLogger(StatsCalculationService.class);

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final SeasonStatisticsRepository statsRepository;
    private final ConferenceMembershipRepository membershipRepository;

    public StatsCalculationService(SeasonRepository seasonRepository,
                                   GameRepository gameRepository,
                                   SeasonStatisticsRepository statsRepository,
                                   ConferenceMembershipRepository membershipRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.statsRepository = statsRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public void calculateAndUpdateForSeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Season {} not found, skipping stats calculation", seasonYear);
            return;
        }

        log.info("Calculating stats for season {}", seasonYear);

        // Build conference membership map first — needed to detect conference games
        List<ConferenceMembership> memberships = membershipRepository.findBySeasonId(season.getId());
        Map<Long, Conference> conferenceByTeamId = new HashMap<>();
        Map<Long, Long> confIdByTeamId = new HashMap<>();
        for (ConferenceMembership cm : memberships) {
            Long teamId = cm.getTeam().getId();
            Conference conf = cm.getConference();
            conferenceByTeamId.put(teamId, conf);
            confIdByTeamId.put(teamId, conf.getId());
        }

        List<Game> finalGames = gameRepository.findBySeasonIdAndStatus(season.getId(), Game.GameStatus.FINAL);

        // Per-team accumulators
        Map<Long, int[]> wins = new HashMap<>();        // [0]=wins, [1]=losses
        Map<Long, int[]> confRecord = new HashMap<>();  // [0]=confWins, [1]=confLosses
        Map<Long, int[]> homeRecord = new HashMap<>();  // [0]=homeWins, [1]=homeLosses
        Map<Long, int[]> roadRecord = new HashMap<>();  // [0]=roadWins, [1]=roadLosses
        Map<Long, int[]> points = new HashMap<>();      // [0]=pointsFor, [1]=pointsAgainst
        Map<Long, List<Boolean>> resultsByDate = new HashMap<>(); // ordered list of wins (desc by date)

        for (Game game : finalGames) {
            if (game.getHomeScore() == null || game.getAwayScore() == null) continue;

            Long homeId = game.getHomeTeam().getId();
            Long awayId = game.getAwayTeam().getId();
            int homeScore = game.getHomeScore();
            int awayScore = game.getAwayScore();
            boolean homeWon = homeScore > awayScore;

            // Conference game: both teams in the same conference for this season
            Long homeConfId = confIdByTeamId.get(homeId);
            Long awayConfId = confIdByTeamId.get(awayId);
            boolean confGame = homeConfId != null && homeConfId.equals(awayConfId);

            // Persist the flag so schedule tables and future reads are correct
            game.setConferenceGame(confGame);

            boolean neutral = Boolean.TRUE.equals(game.getNeutralSite());

            // Home team
            wins.computeIfAbsent(homeId, k -> new int[2]);
            confRecord.computeIfAbsent(homeId, k -> new int[2]);
            homeRecord.computeIfAbsent(homeId, k -> new int[2]);
            points.computeIfAbsent(homeId, k -> new int[2]);
            resultsByDate.computeIfAbsent(homeId, k -> new ArrayList<>()).add(homeWon);

            if (homeWon) {
                wins.get(homeId)[0]++;
                if (!neutral) homeRecord.get(homeId)[0]++;
                if (confGame) confRecord.get(homeId)[0]++;
            } else {
                wins.get(homeId)[1]++;
                if (!neutral) homeRecord.get(homeId)[1]++;
                if (confGame) confRecord.get(homeId)[1]++;
            }
            points.get(homeId)[0] += homeScore;
            points.get(homeId)[1] += awayScore;

            // Away team
            wins.computeIfAbsent(awayId, k -> new int[2]);
            confRecord.computeIfAbsent(awayId, k -> new int[2]);
            roadRecord.computeIfAbsent(awayId, k -> new int[2]);
            points.computeIfAbsent(awayId, k -> new int[2]);
            resultsByDate.computeIfAbsent(awayId, k -> new ArrayList<>()).add(!homeWon);

            if (!homeWon) {
                wins.get(awayId)[0]++;
                if (!neutral) roadRecord.get(awayId)[0]++;
                if (confGame) confRecord.get(awayId)[0]++;
            } else {
                wins.get(awayId)[1]++;
                if (!neutral) roadRecord.get(awayId)[1]++;
                if (confGame) confRecord.get(awayId)[1]++;
            }
            points.get(awayId)[0] += awayScore;
            points.get(awayId)[1] += homeScore;
        }

        // Load existing stats rows
        List<SeasonStatistics> existingStats = statsRepository.findBySeasonId(season.getId());
        Map<Long, SeasonStatistics> statsByTeamId = new HashMap<>();
        for (SeasonStatistics ss : existingStats) {
            statsByTeamId.put(ss.getTeam().getId(), ss);
        }

        // Load team references from games
        Map<Long, Team> teamsById = new HashMap<>();
        for (Game game : finalGames) {
            teamsById.put(game.getHomeTeam().getId(), game.getHomeTeam());
            teamsById.put(game.getAwayTeam().getId(), game.getAwayTeam());
        }

        LocalDateTime now = LocalDateTime.now();
        List<SeasonStatistics> toSave = new ArrayList<>();

        for (Long teamId : wins.keySet()) {
            SeasonStatistics ss = statsByTeamId.get(teamId);
            if (ss == null) {
                Conference conf = conferenceByTeamId.get(teamId);
                if (conf == null) {
                    log.debug("Skipping team {} — no conference membership for season {}", teamId, seasonYear);
                    continue;
                }
                ss = new SeasonStatistics();
                ss.setTeam(teamsById.get(teamId));
                ss.setSeason(season);
                ss.setConference(conf);
            }

            int[] w = wins.get(teamId);
            ss.setCalcWins(w[0]);
            ss.setCalcLosses(w[1]);

            int[] cr = confRecord.getOrDefault(teamId, new int[2]);
            ss.setCalcConferenceWins(cr[0]);
            ss.setCalcConferenceLosses(cr[1]);

            int[] hr = homeRecord.getOrDefault(teamId, new int[2]);
            ss.setCalcHomeWins(hr[0]);
            ss.setCalcHomeLosses(hr[1]);

            int[] rr = roadRecord.getOrDefault(teamId, new int[2]);
            ss.setCalcRoadWins(rr[0]);
            ss.setCalcRoadLosses(rr[1]);

            int[] pts = points.getOrDefault(teamId, new int[2]);
            ss.setCalcPointsFor(pts[0]);
            ss.setCalcPointsAgainst(pts[1]);

            ss.setCalcStreak(computeStreak(resultsByDate.getOrDefault(teamId, List.of())));
            ss.setCalcLastUpdated(now);

            toSave.add(ss);
        }

        statsRepository.saveAll(toSave);
        log.info("Stats calculation complete for season {} — {} teams updated", seasonYear, toSave.size());
    }

    private int computeStreak(List<Boolean> winsDescByDate) {
        if (winsDescByDate.isEmpty()) return 0;
        boolean firstWin = winsDescByDate.get(winsDescByDate.size() - 1);
        int count = 0;
        for (int i = winsDescByDate.size() - 1; i >= 0; i--) {
            if (winsDescByDate.get(i) == firstWin) count++;
            else break;
        }
        return firstWin ? count : -count;
    }
}
