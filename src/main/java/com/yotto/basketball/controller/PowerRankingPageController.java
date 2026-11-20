package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.PowerRankingPageService;
import com.yotto.basketball.controller.dto.NavTab;
import com.yotto.basketball.service.PowerRankingPageService.RankingModel;
import java.util.List;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/**
 * Per-model power ranking pages (spec §6.2). The slug set is fixed by
 * {@link RankingModel}; anything else 404s — which also keeps
 * /rankings/{year}/table (numeric segment + literal) unambiguous.
 */
@Controller
public class PowerRankingPageController {

    private final PowerRankingPageService pageService;
    private final SeasonRepository seasonRepository;

    public PowerRankingPageController(PowerRankingPageService pageService,
                                      SeasonRepository seasonRepository) {
        this.pageService = pageService;
        this.seasonRepository = seasonRepository;
    }

    @GetMapping("/rankings/{modelSlug:[a-z-]+}")
    public String modelLatest(@PathVariable String modelSlug) {
        RankingModel model = requireModel(modelSlug);
        int latestYear = seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
        return "redirect:/seasons/" + latestYear + "/rankings/" + model.getSlug();
    }

    @GetMapping("/seasons/{year}/rankings/{modelSlug}")
    public String modelPage(@PathVariable int year,
                            @PathVariable String modelSlug,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            Model model) {
        RankingModel rankingModel = requireModel(modelSlug);
        model.addAttribute("page", pageService.build(year, rankingModel, date));
        model.addAttribute("currentPage", rankingModel.getSlug());
        model.addAttribute("currentSection", "rankings");
        if (rankingModel == RankingModel.ADJUSTED_EFFICIENCY) {
            model.addAttribute("rankingTabs", landscapeTabs(year, true));
        }
        return "pages/power-ranking";
    }

    @GetMapping("/rankings/adjusted-efficiency/landscape")
    public String landscapeLatest() {
        int latestYear = seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
        return "redirect:/seasons/" + latestYear + "/rankings/adjusted-efficiency/landscape";
    }

    /**
     * The adjusted-efficiency model's picture: AdjO vs AdjD for every team, played
     * across the season. Data arrives as JSON from /api/charts/efficiency-landscape.
     */
    @GetMapping("/seasons/{year}/rankings/adjusted-efficiency/landscape")
    public String landscape(@PathVariable int year, Model model) {
        seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear).sorted(java.util.Comparator.reverseOrder()).toList();
        model.addAttribute("year", year);
        model.addAttribute("availableSeasons", years);
        model.addAttribute("rankingTabs", landscapeTabs(year, false));
        model.addAttribute("currentPage", RankingModel.ADJUSTED_EFFICIENCY.getSlug());
        model.addAttribute("currentSection", "rankings");
        return "pages/efficiency-landscape";
    }

    private static List<NavTab> landscapeTabs(int year, boolean tableActive) {
        String base = "/seasons/" + year + "/rankings/adjusted-efficiency";
        return List.of(new NavTab("Table", base, tableActive),
                       new NavTab("Landscape", base + "/landscape", !tableActive));
    }

    private RankingModel requireModel(String slug) {
        return RankingModel.fromSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown ranking model: " + slug));
    }
}
