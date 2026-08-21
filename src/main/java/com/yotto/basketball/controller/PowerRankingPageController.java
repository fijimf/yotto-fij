package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.PowerRankingPageService;
import com.yotto.basketball.service.PowerRankingPageService.RankingModel;
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
        return "pages/power-ranking";
    }

    private RankingModel requireModel(String slug) {
        return RankingModel.fromSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown ranking model: " + slug));
    }
}
