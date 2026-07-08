package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
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
class AdminUserControllerTest extends BaseIntegrationTest {

    static final String ADMIN = "au-admin";
    static final String TARGET = "au-target";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private MailService mailService;

    private Long targetId;
    private Long adminId;

    @BeforeEach
    void fixtures() {
        adminId = save(ADMIN, "au-admin@example.com", Role.ADMIN).getId();
        targetId = save(TARGET, "au-target@example.com", Role.USER).getId();
    }

    private User save(String username, String email, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode("admin-Pass-1!"));
        u.setRole(role);
        u.setEnabled(true);
        return userRepository.save(u);
    }

    private User target() {
        return userRepository.findById(targetId).orElseThrow();
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void list_rendersUsers() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void list_searchFiltersByUsernameOrEmail() throws Exception {
        mockMvc.perform(get("/admin/users").param("q", "au-target"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", "au-target"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void list_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    // ── Lock / unlock ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void lockAndUnlock() throws Exception {
        mockMvc.perform(post("/admin/users/" + targetId + "/lock").with(csrf()))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("success"));
        assertThat(target().isLocked()).isTrue();

        mockMvc.perform(post("/admin/users/" + targetId + "/unlock").with(csrf()))
                .andExpect(flash().attributeExists("success"));
        assertThat(target().isLocked()).isFalse();
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void unlock_alsoClearsAutomaticLockout() throws Exception {
        User u = target();
        u.setFailedLoginAttempts(10);
        u.setLockoutExpiresAt(java.time.Instant.now().plusSeconds(600));
        userRepository.save(u);

        mockMvc.perform(post("/admin/users/" + targetId + "/unlock").with(csrf()))
                .andExpect(flash().attributeExists("success"));

        User after = target();
        assertThat(after.getFailedLoginAttempts()).isZero();
        assertThat(after.getLockoutExpiresAt()).isNull();
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void lockSelf_isBlocked() throws Exception {
        mockMvc.perform(post("/admin/users/" + adminId + "/lock").with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.findById(adminId).orElseThrow().isLocked()).isFalse();
    }

    // ── Role changes ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void promoteAndDemote() throws Exception {
        mockMvc.perform(post("/admin/users/" + targetId + "/role").with(csrf())
                        .param("role", "ADMIN"))
                .andExpect(flash().attributeExists("success"));
        assertThat(target().getRole()).isEqualTo(Role.ADMIN);

        mockMvc.perform(post("/admin/users/" + targetId + "/role").with(csrf())
                        .param("role", "USER"))
                .andExpect(flash().attributeExists("success"));
        assertThat(target().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void demoteSelf_isBlocked() throws Exception {
        mockMvc.perform(post("/admin/users/" + adminId + "/role").with(csrf())
                        .param("role", "USER"))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.findById(adminId).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @WithMockUser(username = "other-admin", roles = "ADMIN")
    void demoteLastAdmin_isBlocked() throws Exception {
        // 'other-admin' has no users row; au-admin is the only enabled admin row
        mockMvc.perform(post("/admin/users/" + adminId + "/role").with(csrf())
                        .param("role", "USER"))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.findById(adminId).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    // ── Verification / reset / delete ────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void resendVerification_onVerifiedAccount_isRejected() throws Exception {
        mockMvc.perform(post("/admin/users/" + targetId + "/resend-verification").with(csrf()))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void resendVerification_onUnverifiedAccount_sendsMail() throws Exception {
        User u = target();
        u.setEnabled(false);
        userRepository.save(u);

        mockMvc.perform(post("/admin/users/" + targetId + "/resend-verification").with(csrf()))
                .andExpect(flash().attributeExists("success"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void triggerPasswordReset_flashesSuccess() throws Exception {
        mockMvc.perform(post("/admin/users/" + targetId + "/reset-password").with(csrf()))
                .andExpect(flash().attributeExists("success"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void deleteUser_removesRow() throws Exception {
        mockMvc.perform(post("/admin/users/" + targetId + "/delete").with(csrf()))
                .andExpect(flash().attributeExists("success"));
        assertThat(userRepository.existsById(targetId)).isFalse();
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void deleteSelf_isBlocked() throws Exception {
        mockMvc.perform(post("/admin/users/" + adminId + "/delete").with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.existsById(adminId)).isTrue();
    }
}
