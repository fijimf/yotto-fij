package com.yotto.basketball.controller;

import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Controller
public class PredictionsPageController {

    private final PredictionService predictionService;

    public PredictionsPageController(PredictionService predictionService) {
        this.predictionService = predictionService;
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
