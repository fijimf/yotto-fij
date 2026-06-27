package com.yotto.basketball.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.controller.dto.StatPageDto;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.StatCatalog;
import com.yotto.basketball.service.StatFormat;
import com.yotto.basketball.service.StatPageService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class StatPageController {

    private final StatPageService statPageService;
    private final SeasonRepository seasonRepository;
    private final ObjectMapper objectMapper;

    public StatPageController(StatPageService statPageService,
                              SeasonRepository seasonRepository,
                              ObjectMapper objectMapper) {
        this.statPageService = statPageService;
        this.seasonRepository = seasonRepository;
        this.objectMapper = objectMapper;
    }

    /** Glossary: every stat grouped by category, each linking to its page. */
    @GetMapping("/stats")
    public String statsIndex(Model model) {
        Map<String, List<StatCatalog.StatInfo>> byCategory = new LinkedHashMap<>();
        for (StatCatalog.StatInfo info : StatCatalog.all()) {
            byCategory.computeIfAbsent(info.category(), k -> new java.util.ArrayList<>()).add(info);
        }
        Integer latestYear = seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear).orElse(null);
        model.addAttribute("currentPage", "seasons");
        model.addAttribute("statsByCategory", byCategory);
        model.addAttribute("latestYear", latestYear);
        return "pages/stats-index";
    }

    /** Year-less entry point: redirect to the latest season's page for this stat. */
    @GetMapping("/stats/{statName}")
    public String statLatest(@PathVariable String statName) {
        StatCatalog.require(statName); // 404 early on unknown stat
        int latestYear = seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
        return "redirect:/seasons/" + latestYear + "/stats/" + statName;
    }

    @GetMapping("/seasons/{year}/stats/{statName}")
    public String statDetail(@PathVariable int year,
                             @PathVariable String statName,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             Model model) {
        StatPageDto dto = statPageService.build(year, statName, date);
        addStatPageModel(model, dto);
        model.addAttribute("currentPage", "seasons");
        return "pages/stat-detail";
    }

    /** HTMX fragment: re-render the ranking table for a different date. */
    @GetMapping("/seasons/{year}/stats/{statName}/table")
    public String statTable(@PathVariable int year,
                            @PathVariable String statName,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            Model model) {
        StatPageDto dto = statPageService.build(year, statName, date);
        addStatPageModel(model, dto);
        return "fragments/stat-rank-table :: rank-table";
    }

    private void addStatPageModel(Model model, StatPageDto dto) {
        model.addAttribute("statPage", dto);
        model.addAttribute("fmt", new StatFormat());
        try {
            model.addAttribute("statPageJson", objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException e) {
            model.addAttribute("statPageJson", "null");
        }
    }
}
