package com.yotto.basketball.controller;

import com.yotto.basketball.service.ModelComparePageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Public model-performance page: aggregate prediction accuracy per model (spread,
 * total, win probability) for a season, benchmarked against the book closing line.
 * All view-model assembly (and its caching) lives in {@link ModelComparePageService}.
 */
@Controller
public class ModelPerformanceController {

    private final ModelComparePageService comparePageService;

    public ModelPerformanceController(ModelComparePageService comparePageService) {
        this.comparePageService = comparePageService;
    }

    @GetMapping("/models/compare")
    public String performance(@RequestParam(required = false) String year,
                              @RequestParam(defaultValue = "season") String window,
                              @RequestParam(defaultValue = "all") String segment,
                              @RequestParam(required = false) String cmodel,
                              Model model) {
        model.addAllAttributes(comparePageService.build(year, window, segment, cmodel));
        model.addAttribute("currentPage", "compare");
        model.addAttribute("currentSection", "models");
        return "pages/model-performance";
    }
}
