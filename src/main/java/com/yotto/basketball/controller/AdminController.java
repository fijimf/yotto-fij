package com.yotto.basketball.controller;

import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.scraping.AsyncScrapeService;
import com.yotto.basketball.scraping.TournamentReclassifier;
import com.yotto.basketball.service.AutomationService;
import com.yotto.basketball.service.AutomationStatus;
import com.yotto.basketball.service.MlModelRegistryService;
import com.yotto.basketball.service.MlTrainingService;
import com.yotto.basketball.service.ScrapeHistoryEntry;
import com.yotto.basketball.service.ScrapeHistoryService;
import com.yotto.basketball.service.SeasonHealth;
import com.yotto.basketball.service.SeasonHealthService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final SeasonRepository seasonRepository;
    private final AsyncScrapeService asyncScrapeService;
    private final MlModelRegistryService mlModelRegistryService;
    private final MlTrainingService mlTrainingService;
    private final SeasonHealthService seasonHealthService;
    private final ScrapeHistoryService scrapeHistoryService;
    private final AutomationService automationService;
    private final TournamentReclassifier tournamentReclassifier;

    public AdminController(SeasonRepository seasonRepository,
                           AsyncScrapeService asyncScrapeService,
                           MlModelRegistryService mlModelRegistryService,
                           MlTrainingService mlTrainingService,
                           SeasonHealthService seasonHealthService,
                           ScrapeHistoryService scrapeHistoryService,
                           AutomationService automationService,
                           TournamentReclassifier tournamentReclassifier) {
        this.seasonRepository    = seasonRepository;
        this.asyncScrapeService  = asyncScrapeService;
        this.mlModelRegistryService = mlModelRegistryService;
        this.mlTrainingService   = mlTrainingService;
        this.seasonHealthService = seasonHealthService;
        this.scrapeHistoryService = scrapeHistoryService;
        this.automationService = automationService;
        this.tournamentReclassifier = tournamentReclassifier;
    }

    @GetMapping
    public String dashboard(Model model) {
        List<Season> seasons = seasonRepository.findAll();
        seasons.sort((a, b) -> b.getYear().compareTo(a.getYear()));

        Map<Integer, SeasonHealth> healthByYear = new LinkedHashMap<>();
        for (Season s : seasons) {
            healthByYear.put(s.getYear(), seasonHealthService.getHealth(s));
        }

        List<ScrapeHistoryEntry> historyEntries = scrapeHistoryService.recentEntries();
        AutomationStatus automation = automationService.getStatus();

        model.addAttribute("seasons", seasons);
        model.addAttribute("healthByYear", healthByYear);
        model.addAttribute("entries", historyEntries);
        model.addAttribute("automation", automation);
        model.addAttribute("mlModels", mlModelRegistryService.modelViews());
        mlTrainingService.pollActiveRuns();
        model.addAttribute("trainingRuns", mlTrainingService.recentRuns());
        model.addAttribute("trainingInProgress", mlTrainingService.isTrainingInProgress());
        return "admin/dashboard";
    }

    @PostMapping("/seasons")
    public String addSeason(@RequestParam Integer year,
                            @RequestParam(name = "initialize", required = false, defaultValue = "true") boolean initialize,
                            @RequestParam(name = "autoRefresh", required = false, defaultValue = "true") boolean autoRefresh,
                            RedirectAttributes redirectAttributes) {
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
        season.setAutoRefresh(autoRefresh);
        seasonRepository.save(season);

        if (initialize) {
            asyncScrapeService.scrapeFullSeasonAsync(year, ScrapeBatch.Source.AUTO_INITIALIZE);
            redirectAttributes.addFlashAttribute("success",
                    "Season " + year + " added — full scrape initializing in the background");
        } else {
            redirectAttributes.addFlashAttribute("success", "Season " + year + " added");
        }
        return "redirect:/admin";
    }

    @PostMapping("/seasons/{year}/auto-refresh")
    public String toggleAutoRefresh(@PathVariable Integer year,
                                    @RequestParam(name = "enabled", defaultValue = "false") boolean enabled,
                                    RedirectAttributes redirectAttributes) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        if (season == null) {
            redirectAttributes.addFlashAttribute("error", "Season " + year + " not found");
            return "redirect:/admin";
        }
        season.setAutoRefresh(enabled);
        seasonRepository.save(season);
        redirectAttributes.addFlashAttribute("success",
                "Auto-refresh " + (enabled ? "enabled" : "disabled") + " for " + year);
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

    @PostMapping("/scrape/full/all")
    public String scrapeAllSeasonsFull(RedirectAttributes redirectAttributes) {
        List<Season> seasons = seasonRepository.findAll();
        seasons.sort((a, b) -> a.getYear().compareTo(b.getYear()));
        if (seasons.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No seasons to scrape — add a season first");
            return "redirect:/admin";
        }
        List<Integer> years = seasons.stream().map(Season::getYear).toList();
        asyncScrapeService.scrapeAllSeasonsFullAsync(years);
        redirectAttributes.addFlashAttribute("success",
                "Full pipeline re-scrape started for all " + years.size()
                        + " seasons (sequential) — watch Scrape History for progress");
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

    @PostMapping("/scrape/game-stats/{year}")
    public String backfillGameStats(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.backfillGameStatsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Game stats backfill started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/stats/{year}")
    public String calculateStats(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.calculateStatsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Stats calculation started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/scrape/timeseries/{year}")
    public String calculateTimeSeries(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.calculateTimeSeriesAsync(year);
        redirectAttributes.addFlashAttribute("success", "Time-series stats calculation started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/power-ratings/{year}")
    public String calculatePowerRatings(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        asyncScrapeService.calculatePowerRatingsAsync(year);
        redirectAttributes.addFlashAttribute("success", "Power ratings calculation started for " + year);
        return "redirect:/admin";
    }

    @PostMapping("/reclassify-tournaments/{year}")
    public String reclassifyTournaments(@PathVariable Integer year, RedirectAttributes redirectAttributes) {
        TournamentReclassifier.Result result = tournamentReclassifier.reclassifySeason(year);
        redirectAttributes.addFlashAttribute("success",
                "Tournament re-classify " + year + ": " + result.updated() + "/" + result.total() + " games updated");
        return "redirect:/admin";
    }

    @GetMapping("/scrape-history")
    public String scrapeHistory(Model model) {
        model.addAttribute("entries", scrapeHistoryService.recentEntries());
        return "admin/fragments/scrape-history :: scrape-table";
    }

    @PostMapping("/automation/run-now")
    public String runScheduledCycleNow(RedirectAttributes redirectAttributes) {
        asyncScrapeService.runScheduledCycleAsync();
        redirectAttributes.addFlashAttribute("success",
                "Scheduled re-scrape cycle kicked off — watch Scrape History for progress");
        return "redirect:/admin";
    }

    /** Returns the ML models card fragment (HTMX). */
    @GetMapping("/ml/status")
    public String mlStatus(Model model) {
        model.addAttribute("mlModels", mlModelRegistryService.modelViews());
        return "admin/fragments/ml-status :: ml-status-card";
    }

    /**
     * Rescans the model directory, reloads all bundles, and reconciles the registry.
     * Requires admin authentication (form login or HTTP Basic for script access).
     */
    @PostMapping("/ml/reload")
    public String mlReload(Model model,
                           @RequestHeader(value = "HX-Request", required = false) String htmxRequest,
                           RedirectAttributes redirectAttributes) {
        var statuses = mlModelRegistryService.reloadAndReconcile();
        if (htmxRequest != null) {
            model.addAttribute("mlModels", mlModelRegistryService.modelViews());
            return "admin/fragments/ml-status :: ml-status-card";
        }
        redirectAttributes.addFlashAttribute("success",
                "ML bundles reloaded — " + statuses.size() + " loaded");
        return "redirect:/admin";
    }

    /**
     * Starts a training run on the trainer service. On completion (detected by the
     * polled status fragment) models are hot-reloaded and evaluation re-runs.
     */
    @PostMapping("/ml/train")
    public String mlTrain(@RequestParam(defaultValue = "baseline") String modelSlug,
                          @RequestParam(required = false) String featureSet,
                          RedirectAttributes redirectAttributes) {
        try {
            var run = mlTrainingService.startTraining(modelSlug.trim(), featureSet);
            redirectAttributes.addFlashAttribute("success",
                    "Training '" + run.getModelSlug() + "' (seasons " + run.getTrainSeasons()
                            + ") — models reload and evaluations refresh automatically when it finishes");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    /** Makes a model ACTIVE and the site default (fills the main prediction fields). */
    @PostMapping("/ml/models/{slug}/promote")
    public String mlPromote(@PathVariable String slug, RedirectAttributes redirectAttributes) {
        mlModelRegistryService.promote(slug);
        redirectAttributes.addFlashAttribute("success", "Model '" + slug + "' is now the default");
        return "redirect:/admin";
    }

    /** Makes a model ACTIVE (publicly served) without changing the default. */
    @PostMapping("/ml/models/{slug}/activate")
    public String mlActivate(@PathVariable String slug, RedirectAttributes redirectAttributes) {
        mlModelRegistryService.activate(slug);
        redirectAttributes.addFlashAttribute("success", "Model '" + slug + "' activated");
        return "redirect:/admin";
    }

    /** Retires a model (not served, not evaluated; files and history kept). */
    @PostMapping("/ml/models/{slug}/retire")
    public String mlRetire(@PathVariable String slug, RedirectAttributes redirectAttributes) {
        mlModelRegistryService.retire(slug);
        redirectAttributes.addFlashAttribute("success", "Model '" + slug + "' retired");
        return "redirect:/admin";
    }

    /** Moves a retired model back to shadow (CANDIDATE) evaluation. */
    @PostMapping("/ml/models/{slug}/reinstate")
    public String mlReinstate(@PathVariable String slug, RedirectAttributes redirectAttributes) {
        mlModelRegistryService.reinstate(slug);
        redirectAttributes.addFlashAttribute("success", "Model '" + slug + "' reinstated as candidate");
        return "redirect:/admin";
    }

    /** HTMX-polled fragment: reconciles RUNNING rows with the trainer, then renders history. */
    @GetMapping("/ml/training-status")
    public String mlTrainingStatus(Model model) {
        mlTrainingService.pollActiveRuns();
        model.addAttribute("trainingRuns", mlTrainingService.recentRuns());
        model.addAttribute("trainingInProgress", mlTrainingService.isTrainingInProgress());
        return "admin/fragments/ml-training-runs :: training-runs";
    }

    /** Incrementally evaluates model predictions vs. results for all seasons (async). */
    @PostMapping("/ml/evaluate")
    public String evaluatePredictions(RedirectAttributes redirectAttributes) {
        return kickOffEvaluation(false, redirectAttributes);
    }

    /** Deletes and fully recomputes prediction evaluations for all seasons (async). */
    @PostMapping("/ml/evaluate/rebuild")
    public String rebuildPredictionEvaluations(RedirectAttributes redirectAttributes) {
        return kickOffEvaluation(true, redirectAttributes);
    }

    private String kickOffEvaluation(boolean rebuild, RedirectAttributes redirectAttributes) {
        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted()
                .toList();
        if (years.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No seasons configured");
            return "redirect:/admin";
        }
        asyncScrapeService.evaluatePredictionsAsync(years, rebuild);
        redirectAttributes.addFlashAttribute("success",
                (rebuild ? "Prediction evaluation rebuild" : "Prediction evaluation")
                        + " started for " + years.size() + " season(s)");
        return "redirect:/admin";
    }
}
