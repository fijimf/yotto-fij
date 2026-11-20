package com.yotto.basketball.controller;

import com.yotto.basketball.service.ChartDataService;
import com.yotto.basketball.service.ChartDataService.EfficiencyLandscape;
import com.yotto.basketball.service.ChartDataService.RatingRiver;
import com.yotto.basketball.service.ChartDataService.RiverModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JSON payloads for the season-animation charts (public, cached in ChartDataService). */
@RestController
public class ChartDataController {

    private final ChartDataService chartDataService;

    public ChartDataController(ChartDataService chartDataService) {
        this.chartDataService = chartDataService;
    }

    @GetMapping("/api/charts/rating-river")
    public RatingRiver ratingRiver(@RequestParam long conferenceId,
                                   @RequestParam int year,
                                   @RequestParam(defaultValue = "massey") String model) {
        RiverModel riverModel = RiverModel.fromSlug(model)
                .orElseThrow(() -> new IllegalArgumentException("Unknown river model: " + model));
        return chartDataService.buildRatingRiver(conferenceId, year, riverModel);
    }

    @GetMapping("/api/charts/efficiency-landscape")
    public EfficiencyLandscape efficiencyLandscape(@RequestParam int year) {
        return chartDataService.buildEfficiencyLandscape(year);
    }
}
