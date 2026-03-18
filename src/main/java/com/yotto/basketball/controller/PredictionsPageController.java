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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Controller
public class PredictionsPageController {

    private final PredictionService predictionService;
    private final TeamService teamService;

    public PredictionsPageController(PredictionService predictionService, TeamService teamService) {
        this.predictionService = predictionService;
        this.teamService = teamService;
    }

    @GetMapping("/predictions")
    public String predictions(@RequestParam(defaultValue = "7") int days, Model model) {
        populateModel(days, model);
        model.addAttribute("currentPage", "predictions");
        return "pages/predictions";
    }

    @GetMapping("/predictions/list")
    public String predictionsList(@RequestParam(defaultValue = "7") int days, Model model) {
        populateModel(days, model);
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

    private void populateModel(int days, Model model) {
        int clamped = Math.min(Math.max(days, 1), 30);
        List<PredictionResult> predictions = predictionService.getUpcoming(clamped);
        Map<LocalDate, List<PredictionResult>> byDate = predictions.stream()
                .collect(Collectors.groupingBy(PredictionResult::gameDate,
                        TreeMap::new, Collectors.toList()));
        model.addAttribute("predictionsByDate", byDate);
        model.addAttribute("days", clamped);
        model.addAttribute("today", LocalDate.now());
    }
}
