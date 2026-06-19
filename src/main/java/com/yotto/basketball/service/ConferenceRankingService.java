package com.yotto.basketball.service;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for per-conference, season-scoped aggregates shared by the conference
 * index and detail pages: simple-average Massey rating + dense conference rank, combined record,
 * and non-conference record. Computing this in one place keeps the two pages numerically consistent.
 */
@Service
public class ConferenceRankingService {

    private final ConferenceMembershipRepository membershipRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final GameRepository gameRepository;

    public ConferenceRankingService(ConferenceMembershipRepository membershipRepository,
                                    SeasonStatisticsRepository seasonStatisticsRepository,
                                    TeamPowerRatingSnapshotRepository ratingRepository,
                                    GameRepository gameRepository) {
        this.membershipRepository = membershipRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.ratingRepository = ratingRepository;
        this.gameRepository = gameRepository;
    }

    /**
     * Aggregates every conference that has at least one member in the season, keyed by conference id.
     * Conferences with no Massey-rated member get a {@code null} average and rank (they sort last).
     */
    @Transactional(readOnly = true)
    public Map<Long, ConferenceAggregate> aggregateBySeason(Season season) {
        Long seasonId = season.getId();

        // conferenceId -> member team ids (membership is the authoritative member list)
        Map<Long, List<Long>> teamsByConf = new HashMap<>();
        Map<Long, Long> confByTeam = new HashMap<>();
        for (ConferenceMembership cm : membershipRepository.findBySeasonId(seasonId)) {
            Long confId = cm.getConference().getId();
            Long teamId = cm.getTeam().getId();
            teamsByConf.computeIfAbsent(confId, k -> new java.util.ArrayList<>()).add(teamId);
            confByTeam.put(teamId, confId);
        }

        // teamId -> (wins, losses) from SeasonStatistics (calc → scraped fallback)
        Map<Long, int[]> recordByTeam = new HashMap<>();
        for (SeasonStatistics ss : seasonStatisticsRepository.findBySeasonId(seasonId)) {
            recordByTeam.put(ss.getTeam().getId(), new int[]{
                    resolveInt(ss.getCalcWins(), ss.getWins()),
                    resolveInt(ss.getCalcLosses(), ss.getLosses())
            });
        }

        // teamId -> Massey rating at the latest snapshot date
        Map<Long, Double> ratingByTeam = new HashMap<>();
        LocalDate latestDate = ratingRepository
                .findLatestSnapshotDate(seasonId, MasseyRatingService.MODEL_TYPE).orElse(null);
        if (latestDate != null) {
            for (TeamPowerRatingSnapshot s : ratingRepository
                    .findBySeasonModelAndDate(seasonId, MasseyRatingService.MODEL_TYPE, latestDate)) {
                if (s.getRating() != null) ratingByTeam.put(s.getTeam().getId(), s.getRating());
            }
        }

        // Non-conference record per conference: one pass over the season's games.
        Map<Long, int[]> nonConfByConf = new HashMap<>();
        for (Game g : gameRepository.findBySeasonIdWithTeams(seasonId)) {
            if (!isRegularSeasonNonConference(g)) continue;
            Integer hs = g.getHomeScore(), as = g.getAwayScore();
            if (hs == null || as == null || hs.equals(as)) continue;
            boolean homeWon = hs > as;
            creditNonConf(nonConfByConf, confByTeam.get(g.getHomeTeam().getId()), homeWon);
            creditNonConf(nonConfByConf, confByTeam.get(g.getAwayTeam().getId()), !homeWon);
        }

        // Build per-conference aggregates (rank assigned afterwards).
        Map<Long, ConferenceAggregate> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> e : teamsByConf.entrySet()) {
            Long confId = e.getKey();
            List<Long> teamIds = e.getValue();

            int wins = 0, losses = 0, ratedCount = 0;
            double ratingSum = 0.0;
            for (Long teamId : teamIds) {
                int[] rec = recordByTeam.get(teamId);
                if (rec != null) { wins += rec[0]; losses += rec[1]; }
                Double rating = ratingByTeam.get(teamId);
                if (rating != null) { ratingSum += rating; ratedCount++; }
            }
            Double avg = ratedCount > 0 ? ratingSum / ratedCount : null;
            int[] nc = nonConfByConf.getOrDefault(confId, new int[]{0, 0});

            result.put(confId, new ConferenceAggregate(
                    confId, teamIds.size(), avg, null, ratedCount,
                    wins, losses, nc[0], nc[1]));
        }

        return assignRanks(result);
    }

    /** Dense-ranks conferences by average Massey rating (desc); unrated conferences keep null rank. */
    private Map<Long, ConferenceAggregate> assignRanks(Map<Long, ConferenceAggregate> aggregates) {
        List<ConferenceAggregate> rated = aggregates.values().stream()
                .filter(a -> a.avgMasseyRating() != null)
                .sorted(Comparator.comparingDouble(ConferenceAggregate::avgMasseyRating).reversed())
                .toList();

        Map<Long, Integer> rankByConf = new HashMap<>();
        int rank = 0;
        Double previous = null;
        for (ConferenceAggregate a : rated) {
            if (previous == null || !previous.equals(a.avgMasseyRating())) rank++;
            rankByConf.put(a.conferenceId(), rank);
            previous = a.avgMasseyRating();
        }

        Map<Long, ConferenceAggregate> out = new LinkedHashMap<>();
        for (Map.Entry<Long, ConferenceAggregate> e : aggregates.entrySet()) {
            ConferenceAggregate a = e.getValue();
            out.put(e.getKey(), a.withRank(rankByConf.get(a.conferenceId())));
        }
        return out;
    }

    private static void creditNonConf(Map<Long, int[]> nonConfByConf, Long confId, boolean won) {
        if (confId == null) return;
        int[] rec = nonConfByConf.computeIfAbsent(confId, k -> new int[]{0, 0});
        if (won) rec[0]++; else rec[1]++;
    }

    /** FINAL, flagged non-conference, and not a postseason tournament game. */
    private static boolean isRegularSeasonNonConference(Game g) {
        if (g.getStatus() != Game.GameStatus.FINAL) return false;
        if (Boolean.TRUE.equals(g.getConferenceGame())) return false;
        Game.TournamentType t = g.getTournamentType();
        return t == null || t == Game.TournamentType.IN_SEASON_TOURNAMENT;
    }

    private static int resolveInt(Integer calc, Integer scraped) {
        if (calc != null) return calc;
        return scraped != null ? scraped : 0;
    }

    /**
     * Season-scoped aggregate for one conference. {@code avgMasseyRating}/{@code conferenceRank} are
     * null when no member has a Massey rating on the latest snapshot date.
     */
    public record ConferenceAggregate(
            Long conferenceId,
            int teamCount,
            Double avgMasseyRating,
            Integer conferenceRank,
            int ratedCount,
            int wins,
            int losses,
            int nonConfWins,
            int nonConfLosses
    ) {
        ConferenceAggregate withRank(Integer rank) {
            return new ConferenceAggregate(conferenceId, teamCount, avgMasseyRating, rank,
                    ratedCount, wins, losses, nonConfWins, nonConfLosses);
        }
    }
}
