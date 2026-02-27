package com.yotto.basketball.controller;

import com.yotto.basketball.entity.SeasonPopulationStat;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.service.StatisticsTimeSeriesService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final SeasonRepository seasonRepository;
    private final TeamSeasonStatSnapshotRepository snapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;
    private final StatisticsTimeSeriesService timeSeriesService;

    public StatisticsController(SeasonRepository seasonRepository,
                                TeamSeasonStatSnapshotRepository snapshotRepository,
                                SeasonPopulationStatRepository popStatRepository,
                                StatisticsTimeSeriesService timeSeriesService) {
        this.seasonRepository = seasonRepository;
        this.snapshotRepository = snapshotRepository;
        this.popStatRepository = popStatRepository;
        this.timeSeriesService = timeSeriesService;
    }

    /** Full time series for a team in a season — one entry per game date. */
    @GetMapping("/team/{teamId}/season/{year}")
    public List<SnapshotDto> teamTimeSeries(@PathVariable Long teamId, @PathVariable Integer year) {
        var season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        return snapshotRepository.findByTeamAndSeason(teamId, season.getId())
                .stream().map(SnapshotDto::from).toList();
    }

    /** All team snapshots for a specific date (defaults to latest date with data). */
    @GetMapping("/season/{year}/snapshots")
    public List<SnapshotDto> seasonSnapshots(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        LocalDate resolvedDate = resolveDate(season.getId(), date);
        if (resolvedDate == null) return List.of();
        return snapshotRepository.findBySeasonAndDate(season.getId(), resolvedDate)
                .stream().map(SnapshotDto::from).toList();
    }

    /** League-wide population distributions for a specific date (defaults to latest). */
    @GetMapping("/season/{year}/population")
    public Map<String, PopDto> seasonPopulation(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        LocalDate resolvedDate = resolvePopDate(season.getId(), date);
        if (resolvedDate == null) return Map.of();
        return popStatRepository.findLeagueWideBySeasonAndDate(season.getId(), resolvedDate)
                .stream().collect(Collectors.toMap(SeasonPopulationStat::getStatName, PopDto::from));
    }

    /** Trigger time-series recalculation for a season. */
    @PostMapping("/recalculate/{year}")
    public Map<String, String> recalculate(@PathVariable Integer year) {
        timeSeriesService.calculateAndStoreForSeason(year);
        return Map.of("status", "ok", "year", String.valueOf(year));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private LocalDate resolveDate(Long seasonId, LocalDate requested) {
        return requested != null ? requested
                : snapshotRepository.findLatestSnapshotDate(seasonId).orElse(null);
    }

    private LocalDate resolvePopDate(Long seasonId, LocalDate requested) {
        return requested != null ? requested
                : popStatRepository.findLatestStatDate(seasonId).orElse(null);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SnapshotDto(
            LocalDate snapshotDate,
            int gamesPlayed,
            int wins,
            int losses,
            Double winPct,
            Double meanPtsFor,
            Double stddevPtsFor,
            Double meanPtsAgainst,
            Double stddevPtsAgainst,
            Double correlationPts,
            Double meanMargin,
            Double stddevMargin,
            Integer rollingWins,
            Integer rollingLosses,
            Double rollingMeanPtsFor,
            Double rollingMeanPtsAgainst,
            Double zscoreWinPct,
            Double zscoreMeanPtsFor,
            Double zscoreMeanPtsAgainst,
            Double zscoreMeanMargin,
            Double confZscoreWinPct,
            Double confZscoreMeanPtsFor,
            Double confZscoreMeanPtsAgainst,
            Double confZscoreMeanMargin,
            Long teamId,
            String teamName,
            String teamLogoUrl
    ) {
        static SnapshotDto from(TeamSeasonStatSnapshot s) {
            return new SnapshotDto(
                    s.getSnapshotDate(),
                    s.getGamesPlayed(), s.getWins(), s.getLosses(),
                    s.getWinPct(), s.getMeanPtsFor(), s.getStddevPtsFor(),
                    s.getMeanPtsAgainst(), s.getStddevPtsAgainst(),
                    s.getCorrelationPts(), s.getMeanMargin(), s.getStddevMargin(),
                    s.getRollingWins(), s.getRollingLosses(),
                    s.getRollingMeanPtsFor(), s.getRollingMeanPtsAgainst(),
                    s.getZscoreWinPct(), s.getZscoreMeanPtsFor(), s.getZscoreMeanPtsAgainst(),
                    s.getZscoreMeanMargin(),
                    s.getConfZscoreWinPct(), s.getConfZscoreMeanPtsFor(),
                    s.getConfZscoreMeanPtsAgainst(), s.getConfZscoreMeanMargin(),
                    s.getTeam().getId(), s.getTeam().getName(), s.getTeam().getLogoUrl()
            );
        }
    }

    public record PopDto(double mean, double stddev, double min, double max, int teamCount) {
        static PopDto from(SeasonPopulationStat s) {
            return new PopDto(
                    s.getPopMean() != null ? s.getPopMean() : 0,
                    s.getPopStddev() != null ? s.getPopStddev() : 0,
                    s.getPopMin() != null ? s.getPopMin() : 0,
                    s.getPopMax() != null ? s.getPopMax() : 0,
                    s.getTeamCount()
            );
        }
    }
}
