package com.yotto.basketball.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.controller.dto.StatPageDto;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.StatFormat;
import com.yotto.basketball.service.StatPageService;
import com.yotto.basketball.service.StatPredictivenessService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The Predictor section (spec §5.4): an index ranking every stat by standalone
 * winner-prediction power (AUC), and a per-stat detail page hosting the
 * red/green entering-value scatter that used to live on the stat-detail page.
 */
@Controller
public class PredictorPageController {

    private final StatPredictivenessService predictivenessService;
    private final StatPageService statPageService;
    private final SeasonRepository seasonRepository;
    private final ObjectMapper objectMapper;

    public PredictorPageController(StatPredictivenessService predictivenessService,
                                   StatPageService statPageService,
                                   SeasonRepository seasonRepository,
                                   ObjectMapper objectMapper) {
        this.predictivenessService = predictivenessService;
        this.statPageService = statPageService;
        this.seasonRepository = seasonRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stats/predictor")
    public String predictorLatest() {
        return "redirect:/seasons/" + latestYear() + "/stats/predictor";
    }

    @GetMapping("/seasons/{year}/stats/predictor")
    public String predictorIndex(@PathVariable int year, Model model) {
        model.addAttribute("index", predictivenessService.build(year));
        model.addAttribute("currentPage", "predictor");
        model.addAttribute("currentSection", "stats");
        return "pages/predictor-index";
    }

    @GetMapping("/stats/predictor/{statName}")
    public String predictorDetailLatest(@PathVariable String statName) {
        com.yotto.basketball.service.StatCatalog.require(statName);
        return "redirect:/seasons/" + latestYear() + "/stats/predictor/" + statName;
    }

    @GetMapping("/seasons/{year}/stats/predictor/{statName}")
    public String predictorDetail(@PathVariable int year,
                                  @PathVariable String statName,
                                  Model model) {
        StatPageDto dto = statPageService.build(year, statName, null);
        model.addAttribute("statPage", dto);
        model.addAttribute("fmt", new StatFormat());
        model.addAttribute("aucBand", aucBand(dto.scatter().predictive().auc()));
        try {
            model.addAttribute("statPageJson", objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException e) {
            model.addAttribute("statPageJson", "null");
        }
        model.addAttribute("currentPage", "predictor");
        model.addAttribute("currentSection", "stats");
        return "pages/predictor-detail";
    }

    private int latestYear() {
        return seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
    }

    /** Plain-language read of the AUC for the usefulness panel. */
    static String aucBand(Double auc) {
        if (auc == null) return "Not enough games to measure yet.";
        if (auc < 0.55) return "On its own, no better than a coin flip at picking winners.";
        if (auc < 0.65) return "A weak signal — better than a coin flip, but not by much.";
        if (auc < 0.75) return "A moderate signal — one of the more informative single stats.";
        return "A strong signal — unusually predictive for a single stat.";
    }
}
