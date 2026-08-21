package com.yotto.basketball.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Permanent redirects from pre-reorganization URLs (docs/MENU_AND_GUI_SPEC.md
 * §2.3) so old bookmarks and inbound links keep working. Query strings are
 * preserved (the performance page's year/window/segment selectors, etc.).
 */
@Controller
public class LegacyRedirectController {

    private final com.yotto.basketball.service.PublicModelService publicModelService;

    public LegacyRedirectController(com.yotto.basketball.service.PublicModelService publicModelService) {
        this.publicModelService = publicModelService;
    }

    @GetMapping("/predictions/performance")
    public RedirectView performance(HttpServletRequest request) {
        return movedPermanently("/models/compare", request);
    }

    @GetMapping("/predictions/matchup")
    public RedirectView matchup(HttpServletRequest request) {
        return movedPermanently("/models/matchup", request);
    }

    /** The old multi-model upcoming-cards page → the default model's schedule (spec OQ-3). */
    @GetMapping("/predictions")
    public RedirectView predictions(HttpServletRequest request) {
        String target = publicModelService.defaultSlug()
                .map(slug -> "/models/" + slug + "/schedule")
                .orElse("/models/compare");
        return movedPermanently(target, request);
    }

    private RedirectView movedPermanently(String target, HttpServletRequest request) {
        String query = request.getQueryString();
        RedirectView view = new RedirectView(query == null ? target : target + "?" + query);
        view.setStatusCode(org.springframework.http.HttpStatus.MOVED_PERMANENTLY);
        return view;
    }
}
