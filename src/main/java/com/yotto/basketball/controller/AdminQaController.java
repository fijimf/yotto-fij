package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.service.SeasonHealthService;
import com.yotto.basketball.service.TeamSeasonTieOut;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AdminQaController {

    private final SeasonRepository seasonRepository;
    private final SeasonStatisticsRepository statsRepository;
    private final SeasonHealthService seasonHealthService;

    public AdminQaController(SeasonRepository seasonRepository,
                             SeasonStatisticsRepository statsRepository,
                             SeasonHealthService seasonHealthService) {
        this.seasonRepository = seasonRepository;
        this.statsRepository = statsRepository;
        this.seasonHealthService = seasonHealthService;
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

        Map<Long, TeamSeasonTieOut> tieOutByTeam = new HashMap<>();
        for (TeamSeasonTieOut t : seasonHealthService.getTieOuts(selectedSeason)) {
            tieOutByTeam.put(t.teamId(), t);
        }

        List<SeasonStatistics> matching = new ArrayList<>();
        List<StatDiscrepancy> discrepancies = new ArrayList<>();
        int noCalcCount = 0;
        int noScrapedCount = 0;

        for (SeasonStatistics ss : allStats) {
            if (!ss.hasCalcData()) {
                noCalcCount++;
                continue;
            }
            if (ss.getWins() == null) {
                noScrapedCount++;
                continue;
            }
            TeamSeasonTieOut tieOut = tieOutByTeam.get(ss.getTeam().getId());
            List<StatDiff> diffs = buildDiffs(ss, tieOut);
            if (diffs.isEmpty()) {
                matching.add(ss);
            } else {
                discrepancies.add(new StatDiscrepancy(
                        ss.getTeam().getId(),
                        ss.getTeam().getName(),
                        ss.getConference().getName(),
                        diffs
                ));
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

    private List<StatDiff> buildDiffs(SeasonStatistics ss, TeamSeasonTieOut tieOut) {
        List<StatDiff> diffs = new ArrayList<>();
        long nonD1Wins = tieOut != null ? tieOut.nonD1Wins() : 0L;
        long nonD1Losses = tieOut != null ? tieOut.nonD1Losses() : 0L;
        addWinLossDiff(diffs, "Wins", ss.getWins(), ss.getCalcWins(), nonD1Wins);
        addWinLossDiff(diffs, "Losses", ss.getLosses(), ss.getCalcLosses(), nonD1Losses);
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
            diffs.add(new StatDiff(field, scraped, calc, null, calc, calc - scraped));
        }
    }

    private void addWinLossDiff(List<StatDiff> diffs, String field, Integer scraped, Integer calc, long nonD1) {
        if (scraped == null || calc == null) return;
        int expected = calc + (int) nonD1;
        if (scraped == expected) return;
        diffs.add(new StatDiff(field, scraped, calc, (int) nonD1, expected, expected - scraped));
    }

    /**
     * One mismatching stat. For Wins/Losses, {@code nonD1} carries the count of
     * tracked non-DI W/Ls and {@code expected = calc + nonD1}. For other stats,
     * {@code nonD1 = null} and {@code expected = calc}. Δ is always
     * {@code expected - scraped}, so a clean tie-out means no diff row.
     */
    public record StatDiff(String field, Integer scraped, Integer calc, Integer nonD1, Integer expected, Integer delta) {}

    public record StatDiscrepancy(Long teamId, String teamName, String conferenceName, List<StatDiff> diffs) {}
}
