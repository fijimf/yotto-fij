package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Controller
public class RankingsPageController {

    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;

    public RankingsPageController(SeasonRepository seasonRepository,
                                  TeamPowerRatingSnapshotRepository ratingRepository) {
        this.seasonRepository = seasonRepository;
        this.ratingRepository = ratingRepository;
    }

    @GetMapping("/rankings")
    public String rankings(@RequestParam(required = false) Integer year,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           Model model) {
        List<Season> allSeasons = seasonRepository.findAll()
                .stream().sorted((a, b) -> b.getYear().compareTo(a.getYear())).toList();

        Season season = resolveSeason(year, allSeasons);
        if (season == null) {
            model.addAttribute("allSeasons", allSeasons);
            model.addAttribute("currentPage", "rankings");
            return "pages/rankings";
        }

        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, allSeasons, model);
        model.addAttribute("currentPage", "rankings");
        return "pages/rankings";
    }

    @GetMapping("/rankings/{year}/table")
    public String rankingsTable(@PathVariable Integer year,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                Model model) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        List<Season> allSeasons = List.of();
        if (season == null) return "fragments/rankings-table :: rankings-table";

        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, allSeasons, model);
        return "fragments/rankings-table :: rankings-table";
    }

    private void populateModel(Season season, LocalDate resolvedDate, List<Season> allSeasons, Model model) {
        List<TeamPowerRatingSnapshot> massey = resolvedDate != null
                ? ratingRepository.findBySeasonModelAndDate(season.getId(), MasseyRatingService.MODEL_TYPE, resolvedDate)
                : List.of();
        List<TeamPowerRatingSnapshot> bradleyTerry = resolvedDate != null
                ? ratingRepository.findBySeasonModelAndDate(season.getId(), BradleyTerryRatingService.MODEL_TYPE, resolvedDate)
                : List.of();
        List<LocalDate> availableDates = ratingRepository.findSnapshotDates(season.getId(), MasseyRatingService.MODEL_TYPE);
        List<LocalDate> availableDatesDesc = new ArrayList<>(availableDates);
        Collections.reverse(availableDatesDesc);

        model.addAttribute("season", season);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("selectedDate", resolvedDate);
        model.addAttribute("availableDates", availableDatesDesc);
        model.addAttribute("latestDate", availableDatesDesc.isEmpty() ? null : availableDatesDesc.get(0));
        model.addAttribute("masseyRankings", massey);
        model.addAttribute("bradleyTerryRankings", bradleyTerry);
        model.addAttribute("hasData", !massey.isEmpty() || !bradleyTerry.isEmpty());
    }

    private Season resolveSeason(Integer year, List<Season> allSeasons) {
        if (year != null) {
            return seasonRepository.findByYear(year).orElse(null);
        }
        return allSeasons.isEmpty() ? null : allSeasons.get(0);
    }

    private LocalDate resolveDate(Season season, LocalDate requested) {
        if (requested != null) return requested;
        return ratingRepository.findLatestSnapshotDate(season.getId(), MasseyRatingService.MODEL_TYPE).orElse(null);
    }
}
