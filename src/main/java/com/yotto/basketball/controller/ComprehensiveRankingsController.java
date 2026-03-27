package com.yotto.basketball.controller;

import com.yotto.basketball.dto.ComprehensiveRankingRow;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class ComprehensiveRankingsController {

    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final TeamSeasonStatSnapshotRepository statSnapshotRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;

    public ComprehensiveRankingsController(SeasonRepository seasonRepository,
                                           TeamPowerRatingSnapshotRepository ratingRepository,
                                           TeamSeasonStatSnapshotRepository statSnapshotRepository,
                                           SeasonStatisticsRepository seasonStatisticsRepository) {
        this.seasonRepository = seasonRepository;
        this.ratingRepository = ratingRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
    }



    @GetMapping("/rankings/comprehensive")
    public String rankings(@RequestParam(required = false) Integer year,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           Model model) {
        List<Season> allSeasons = seasonRepository.findAll()
                .stream().sorted((a, b) -> b.getYear().compareTo(a.getYear())).toList();
        Season season = resolveSeason(year, allSeasons);
        model.addAttribute("currentPage", "comprehensive-rankings");
        if (season == null) {
            model.addAttribute("allSeasons", allSeasons);
            return "pages/comprehensive-rankings";
        }
        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, allSeasons, model);
        return "pages/comprehensive-rankings";
    }

    @GetMapping("/rankings/comprehensive/{year}/table")
    public String rankingsTable(@PathVariable Integer year,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                Model model) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        if (season == null) {
            model.addAttribute("rows", List.of());
            model.addAttribute("hasData", false);
            return "fragments/comprehensive-rankings-table :: comp-rankings-table";
        }
        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, List.of(), model);
        return "fragments/comprehensive-rankings-table :: comp-rankings-table";
    }

    private void populateModel(Season season, LocalDate resolvedDate,
                               List<Season> allSeasons, Model model) {
        List<ComprehensiveRankingRow> rows = resolvedDate != null
                ? buildRows(season, resolvedDate) : List.of();

        List<LocalDate> availableDates = ratingRepository
                .findSnapshotDates(season.getId(), MasseyRatingService.MODEL_TYPE);
        List<LocalDate> availableDatesDesc = new ArrayList<>(availableDates);
        Collections.reverse(availableDatesDesc);

        model.addAttribute("season", season);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("selectedDate", resolvedDate);
        model.addAttribute("availableDates", availableDatesDesc);
        model.addAttribute("latestDate", availableDatesDesc.isEmpty() ? null : availableDatesDesc.get(0));
        model.addAttribute("rows", rows);
        model.addAttribute("hasData", !rows.isEmpty());
    }

    private List<ComprehensiveRankingRow> buildRows(Season season, LocalDate date) {
        List<TeamSeasonStatSnapshot> stats =
                statSnapshotRepository.findBySeasonAndDate(season.getId(), date);
        List<SeasonStatistics> seasonStats =
                seasonStatisticsRepository.findBySeasonIdWithTeamAndConference(season.getId());
        List<TeamPowerRatingSnapshot> massey =
                ratingRepository.findBySeasonModelAndDate(season.getId(), MasseyRatingService.MODEL_TYPE, date);
        List<TeamPowerRatingSnapshot> bt =
                ratingRepository.findBySeasonModelAndDate(season.getId(), BradleyTerryRatingService.MODEL_TYPE, date);
        List<TeamPowerRatingSnapshot> btw =
                ratingRepository.findBySeasonModelAndDate(season.getId(), BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, date);

        Map<Long, SeasonStatistics> confByTeam = seasonStats.stream()
                .collect(Collectors.toMap(ss -> ss.getTeam().getId(), ss -> ss, (a, b) -> a));
        Map<Long, Double> masseyByTeam = massey.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));
        Map<Long, Double> btByTeam = bt.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));
        Map<Long, Double> btwByTeam = btw.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));

        return stats.stream().map(s -> {
            long teamId = s.getTeam().getId();
            SeasonStatistics ss = confByTeam.get(teamId);
            String confName = ss != null ? ss.getConference().getName() : "—";
            String confAbbr = (ss != null && ss.getConference().getAbbreviation() != null)
                    ? ss.getConference().getAbbreviation() : confName;
            return new ComprehensiveRankingRow(
                    s.getTeam(), confName, confAbbr,
                    s.getWins(), s.getLosses(), s.getWinPct(),
                    s.getMeanPtsFor(), s.getMeanPtsAgainst(), s.getMeanMargin(), s.getRpi(),
                    masseyByTeam.get(teamId),
                    btByTeam.get(teamId),
                    btwByTeam.get(teamId)
            );
        })
        .sorted(Comparator.comparingDouble((ComprehensiveRankingRow r) ->
                r.masseyRating() != null ? r.masseyRating() : Double.NEGATIVE_INFINITY).reversed())
        .toList();
    }

    private Season resolveSeason(Integer year, List<Season> allSeasons) {
        if (year != null) return seasonRepository.findByYear(year).orElse(null);
        return allSeasons.isEmpty() ? null : allSeasons.get(0);
    }

    private LocalDate resolveDate(Season season, LocalDate requested) {
        if (requested != null) return requested;
        Optional<LocalDate> fromRatings = ratingRepository.findLatestSnapshotDate(season.getId(), MasseyRatingService.MODEL_TYPE);
        if (fromRatings.isPresent()) return fromRatings.get();
        return statSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
    }
}
