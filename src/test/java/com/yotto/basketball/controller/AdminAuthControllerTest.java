package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class AdminAuthControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired PasswordEncoder passwordEncoder;

    static final String USERNAME = "auth-test-user";
    static final String CURRENT_PASSWORD = "Current123!";

    @BeforeEach
    void setUp() {
        adminUserRepository.findByUsername(USERNAME).ifPresent(adminUserRepository::delete);
        AdminUser u = new AdminUser();
        u.setUsername(USERNAME);
        u.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));
        u.setPasswordMustChange(true);
        adminUserRepository.save(u);
    }

    // ── GET /admin/login ──────────────────────────────────────────────────────

    @Test
    void loginPage_isPublic_andReturnsLoginView() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/login"));
    }

    // ── GET /admin/change-password ────────────────────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePasswordPage_setsMustChangeFlag_whenUserMustChange() throws Exception {
        mockMvc.perform(get("/admin/change-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/change-password"))
                .andExpect(model().attribute("mustChange", true));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePasswordPage_omitsMustChangeFlag_whenUserDoesNotMustChange() throws Exception {
        AdminUser u = adminUserRepository.findByUsername(USERNAME).orElseThrow();
        u.setPasswordMustChange(false);
        adminUserRepository.save(u);

        mockMvc.perform(get("/admin/change-password"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("mustChange"));
    }

    // ── POST /admin/change-password — failure modes ───────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePassword_passwordsDoNotMatch_redirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", "Different1!")
                        .param("confirmPassword", "MismatchA!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/change-password"))
                .andExpect(flash().attribute("error", "New passwords do not match"));

        // Password unchanged
        AdminUser reloaded = adminUserRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(reloaded.getPasswordMustChange()).isTrue();
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePassword_shorterThanEight_redirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", "Short1!")
                        .param("confirmPassword", "Short1!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/change-password"))
                .andExpect(flash().attribute("error", "Password must be at least 8 characters"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePassword_wrongCurrent_redirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/change-password").with(csrf())
                        .param("currentPassword", "WrongPass99!")
                        .param("newPassword", "ValidNewPass123!")
                        .param("confirmPassword", "ValidNewPass123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/change-password"))
                .andExpect(flash().attribute("error", "Current password is incorrect"));

        AdminUser reloaded = adminUserRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, reloaded.getPasswordHash())).isTrue();
    }

    @Test
    @WithMockUser(username = "ghost-user", roles = "ADMIN")
    void changePassword_userNotInDb_redirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/change-password").with(csrf())
                        .param("currentPassword", "Whatever1!")
                        .param("newPassword", "AnyNewPass1!")
                        .param("confirmPassword", "AnyNewPass1!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/change-password"))
                .andExpect(flash().attribute("error", "User not found"));
    }

    // ── POST /admin/change-password — happy path ──────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePassword_success_updatesHashClearsMustChangeAndRedirectsToDashboard() throws Exception {
        String newPassword = "BrandNewPass!42";

        mockMvc.perform(post("/admin/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", newPassword)
                        .param("confirmPassword", newPassword))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("success", "Password changed successfully"));

        AdminUser reloaded = adminUserRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(passwordEncoder.matches(newPassword, reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, reloaded.getPasswordHash())).isFalse();
        assertThat(reloaded.getPasswordMustChange()).isFalse();
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    void changePassword_withoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(post("/admin/change-password") // no .with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", "Whatever1!")
                        .param("confirmPassword", "Whatever1!"))
                .andExpect(status().isForbidden());
    }
}
