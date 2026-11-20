package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.ChartDataService;
import com.yotto.basketball.service.ChartDataService.Fingerprints;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Team fingerprints: one radial four-factors glyph per team, the whole league in a grid. */
@Controller
public class FingerprintPageController {

    private final ChartDataService chartDataService;
    private final SeasonRepository seasonRepository;

    public FingerprintPageController(ChartDataService chartDataService, SeasonRepository seasonRepository) {
        this.chartDataService = chartDataService;
        this.seasonRepository = seasonRepository;
    }

    @GetMapping("/stats/fingerprints")
    public String latest() {
        int year = seasonRepository.findTopByOrderByYearDesc().map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
        return "redirect:/seasons/" + year + "/stats/fingerprints";
    }

    @GetMapping("/seasons/{year}/stats/fingerprints")
    public String page(@PathVariable int year,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        Fingerprints data = chartDataService.buildFingerprints(year, date);   // 404s on unknown season
        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear).sorted(Comparator.reverseOrder()).toList();
        model.addAttribute("year", year);
        model.addAttribute("availableSeasons", years);
        model.addAttribute("date", data.date());
        model.addAttribute("latestDate", data.latestDate());
        model.addAttribute("axes", data.axes());
        model.addAttribute("currentPage", "fingerprints");
        model.addAttribute("currentSection", "stats");
        return "pages/fingerprints";
    }
}
