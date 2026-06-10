package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.BracketService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
public class BracketWebController {

    private final BracketService bracketService;
    private final SeasonRepository seasonRepository;

    public BracketWebController(BracketService bracketService, SeasonRepository seasonRepository) {
        this.bracketService = bracketService;
        this.seasonRepository = seasonRepository;
    }

    /** Nav entry point: jump to the most recent season with NCAA tournament games. */
    @GetMapping("/bracket")
    public String latestBracket() {
        Integer year = bracketService.latestBracketYear()
                .orElseGet(() -> seasonRepository.findAll().stream()
                        .map(Season::getYear)
                        .max(Integer::compareTo)
                        .orElse(null));
        if (year == null) return "redirect:/";
        return "redirect:/seasons/" + year + "/bracket";
    }

    @GetMapping("/seasons/{year}/bracket")
    public String bracket(@PathVariable Integer year, Model model) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        List<Season> allSeasons = seasonRepository.findAll();
        allSeasons.sort((a, b) -> b.getYear().compareTo(a.getYear()));

        model.addAttribute("currentPage", "bracket");
        model.addAttribute("season", season);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("bracket", bracketService.buildBracket(year).orElse(null));
        return "pages/bracket";
    }
}
