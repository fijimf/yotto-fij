package com.yotto.basketball.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.security.AppUserDetails;
import com.yotto.basketball.service.CorrelationDataService;
import com.yotto.basketball.service.PreferenceKeys;
import com.yotto.basketball.service.UserPreferenceService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Correlation explorer (spec §5.5): pick 2–8 variables, see the scatter/Pearson
 * matrix. Selection precedence: URL ?vars= → the signed-in user's saved
 * preference → the built-in default. Selections are URL-encoded so any view is
 * shareable.
 */
@Controller
public class CorrelationPageController {

    private final CorrelationDataService correlationDataService;
    private final SeasonRepository seasonRepository;
    private final UserPreferenceService preferenceService;
    private final ObjectMapper objectMapper;

    public CorrelationPageController(CorrelationDataService correlationDataService,
                                     SeasonRepository seasonRepository,
                                     UserPreferenceService preferenceService,
                                     ObjectMapper objectMapper) {
        this.correlationDataService = correlationDataService;
        this.seasonRepository = seasonRepository;
        this.preferenceService = preferenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stats/correlation")
    public String correlationLatest() {
        int latestYear = seasonRepository.findTopByOrderByYearDesc()
                .map(Season::getYear)
                .orElseThrow(() -> new EntityNotFoundException("No seasons found"));
        return "redirect:/seasons/" + latestYear + "/stats/correlation";
    }

    @GetMapping("/seasons/{year}/stats/correlation")
    public String correlation(@PathVariable int year,
                              @RequestParam(required = false) String vars,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @AuthenticationPrincipal AppUserDetails principal,
                              Model model) {
        List<String> varIds = resolveSelection(vars, principal);
        var page = correlationDataService.build(year, varIds, date);

        model.addAttribute("page", page);
        model.addAttribute("variablesByGroup", correlationDataService.variablesByGroup());
        model.addAttribute("selectedIds", page.selected().stream()
                .map(CorrelationDataService.Variable::id).toList());
        model.addAttribute("maxVars", CorrelationDataService.MAX_VARS);
        model.addAttribute("canSave", principal != null);
        try {
            model.addAttribute("matrixJson", objectMapper.writeValueAsString(
                    new MatrixPayload(page.selected(), page.rows())));
        } catch (JsonProcessingException e) {
            model.addAttribute("matrixJson", "null");
        }
        model.addAttribute("currentPage", "correlation");
        model.addAttribute("currentSection", "stats");
        return "pages/correlation";
    }

    /** Payload handed to js/scatter-matrix.js. */
    record MatrixPayload(List<CorrelationDataService.Variable> vars,
                         List<CorrelationDataService.TeamRow> rows) {}

    @PostMapping("/stats/correlation/save-default")
    public String saveDefault(@RequestParam String vars,
                              @RequestParam int year,
                              @AuthenticationPrincipal AppUserDetails principal,
                              RedirectAttributes redirectAttributes) {
        List<String> valid = sanitize(vars);
        if (valid.size() >= CorrelationDataService.MIN_VARS) {
            preferenceService.set(principal.getId(), PreferenceKeys.STATS_CORRELATION_VARS,
                    String.join(",", valid));
            redirectAttributes.addFlashAttribute("success", "Saved as your default selection.");
        }
        return "redirect:/seasons/" + year + "/stats/correlation?vars=" + String.join(",", valid);
    }

    /** URL vars → saved preference → default; anything invalid falls through. */
    private List<String> resolveSelection(String vars, AppUserDetails principal) {
        if (vars != null) {
            List<String> fromUrl = sanitize(vars);
            if (fromUrl.size() >= CorrelationDataService.MIN_VARS) return fromUrl;
        }
        if (principal != null) {
            List<String> fromPref = preferenceService.get(principal.getId(), PreferenceKeys.STATS_CORRELATION_VARS)
                    .map(this::sanitize)
                    .orElse(List.of());
            if (fromPref.size() >= CorrelationDataService.MIN_VARS) return fromPref;
        }
        return CorrelationDataService.DEFAULT_VARS;
    }

    private List<String> sanitize(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> correlationDataService.resolve(s) != null)
                .distinct()
                .limit(CorrelationDataService.MAX_VARS)
                .toList();
    }
}
