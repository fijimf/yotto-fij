package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Team;
import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import com.yotto.basketball.service.TeamService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class PredictionsPageController {

    private final PredictionService predictionService;
    private final TeamService teamService;
    private final com.yotto.basketball.service.PublicModelService publicModelService;

    public PredictionsPageController(PredictionService predictionService,
                                     TeamService teamService,
                                     com.yotto.basketball.service.PublicModelService publicModelService) {
        this.predictionService = predictionService;
        this.teamService = teamService;
        this.publicModelService = publicModelService;
    }

    /* /predictions retired (spec OQ-3): the multi-model upcoming-cards page is
       superseded by the per-model schedule pages — see LegacyRedirectController. */

    @GetMapping("/models/matchup")
    public String matchup(@RequestParam(required = false) String model,
                          Model uiModel) {
        List<Team> teams = teamService.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .sorted(Comparator.comparing(Team::getName))
                .collect(Collectors.toList());
        uiModel.addAttribute("teams", teams);
        uiModel.addAttribute("today", LocalDate.now());
        uiModel.addAttribute("highlightModel", publicModelService.find(model == null ? "" : model)
                .map(com.yotto.basketball.service.PublicModelService.PublicModel::slug).orElse(null));
        uiModel.addAttribute("currentPage", "matchup");
        uiModel.addAttribute("currentSection", "models");
        return "pages/matchup";
    }

    /* Old path kept as an alias for one release: HTMX pages cached by browsers
       may still fire the legacy fragment URL. */
    @GetMapping({"/models/matchup/result", "/predictions/matchup/result"})
    public String matchupResult(
            @RequestParam Long homeTeamId,
            @RequestParam Long awayTeamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean neutral,
            @RequestParam(required = false) String highlight,
            Model model) {
        if (homeTeamId.equals(awayTeamId)) {
            model.addAttribute("error", "Home and away teams must be different.");
            return "fragments/matchup-result :: matchup-result";
        }
        try {
            PredictionResult result = predictionService.predictMatchup(homeTeamId, awayTeamId, date, neutral);
            model.addAttribute("result", result);
            // ML bundle rows: default model first, in public-model order
            model.addAttribute("mlRows", publicModelService.list().stream()
                    .filter(com.yotto.basketball.service.PublicModelService.PublicModel::ml)
                    .filter(m -> result.mlModels() != null && result.mlModels().containsKey(m.slug()))
                    .toList());
            model.addAttribute("highlightModel", highlight);
        } catch (Exception e) {
            model.addAttribute("error", "Could not generate prediction: " + e.getMessage());
        }
        return "fragments/matchup-result :: matchup-result";
    }

}
