package com.yotto.basketball.controller;

import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthController(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    @GetMapping("/change-password")
    public String changePasswordPage(Model model, Authentication authentication) {
        AdminUser user = adminUserRepository.findByUsername(authentication.getName()).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getPasswordMustChange())) {
            model.addAttribute("mustChange", true);
        }
        return "admin/change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New passwords do not match");
            return "redirect:/admin/change-password";
        }

        if (newPassword.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "Password must be at least 8 characters");
            return "redirect:/admin/change-password";
        }

        AdminUser user = adminUserRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found");
            return "redirect:/admin/change-password";
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect");
            return "redirect:/admin/change-password";
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordMustChange(false);
        adminUserRepository.save(user);

        redirectAttributes.addFlashAttribute("success", "Password changed successfully");
        return "redirect:/admin";
    }
}
