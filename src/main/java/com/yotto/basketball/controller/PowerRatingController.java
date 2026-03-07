package com.yotto.basketball.controller;

import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.service.MasseyRatingService;
import com.yotto.basketball.service.BradleyTerryRatingService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/power-ratings")
public class PowerRatingController {

    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;

    public PowerRatingController(SeasonRepository seasonRepository,
                                 TeamPowerRatingSnapshotRepository ratingRepository,
                                 PowerModelParamSnapshotRepository paramRepository) {
        this.seasonRepository = seasonRepository;
        this.ratingRepository = ratingRepository;
        this.paramRepository  = paramRepository;
    }

    /** Massey leaderboard for a season, optionally filtered to a specific date. */
    @GetMapping("/{year}/massey")
    public List<RatingDto> masseyLeaderboard(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return leaderboard(year, MasseyRatingService.MODEL_TYPE, date);
    }

    /** Bradley-Terry leaderboard for a season, optionally filtered to a specific date. */
    @GetMapping("/{year}/bradley-terry")
    public List<RatingDto> bradleyTerryLeaderboard(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return leaderboard(year, BradleyTerryRatingService.MODEL_TYPE, date);
    }

    /** Massey rating time series for a single team in a season. */
    @GetMapping("/{year}/massey/team/{teamId}")
    public List<RatingDto> masseyTeamTimeSeries(@PathVariable Integer year, @PathVariable Long teamId) {
        return teamTimeSeries(year, teamId, MasseyRatingService.MODEL_TYPE);
    }

    /** Bradley-Terry rating time series for a single team in a season. */
    @GetMapping("/{year}/bradley-terry/team/{teamId}")
    public List<RatingDto> bradleyTerryTeamTimeSeries(@PathVariable Integer year, @PathVariable Long teamId) {
        return teamTimeSeries(year, teamId, BradleyTerryRatingService.MODEL_TYPE);
    }

    /** Available snapshot dates for a season (Massey; both models share the same dates). */
    @GetMapping("/{year}/dates")
    public List<LocalDate> snapshotDates(@PathVariable Integer year) {
        var season = requireSeason(year);
        return ratingRepository.findSnapshotDates(season.getId(), MasseyRatingService.MODEL_TYPE);
    }

    /** Home court advantage time series for both models in a season. */
    @GetMapping("/{year}/params")
    public Map<String, List<ParamDto>> params(@PathVariable Integer year) {
        var season = requireSeason(year);
        return Map.of(
                "massey", paramRepository.findBySeasonAndModel(season.getId(), MasseyRatingService.MODEL_TYPE)
                        .stream().map(ParamDto::from).toList(),
                "bradleyTerry", paramRepository.findBySeasonAndModel(season.getId(), BradleyTerryRatingService.MODEL_TYPE)
                        .stream().map(ParamDto::from).toList()
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<RatingDto> leaderboard(int year, String modelType, LocalDate date) {
        var season = requireSeason(year);
        LocalDate resolved = date != null ? date
                : ratingRepository.findLatestSnapshotDate(season.getId(), modelType).orElse(null);
        if (resolved == null) return List.of();
        return ratingRepository.findBySeasonModelAndDate(season.getId(), modelType, resolved)
                .stream().map(RatingDto::from).toList();
    }

    private List<RatingDto> teamTimeSeries(int year, Long teamId, String modelType) {
        var season = requireSeason(year);
        return ratingRepository.findByTeamSeasonAndModel(teamId, season.getId(), modelType)
                .stream().map(RatingDto::from).toList();
    }

    private com.yotto.basketball.entity.Season requireSeason(int year) {
        return seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record RatingDto(
            LocalDate snapshotDate,
            String modelType,
            Double rating,
            Integer rank,
            int gamesPlayed,
            Long teamId,
            String teamName,
            String teamLogoUrl
    ) {
        static RatingDto from(TeamPowerRatingSnapshot s) {
            return new RatingDto(
                    s.getSnapshotDate(),
                    s.getModelType(),
                    s.getRating(),
                    s.getRank(),
                    s.getGamesPlayed(),
                    s.getTeam().getId(),
                    s.getTeam().getName(),
                    s.getTeam().getLogoUrl()
            );
        }
    }

    public record ParamDto(LocalDate date, String paramName, double value) {
        static ParamDto from(PowerModelParamSnapshot p) {
            return new ParamDto(p.getSnapshotDate(), p.getParamName(), p.getParamValue());
        }
    }
}
