package com.yotto.basketball.controller;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.scraping.AsyncScrapeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final SeasonRepository seasonRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;
    private final AsyncScrapeService asyncScrapeService;

    public AdminController(SeasonRepository seasonRepository, ScrapeBatchRepository scrapeBatchRepository,
                           AsyncScrapeService asyncScrapeService) {
        this.seasonRepository = seasonRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
        this.asyncScrapeService = asyncScrapeService;
    }

    @GetMapping
    public String dashboard(Model model) {
        List<Season> seasons = seasonRepository.findAll();
        seasons.sort((a, b) -> b.getYear().compareTo(a.getYear()));

        List<ScrapeBatch> recentBatches = scrapeBatchRepository.findTop20ByOrderByStartedAtDesc();

        model.addAttribute("seasons", seasons);
        model.addAttribute("batches", recentBatches);
        return "admin/dashboard";
    }

    @PostMapping("/seasons")
    public String addSeason(@RequestParam Integer year, RedirectAttributes redirectAttributes) {
        if (year < 2000 || year > 2099) {
            redirectAttributes.addFlashAttribute("error", "Year must be between 2000 and 2099");
            return "redirect:/admin";
        }
        if (seasonRepository.findByYear(year).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Season " + year + " already exists");
            return "redirect:/admin";
        }

        Season season = new Season();
        season.setYear(year);
        season.setStartDate(LocalDate.of(year - 1, 11, 1));
        season.setEndDate(LocalDate.of(year, 4, 30));
        season.setDescription(year + " NCAA Men's Basketball Season");
        seasonRepository.save(season);

        redirectAttributes.addFlashAttribute("success", "Season " + year + " added");
        return "redirect:/admin";
    }

    @DeleteMapping("/seasons/{year}")
    public String deleteSeason(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        if (season == null) {
            redirectAttributes.addFlashAttribute("error", "Season " + year + " not found");
            return "redirect:/admin";
        }

        seasonRepository.delete(season);
        redirectAttributes.addFlashAttribute("success", "Season " + year + " removed");
        return "redirect:/admin";
    }

    @PostMapping("/scrape/full/{year}")
    public String scrapeFullSeason(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.scrapeFullSeasonAsync(year);
        redirectAttributes.addFlashAttribute("success", "Full season scrape started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/current/{year}")
    public String scrapeCurrentSeason(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.scrapeCurrentSeasonAsync(year);
        redirectAttributes.addFlashAttribute("success", "Current season re-scrape started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/teams/{year}")
    public String scrapeTeams(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.scrapeTeamsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Team scrape started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/odds/{year}")
    public String backfillOdds(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.backfillOddsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Odds backfill started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/stats/{year}")
    public String calculateStats(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.calculateStatsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Stats calculation started for " + year);
        return "redirect:/admin";
    }

    @GetMapping("/scrape-history")
    public String scrapeHistory(Model model) {
        List<ScrapeBatch> batches = scrapeBatchRepository.findTop20ByOrderByStartedAtDesc();
        model.addAttribute("batches", batches);
        return "admin/fragments/scrape-history :: scrape-table";
    }
}
