package com.yotto.basketball.controller;

import com.yotto.basketball.security.AppUserDetails;
import com.yotto.basketball.service.FavoriteTeamService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Follow/unfollow endpoints. The team-page buttons are HTMX swaps returning the button fragment;
 * the account-page remove is a plain redirect. Both paths require authentication (SecurityConfig).
 */
@Controller
public class FavoriteTeamController {

    private final FavoriteTeamService favoriteTeamService;

    public FavoriteTeamController(FavoriteTeamService favoriteTeamService) {
        this.favoriteTeamService = favoriteTeamService;
    }

    @PostMapping("/teams/{id}/follow")
    public String follow(@AuthenticationPrincipal AppUserDetails principal,
                         @PathVariable Long id, Model model) {
        String error = null;
        try {
            favoriteTeamService.follow(principal.getId(), id);
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        return buttonFragment(principal, id, error, model);
    }

    @PostMapping("/teams/{id}/unfollow")
    public String unfollow(@AuthenticationPrincipal AppUserDetails principal,
                           @PathVariable Long id, Model model) {
        favoriteTeamService.unfollow(principal.getId(), id);
        return buttonFragment(principal, id, null, model);
    }

    @PostMapping("/account/favorites/remove")
    public String removeFromAccount(@AuthenticationPrincipal AppUserDetails principal,
                                    @RequestParam Long teamId,
                                    RedirectAttributes redirectAttributes) {
        favoriteTeamService.unfollow(principal.getId(), teamId);
        redirectAttributes.addFlashAttribute("success", "Team unfollowed");
        return "redirect:/account";
    }

    private String buttonFragment(AppUserDetails principal, Long teamId, String error, Model model) {
        model.addAttribute("teamId", teamId);
        model.addAttribute("isFollowing", favoriteTeamService.isFavorite(principal.getId(), teamId));
        model.addAttribute("followError", error);
        return "fragments/follow-button :: button";
    }
}
