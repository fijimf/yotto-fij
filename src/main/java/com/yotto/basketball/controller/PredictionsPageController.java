package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Team;
import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import com.yotto.basketball.service.PredictionsPageService;
import com.yotto.basketball.service.PredictionsPageService.PredictionsPage;
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
    private final PredictionsPageService predictionsPageService;
    private final TeamService teamService;

    public PredictionsPageController(PredictionService predictionService,
                                     PredictionsPageService predictionsPageService,
                                     TeamService teamService) {
        this.predictionService = predictionService;
        this.predictionsPageService = predictionsPageService;
        this.teamService = teamService;
    }

    @GetMapping("/predictions")
    public String predictions(@RequestParam(required = false) String date,
                              @RequestParam(defaultValue = "7") int days,
                              @RequestParam(required = false) String model,
                              Model uiModel) {
        populateModel(date, days, model, uiModel);
        uiModel.addAttribute("currentPage", "predictions");
        return "pages/predictions";
    }

    @GetMapping("/predictions/list")
    public String predictionsList(@RequestParam(required = false) String date,
                                  @RequestParam(defaultValue = "7") int days,
                                  @RequestParam(required = false) String model,
                                  Model uiModel) {
        populateModel(date, days, model, uiModel);
        return "fragments/predictions-list :: predictions-list";
    }

    @GetMapping("/predictions/matchup")
    public String matchup(Model model) {
        List<Team> teams = teamService.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .sorted(Comparator.comparing(Team::getName))
                .collect(Collectors.toList());
        model.addAttribute("teams", teams);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("currentPage", "matchup");
        return "pages/matchup";
    }

    @GetMapping("/predictions/matchup/result")
    public String matchupResult(
            @RequestParam Long homeTeamId,
            @RequestParam Long awayTeamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean neutral,
            Model model) {
        if (homeTeamId.equals(awayTeamId)) {
            model.addAttribute("error", "Home and away teams must be different.");
            return "fragments/matchup-result :: matchup-result";
        }
        try {
            PredictionResult result = predictionService.predictMatchup(homeTeamId, awayTeamId, date, neutral);
            model.addAttribute("result", result);
        } catch (Exception e) {
            model.addAttribute("error", "Could not generate prediction: " + e.getMessage());
        }
        return "fragments/matchup-result :: matchup-result";
    }

    private void populateModel(String date, int days, String model, Model uiModel) {
        PredictionsPage page = predictionsPageService.build(date, days, model);
        uiModel.addAttribute("page", page);
        uiModel.addAttribute("today", LocalDate.now(com.yotto.basketball.util.EasternDates.EASTERN));
    }
}
