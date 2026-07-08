package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.UserAccountService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin user management: list/search, lock, role, verification, reset, delete. */
@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private static final int PAGE_SIZE = 25;

    private final UserRepository userRepository;
    private final UserAccountService accountService;

    public AdminUserController(UserRepository userRepository, UserAccountService accountService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), PAGE_SIZE,
                Sort.by("username").ascending());
        Page<User> users = q.isBlank()
                ? userRepository.findAll(pageRequest)
                : userRepository.findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                        q, q, pageRequest);
        model.addAttribute("users", users);
        model.addAttribute("q", q);
        return "admin/users";
    }

    @PostMapping("/{id}/lock")
    public String lock(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        return act(ra, () -> accountService.setLocked(id, true, auth.getName()), "User locked");
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        return act(ra, () -> accountService.setLocked(id, false, auth.getName()), "User unlocked");
    }

    @PostMapping("/{id}/role")
    public String setRole(@PathVariable Long id, @RequestParam Role role,
                          Authentication auth, RedirectAttributes ra) {
        return act(ra, () -> accountService.setRole(id, role, auth.getName()),
                "Role set to " + role);
    }

    @PostMapping("/{id}/resend-verification")
    public String resendVerification(@PathVariable Long id, RedirectAttributes ra) {
        return act(ra, () -> accountService.adminResendVerification(id), "Verification email sent");
    }

    @PostMapping("/{id}/reset-password")
    public String triggerReset(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        return act(ra, () -> accountService.adminTriggerPasswordReset(id, auth.getName()),
                "Password reset email sent");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        return act(ra, () -> accountService.adminDelete(id, auth.getName()), "User deleted");
    }

    private String act(RedirectAttributes ra, Runnable action, String successMessage) {
        try {
            action.run();
            ra.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
