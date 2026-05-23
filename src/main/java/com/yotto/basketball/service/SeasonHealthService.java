package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SeasonHealthService {

    private static final long STALE_IN_PROGRESS_HOURS = 24;

    private final SeasonRepository seasonRepository;
    private final ConferenceMembershipRepository conferenceMembershipRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final GameRepository gameRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;
    private final NonD1GameObservationRepository nonD1GameObservationRepository;

    public SeasonHealthService(SeasonRepository seasonRepository,
                               ConferenceMembershipRepository conferenceMembershipRepository,
                               SeasonStatisticsRepository seasonStatisticsRepository,
                               GameRepository gameRepository,
                               ScrapeBatchRepository scrapeBatchRepository,
                               NonD1GameObservationRepository nonD1GameObservationRepository) {
        this.seasonRepository = seasonRepository;
        this.conferenceMembershipRepository = conferenceMembershipRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.gameRepository = gameRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
        this.nonD1GameObservationRepository = nonD1GameObservationRepository;
    }

    @Transactional(readOnly = true)
    public SeasonHealth getHealth(Season season) {
        Long seasonId = season.getId();
        Integer year = season.getYear();
        LocalDate start = season.getStartDate();
        LocalDate end = season.getEndDate();

        int totalDates = (start != null && end != null)
                ? (int) ChronoUnit.DAYS.between(start, end) + 1
                : 0;

        LocalDateTime staleCutoff = LocalDateTime.now().minusHours(STALE_IN_PROGRESS_HOURS);

        Optional<ScrapeBatch> latestBatch =
                scrapeBatchRepository.findFirstBySeasonYearOrderByStartedAtDesc(year);

        long mismatches = getTieOuts(season).stream().filter(TeamSeasonTieOut::hasMismatch).count();

        SeasonHealth.PostseasonCounts postseason = new SeasonHealth.PostseasonCounts(
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.CONFERENCE_TOURNAMENT),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.NCAA_TOURNAMENT),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.NIT),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.CBI),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.CROWN),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.OTHER_POSTSEASON),
                gameRepository.countBySeasonIdAndTournamentType(seasonId, Game.TournamentType.IN_SEASON_TOURNAMENT)
        );

        return new SeasonHealth(
                year,
                start,
                end,
                conferenceMembershipRepository.countDistinctConferencesBySeasonId(seasonId),
                conferenceMembershipRepository.countBySeasonId(seasonId),
                totalDates,
                gameRepository.countDistinctScrapeDateBySeasonId(seasonId),
                gameRepository.countBySeasonId(seasonId),
                gameRepository.countBySeasonIdAndStatus(seasonId, Game.GameStatus.FINAL),
                gameRepository.countBySeasonIdAndStatus(seasonId, Game.GameStatus.IN_PROGRESS),
                gameRepository.countStaleInProgress(seasonId, staleCutoff),
                gameRepository.countGamesWithStats(seasonId),
                gameRepository.countFinalGamesMissingStats(seasonId),
                gameRepository.countGamesWithOdds(seasonId),
                gameRepository.countFinalGamesMissingOdds(seasonId),
                seasonStatisticsRepository.countBySeasonId(seasonId),
                nonD1GameObservationRepository.countBySeasonYear(year),
                mismatches,
                postseason,
                latestBatch.map(ScrapeBatch::getStartedAt).orElse(null),
                latestBatch.map(b -> b.getScrapeType().name()).orElse(null),
                latestBatch.map(b -> b.getStatus().name()).orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public SeasonHealth getHealth(int seasonYear) {
        return seasonRepository.findByYear(seasonYear)
                .map(this::getHealth)
                .orElseThrow(() -> new IllegalArgumentException("Season " + seasonYear + " not found"));
    }

    /**
     * Per-team tie-out: ESPN-reported wins/losses vs. (our counted D-I games + our
     * counted non-DI games). A team where these don't match flags either a stale
     * scrape, a misclassified game, or a non-DI opponent we haven't tracked.
     */
    @Transactional(readOnly = true)
    public List<TeamSeasonTieOut> getTieOuts(Season season) {
        List<SeasonStatistics> stats =
                seasonStatisticsRepository.findBySeasonIdWithTeamAndConferenceOrdered(season.getId());
        List<TeamSeasonTieOut> results = new ArrayList<>(stats.size());
        for (SeasonStatistics ss : stats) {
            Team team = ss.getTeam();
            long nonD1Wins = nonD1GameObservationRepository
                    .countByD1TeamIdAndSeasonYearAndResult(team.getId(), season.getYear(), "W");
            long nonD1Losses = nonD1GameObservationRepository
                    .countByD1TeamIdAndSeasonYearAndResult(team.getId(), season.getYear(), "L");
            results.add(new TeamSeasonTieOut(
                    team.getId(),
                    team.getName(),
                    ss.getWins(),
                    ss.getLosses(),
                    ss.getCalcWins(),
                    ss.getCalcLosses(),
                    nonD1Wins,
                    nonD1Losses
            ));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<TeamSeasonTieOut> getTieOuts(int seasonYear) {
        return seasonRepository.findByYear(seasonYear)
                .map(this::getTieOuts)
                .orElseThrow(() -> new IllegalArgumentException("Season " + seasonYear + " not found"));
    }
}
