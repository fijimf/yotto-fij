package com.yotto.basketball.controller;

import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /** Full prediction for a single game. 404 if the game does not exist. */
    @GetMapping("/game/{gameId}")
    public PredictionResult game(@PathVariable Long gameId) {
        return predictionService.predict(gameId);
    }

    /**
     * Predictions for all SCHEDULED games in the next {@code days} calendar days.
     * Defaults to 7, capped at 30. IN_PROGRESS games are excluded.
     */
    @GetMapping("/upcoming")
    public List<PredictionResult> upcoming(
            @RequestParam(defaultValue = "7") int days) {
        return predictionService.getUpcoming(days);
    }
}
