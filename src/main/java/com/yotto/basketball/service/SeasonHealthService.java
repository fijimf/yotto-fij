package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
}
