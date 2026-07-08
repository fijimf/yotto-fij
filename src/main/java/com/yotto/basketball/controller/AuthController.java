package com.yotto.basketball.controller;

import com.yotto.basketball.controller.dto.RegisterForm;
import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.security.TokenService;
import com.yotto.basketball.service.UserAccountService;
import com.yotto.basketball.service.UserAccountService.RegistrationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Public authentication pages: login, registration, email verification,
 * forgot/reset password. Token-consuming actions are POSTs (GET renders a
 * confirm form) so mail-scanner prefetches can't burn single-use links.
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String REGISTER_MESSAGE =
            "Almost done — check your email for a confirmation link.";

    private final UserAccountService accountService;
    private final TokenService tokenService;

    public AuthController(UserAccountService accountService, TokenService tokenService) {
        this.accountService = accountService;
        this.tokenService = tokenService;
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    /** Legacy admin bookmark. */
    @GetMapping("/admin/login")
    public String legacyAdminLogin() {
        return "redirect:/login";
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @GetMapping("/register")
    public String registerPage(@ModelAttribute("form") RegisterForm form) {
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult bindingResult,
                           HttpServletRequest request,
                           Model model) {
        if (form.getWebsite() != null && !form.getWebsite().isBlank()) {
            log.warn("Registration honeypot tripped from {}", request.getRemoteAddr());
            model.addAttribute("message", REGISTER_MESSAGE);
            return checkEmail(model); // fake success for bots
        }
        if (!bindingResult.hasFieldErrors("confirmPassword")
                && form.getPassword() != null
                && !form.getPassword().equals(form.getConfirmPassword())) {
            bindingResult.rejectValue("confirmPassword", "mismatch", "Passwords do not match");
        }
        if (bindingResult.hasErrors()) {
            return "auth/register";
        }

        try {
            RegistrationResult result = accountService.register(
                    form.getUsername(), form.getEmail(), form.getPassword(),
                    request.getRemoteAddr());
            if (result == RegistrationResult.USERNAME_TAKEN) {
                bindingResult.rejectValue("username", "taken", "That username is taken");
                return "auth/register";
            }
            // SUCCESS and EMAIL_TAKEN are deliberately indistinguishable
            model.addAttribute("message", REGISTER_MESSAGE);
            return checkEmail(model);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/register";
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent registration
            model.addAttribute("error", "That username or email is unavailable");
            return "auth/register";
        }
    }

    // ── Email verification ───────────────────────────────────────────────────

    @GetMapping("/verify")
    public String verifyPage(@RequestParam(required = false) String token, Model model) {
        if (tokenService.peek(token, TokenType.EMAIL_VERIFICATION).isEmpty()) {
            return "auth/link-expired";
        }
        model.addAttribute("token", token);
        return "auth/verify";
    }

    @PostMapping("/verify")
    public String verify(@RequestParam String token) {
        if (accountService.verifyEmail(token).isEmpty()) {
            return "auth/link-expired";
        }
        return "redirect:/login?verified";
    }

    @PostMapping("/verify/resend")
    public String resendVerification(@RequestParam String email,
                                     HttpServletRequest request,
                                     Model model) {
        try {
            accountService.resendVerification(email, request.getRemoteAddr());
        } catch (IllegalArgumentException e) {
            // Blank/invalid email — same response, no enumeration signal
        }
        return checkEmail(model);
    }

    // ── Forgot / reset password ──────────────────────────────────────────────

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email,
                                 HttpServletRequest request,
                                 Model model) {
        try {
            accountService.forgotPassword(email, request.getRemoteAddr());
        } catch (IllegalArgumentException e) {
            // Blank/invalid email — same response, no enumeration signal
        }
        model.addAttribute("message",
                "If that address has an account, we've emailed a password reset link.");
        return checkEmail(model);
    }

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam(required = false) String token, Model model) {
        if (tokenService.peek(token, TokenType.PASSWORD_RESET).isEmpty()) {
            return "auth/link-expired";
        }
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String password,
                                @RequestParam String confirmPassword,
                                Model model) {
        if (!password.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Passwords do not match");
            return "auth/reset-password";
        }
        try {
            if (!accountService.resetPassword(token, password)) {
                return "auth/link-expired";
            }
        } catch (IllegalArgumentException e) {
            model.addAttribute("token", token);
            model.addAttribute("error", e.getMessage());
            return "auth/reset-password";
        }
        return "redirect:/login?reset";
    }

    private String checkEmail(Model model) {
        if (!model.containsAttribute("message")) {
            model.addAttribute("message",
                    "If that address needs anything from us, we've sent an email with the next step.");
        }
        return "auth/check-email";
    }
}
