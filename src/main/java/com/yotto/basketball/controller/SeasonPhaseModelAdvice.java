package com.yotto.basketball.controller;

import com.yotto.basketball.service.SeasonPhase;
import com.yotto.basketball.service.SeasonPhaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the current {@link SeasonPhase} to every view (nav bracket link, phase-aware panels).
 * Never fails a page over it: bad data degrades to a null attribute, which templates must guard.
 */
@ControllerAdvice(annotations = Controller.class)
public class SeasonPhaseModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(SeasonPhaseModelAdvice.class);

    private final SeasonPhaseService seasonPhaseService;

    public SeasonPhaseModelAdvice(SeasonPhaseService seasonPhaseService) {
        this.seasonPhaseService = seasonPhaseService;
    }

    @ModelAttribute("seasonPhase")
    public SeasonPhase seasonPhase() {
        try {
            return seasonPhaseService.current();
        } catch (RuntimeException e) {
            log.warn("Season phase resolution failed; rendering without it", e);
            return null;
        }
    }
}
