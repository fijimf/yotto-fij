package com.yotto.basketball.controller.dto;

/**
 * One prediction model's pre-game point in score space for the game chart.
 * {@code spread} is the predicted home margin (positive = home favored, i.e. the
 * model convention, NOT the book handicap); {@code total} the predicted combined
 * score. Predicted home score = (total + spread) / 2, away = (total − spread) / 2.
 */
public record ChartModelPointDto(
        String type,                 // MASSEY, ADJ_EFF, ML:<slug> — drives modelColor()
        String label,                // display name
        double spread,
        double total,
        Double homeWinProbability    // null when the model has no probability
) {}
