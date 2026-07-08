package com.yotto.basketball.controller;

import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.security.AppUserDetails;
import com.yotto.basketball.security.SessionInvalidationService;
import com.yotto.basketball.security.TokenService;
import com.yotto.basketball.service.PreferenceKeys;
import com.yotto.basketball.service.UserAccountService;
import com.yotto.basketball.service.UserPreferenceService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Signed-in account management: profile view, password change, email change,
 * preferences, log-out-everywhere, and self-service deletion.
 */
@Controller
@RequestMapping("/account")
public class AccountController {

    private final UserAccountService accountService;
    private final UserPreferenceService preferenceService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final SessionInvalidationService sessionInvalidationService;

    public AccountController(UserAccountService accountService,
                             UserPreferenceService preferenceService,
                             UserRepository userRepository,
                             TokenService tokenService,
                             SessionInvalidationService sessionInvalidationService) {
        this.accountService = accountService;
        this.preferenceService = preferenceService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.sessionInvalidationService = sessionInvalidationService;
    }

    @GetMapping
    public String accountPage(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        User user = currentUser(principal);
        model.addAttribute("user", user);
        model.addAttribute("memberSince", formatInstant(user.getCreatedAt(), "MMMM d, yyyy"));
        model.addAttribute("lastLogin", formatInstant(user.getLastLoginAt(), "MMM d, yyyy HH:mm"));
        model.addAttribute("dailyUpdateEmail",
                preferenceService.getBoolean(user.getId(), PreferenceKeys.DAILY_UPDATE_EMAIL, false));
        return "account/account";
    }

    private String formatInstant(Instant instant, String pattern) {
        if (instant == null) return "—";
        return DateTimeFormatter.ofPattern(pattern)
                .format(instant.atZone(ZoneId.systemDefault()));
    }

    // ── Password ─────────────────────────────────────────────────────────────

    @GetMapping("/password")
    public String passwordPage(Authentication authentication, Model model) {
        // By name, not by AppUserDetails: the must-change interceptor redirects
        // here for every authentication flavor, so stay principal-type-agnostic
        boolean mustChange = userRepository.findByUsernameIgnoreCase(authentication.getName())
                .map(User::isPasswordMustChange)
                .orElse(false);
        model.addAttribute("mustChange", mustChange);
        return "account/password";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal AppUserDetails principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New passwords do not match");
            return "redirect:/account/password";
        }
        try {
            accountService.changePassword(principal.getId(), currentPassword, newPassword,
                    request.getSession().getId());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/account/password";
        }
        redirectAttributes.addFlashAttribute("success", "Password changed");
        return "redirect:/account";
    }

    // ── Email change ─────────────────────────────────────────────────────────

    @PostMapping("/email")
    public String requestEmailChange(@AuthenticationPrincipal AppUserDetails principal,
                                     @RequestParam String newEmail,
                                     @RequestParam String currentPassword,
                                     RedirectAttributes redirectAttributes) {
        try {
            accountService.requestEmailChange(principal.getId(), newEmail, currentPassword);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/account";
        }
        redirectAttributes.addFlashAttribute("success",
                "Check the new address for a confirmation link. Your current email keeps working until then.");
        return "redirect:/account";
    }

    @GetMapping("/confirm-email")
    public String confirmEmailPage(@AuthenticationPrincipal AppUserDetails principal,
                                   @RequestParam(required = false) String token,
                                   Model model) {
        boolean usable = tokenService.peek(token, TokenType.EMAIL_CHANGE)
                .filter(t -> t.getUser().getId().equals(principal.getId()))
                .isPresent();
        if (!usable) {
            return "auth/link-expired";
        }
        model.addAttribute("token", token);
        return "account/confirm-email";
    }

    @PostMapping("/confirm-email")
    public String confirmEmail(@AuthenticationPrincipal AppUserDetails principal,
                               @RequestParam String token,
                               RedirectAttributes redirectAttributes) {
        if (!accountService.confirmEmailChange(token, principal.getId())) {
            return "auth/link-expired";
        }
        redirectAttributes.addFlashAttribute("success", "Email address updated");
        return "redirect:/account";
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    @PostMapping("/preferences")
    public String updatePreferences(@AuthenticationPrincipal AppUserDetails principal,
                                    @RequestParam(defaultValue = "false") boolean dailyUpdateEmail,
                                    RedirectAttributes redirectAttributes) {
        preferenceService.set(principal.getId(), PreferenceKeys.DAILY_UPDATE_EMAIL,
                Boolean.toString(dailyUpdateEmail));
        redirectAttributes.addFlashAttribute("success", "Preferences saved");
        return "redirect:/account";
    }

    // ── Sessions & deletion ──────────────────────────────────────────────────

    @PostMapping("/logout-everywhere")
    public String logoutEverywhere(@AuthenticationPrincipal AppUserDetails principal,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        sessionInvalidationService.invalidateAll(principal.getUsername());
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return "redirect:/login?logout";
    }

    @PostMapping("/delete")
    public String deleteAccount(@AuthenticationPrincipal AppUserDetails principal,
                                @RequestParam String password,
                                HttpServletRequest request,
                                HttpServletResponse response,
                                RedirectAttributes redirectAttributes) {
        try {
            accountService.deleteSelf(principal.getId(), password);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/account";
        }
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return "redirect:/";
    }

    private User currentUser(AppUserDetails principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
    }
}
