package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonPopulationStat;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class StatsWebController {

    private final SeasonRepository seasonRepository;
    private final TeamSeasonStatSnapshotRepository snapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;

    public StatsWebController(SeasonRepository seasonRepository,
                              TeamSeasonStatSnapshotRepository snapshotRepository,
                              SeasonPopulationStatRepository popStatRepository) {
        this.seasonRepository = seasonRepository;
        this.snapshotRepository = snapshotRepository;
        this.popStatRepository = popStatRepository;
    }

    @GetMapping("/seasons/{year}/stats")
    public String seasonStats(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model) {

        var season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        LocalDate latestDate = snapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        LocalDate resolvedDate = date != null ? date : latestDate;

        List<TeamSeasonStatSnapshot> snapshots = resolvedDate != null
                ? snapshotRepository.findBySeasonAndDate(season.getId(), resolvedDate)
                : List.of();

        Map<String, SeasonPopulationStat> popStats = resolvedDate != null
                ? popStatRepository.findLeagueWideBySeasonAndDate(season.getId(), resolvedDate)
                        .stream().collect(Collectors.toMap(SeasonPopulationStat::getStatName, s -> s))
                : Map.of();

        List<Season> allSeasons = seasonRepository.findAll();
        allSeasons.sort((a, b) -> b.getYear().compareTo(a.getYear()));

        model.addAttribute("currentPage", "seasons");
        model.addAttribute("season", season);
        model.addAttribute("selectedDate", resolvedDate);
        model.addAttribute("latestDate", latestDate);
        model.addAttribute("snapshots", snapshots);
        model.addAttribute("popStats", popStats);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("hasData", !snapshots.isEmpty());

        return "pages/season-stats";
    }

    /** HTMX fragment: reloads the rankings table for a different date. */
    @GetMapping("/seasons/{year}/stats/table")
    public String seasonStatsTable(
            @PathVariable Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model) {

        var season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        LocalDate resolvedDate = date != null ? date
                : snapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);

        List<TeamSeasonStatSnapshot> snapshots = resolvedDate != null
                ? snapshotRepository.findBySeasonAndDate(season.getId(), resolvedDate)
                : List.of();

        Map<String, SeasonPopulationStat> popStats = resolvedDate != null
                ? popStatRepository.findLeagueWideBySeasonAndDate(season.getId(), resolvedDate)
                        .stream().collect(Collectors.toMap(SeasonPopulationStat::getStatName, s -> s))
                : Map.of();

        model.addAttribute("season", season);
        model.addAttribute("selectedDate", resolvedDate);
        model.addAttribute("snapshots", snapshots);
        model.addAttribute("popStats", popStats);

        return "fragments/season-stats-table :: stats-table";
    }
}
