package com.yotto.basketball.controller;

import com.yotto.basketball.controller.dto.NavTab;
import com.yotto.basketball.service.ModelAboutService;
import com.yotto.basketball.service.PublicModelService;
import com.yotto.basketball.service.PublicModelService.PublicModel;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * The Models section hub (spec §7): /models index and the per-model About
 * pages. Literal routes (/models/compare, /models/matchup) always win over the
 * {slug} pattern; unknown or non-public slugs 404 via PublicModelService.
 */
@Controller
public class ModelHubController {

    private final PublicModelService publicModelService;
    private final ModelAboutService modelAboutService;
    private final com.yotto.basketball.service.ModelScheduleService modelScheduleService;
    private final com.yotto.basketball.service.ModelBracketService modelBracketService;

    public ModelHubController(PublicModelService publicModelService,
                              ModelAboutService modelAboutService,
                              com.yotto.basketball.service.ModelScheduleService modelScheduleService,
                              com.yotto.basketball.service.ModelBracketService modelBracketService) {
        this.publicModelService = publicModelService;
        this.modelAboutService = modelAboutService;
        this.modelScheduleService = modelScheduleService;
        this.modelBracketService = modelBracketService;
    }

    @GetMapping("/models")
    public String index(Model model) {
        model.addAttribute("models", publicModelService.list());
        model.addAttribute("headlines", modelAboutService.headlines());
        model.addAttribute("currentPage", "models");
        model.addAttribute("currentSection", "models");
        return "pages/models-index";
    }

    @GetMapping("/models/{slug}")
    public String about(@PathVariable String slug,
                        @RequestParam(required = false) String year,
                        @RequestParam(required = false, defaultValue = "all") String segment,
                        Model model) {
        ModelAboutService.AboutPage page = modelAboutService.build(slug, year, segment);
        model.addAttribute("page", page);
        model.addAttribute("hubTabs", hubTabs(page.model(), "about"));
        model.addAttribute("currentPage", slug);
        model.addAttribute("currentSection", "models");
        return "pages/model-about";
    }

    @GetMapping("/models/{slug}/schedule")
    public String schedule(@PathVariable String slug,
                           @RequestParam(required = false)
                           @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                           java.time.LocalDate date,
                           Model model) {
        var page = modelScheduleService.build(slug, date);
        model.addAttribute("page", page);
        model.addAttribute("hubTabs", hubTabs(page.model(), "schedule"));
        model.addAttribute("currentPage", slug);
        model.addAttribute("currentSection", "models");
        return "pages/model-schedule";
    }

    @GetMapping("/models/{slug}/bracket")
    public String bracketLatest(@PathVariable String slug) {
        requireModel(slug);
        List<Integer> years = modelBracketService.bracketYears();
        if (years.isEmpty()) {
            return "redirect:/models/" + slug;
        }
        return "redirect:/models/" + slug + "/bracket/" + years.get(0);
    }

    @GetMapping("/models/{slug}/bracket/{year}")
    public String bracket(@PathVariable String slug, @PathVariable int year, Model model) {
        var page = modelBracketService.build(slug, year);
        List<NavTab> yearTabs = page.years().stream()
                .map(y -> new NavTab(String.valueOf(y), "/models/" + slug + "/bracket/" + y, y == year))
                .toList();
        model.addAttribute("page", page);
        model.addAttribute("modelOverlay", page.overlay());
        model.addAttribute("yearTabs", yearTabs);
        model.addAttribute("hubTabs", hubTabs(page.model(), "bracket"));
        model.addAttribute("currentPage", slug);
        model.addAttribute("currentSection", "models");
        return "pages/model-bracket";
    }

    /** The hub tab strip: About | Schedule | Bracket | Matchup. */
    static List<NavTab> hubTabs(PublicModel model, String active) {
        String base = "/models/" + model.slug();
        return List.of(
                new NavTab("About", base, "about".equals(active)),
                new NavTab("Schedule", base + "/schedule", "schedule".equals(active)),
                new NavTab("Bracket", base + "/bracket", "bracket".equals(active)),
                new NavTab("Matchup", "/models/matchup?model=" + model.slug(), false));
    }

    /** Guard shared by the sub-pages. */
    PublicModel requireModel(String slug) {
        return publicModelService.find(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown model: " + slug));
    }
}
