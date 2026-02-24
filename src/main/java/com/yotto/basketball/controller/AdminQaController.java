package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Controller
public class AdminQaController {

    private final SeasonRepository seasonRepository;
    private final SeasonStatisticsRepository statsRepository;

    public AdminQaController(SeasonRepository seasonRepository,
                             SeasonStatisticsRepository statsRepository) {
        this.seasonRepository = seasonRepository;
        this.statsRepository = statsRepository;
    }

    @GetMapping("/admin/qa")
    public String qa(@RequestParam(required = false) Integer season, Model model) {
        List<Season> seasons = seasonRepository.findAll();
        seasons.sort((a, b) -> b.getYear().compareTo(a.getYear()));

        if (seasons.isEmpty()) {
            model.addAttribute("seasons", seasons);
            model.addAttribute("selectedSeason", null);
            model.addAttribute("matchCount", 0);
            model.addAttribute("discrepancies", List.of());
            return "admin/scraping-qa";
        }

        int selectedYear = season != null ? season : seasons.get(0).getYear();
        Season selectedSeason = seasons.stream()
                .filter(s -> s.getYear().equals(selectedYear))
                .findFirst()
                .orElse(seasons.get(0));

        List<SeasonStatistics> allStats =
                statsRepository.findBySeasonIdWithTeamAndConferenceOrdered(selectedSeason.getId());

        List<SeasonStatistics> matching = new ArrayList<>();
        List<StatDiscrepancy> discrepancies = new ArrayList<>();
        int noCalcCount = 0;
        int noScrapedCount = 0;

        for (SeasonStatistics ss : allStats) {
            if (!ss.hasCalcData()) {
                noCalcCount++;
                continue;
            }
            // Calc data present but scraped wins are null — nothing to compare against
            if (ss.getWins() == null) {
                noScrapedCount++;
                continue;
            }
            if (ss.hasDiscrepancy()) {
                List<StatDiff> diffs = buildDiffs(ss);
                discrepancies.add(new StatDiscrepancy(
                        ss.getTeam().getId(),
                        ss.getTeam().getName(),
                        ss.getConference().getName(),
                        diffs
                ));
            } else {
                matching.add(ss);
            }
        }

        model.addAttribute("seasons", seasons);
        model.addAttribute("selectedSeason", selectedSeason.getYear());
        model.addAttribute("matchCount", matching.size());
        model.addAttribute("noCalcCount", noCalcCount);
        model.addAttribute("noScrapedCount", noScrapedCount);
        model.addAttribute("discrepancies", discrepancies);

        return "admin/scraping-qa";
    }

    private List<StatDiff> buildDiffs(SeasonStatistics ss) {
        List<StatDiff> diffs = new ArrayList<>();
        addDiff(diffs, "Wins", ss.getWins(), ss.getCalcWins());
        addDiff(diffs, "Losses", ss.getLosses(), ss.getCalcLosses());
        addDiff(diffs, "Conf Wins", ss.getConferenceWins(), ss.getCalcConferenceWins());
        addDiff(diffs, "Conf Losses", ss.getConferenceLosses(), ss.getCalcConferenceLosses());
        addDiff(diffs, "Home Wins", ss.getHomeWins(), ss.getCalcHomeWins());
        addDiff(diffs, "Home Losses", ss.getHomeLosses(), ss.getCalcHomeLosses());
        addDiff(diffs, "Road Wins", ss.getRoadWins(), ss.getCalcRoadWins());
        addDiff(diffs, "Road Losses", ss.getRoadLosses(), ss.getCalcRoadLosses());
        addDiff(diffs, "Points For", ss.getPointsFor(), ss.getCalcPointsFor());
        addDiff(diffs, "Points Against", ss.getPointsAgainst(), ss.getCalcPointsAgainst());
        addDiff(diffs, "Streak", ss.getStreak(), ss.getCalcStreak());
        return diffs;
    }

    private void addDiff(List<StatDiff> diffs, String field, Integer scraped, Integer calc) {
        if (scraped == null || calc == null) return;
        if (!scraped.equals(calc)) {
            diffs.add(new StatDiff(field, scraped, calc, calc - scraped));
        }
    }

    public record StatDiff(String field, Integer scraped, Integer calc, Integer delta) {}

    public record StatDiscrepancy(Long teamId, String teamName, String conferenceName, List<StatDiff> diffs) {}
}
