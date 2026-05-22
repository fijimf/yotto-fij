package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityConfigTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminUserRepository adminUserRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    static final String LOGIN_USERNAME = "sec-test-user";
    static final String LOGIN_PASSWORD = "Sec-Pass-1234!";

    @BeforeEach
    void setUp() {
        adminUserRepository.findByUsername(LOGIN_USERNAME).ifPresent(adminUserRepository::delete);
        AdminUser u = new AdminUser();
        u.setUsername(LOGIN_USERNAME);
        u.setPasswordHash(passwordEncoder.encode(LOGIN_PASSWORD));
        u.setPasswordMustChange(false);
        adminUserRepository.save(u);
    }

    // ── Authentication boundary ──────────────────────────────────────────────

    @Test
    void adminDashboard_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    void adminLogin_isPublic() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk());
    }

    @Test
    void publicPages_doNotRequireAuth() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void apiEndpoints_doNotRequireAuth() throws Exception {
        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk());
    }

    // ── CSRF behavior ────────────────────────────────────────────────────────

    @Test
    void apiPost_withoutCsrf_succeedsBecauseApiIsCsrfIgnored() throws Exception {
        // /api/** is in csrf.ignoringRequestMatchers — a POST without a CSRF token must
        // be allowed through (otherwise script clients are broken).
        // We deliberately send an empty body so the controller returns 400 (validation),
        // not 403 (CSRF). The relevant assertion is "NOT 403".
        mockMvc.perform(post("/api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiDelete_withoutCsrf_succeedsBecauseApiIsCsrfIgnored() throws Exception {
        // Non-existent id → 404, not 403. Again, the key assertion is "NOT 403".
        mockMvc.perform(delete("/api/teams/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = LOGIN_USERNAME, roles = "ADMIN")
    void adminPost_withoutCsrf_isRejected() throws Exception {
        // CSRF is required on admin paths. Without a token, the request must be 403.
        mockMvc.perform(post("/admin/seasons")
                        .param("year", "2026"))
                .andExpect(status().isForbidden());
    }

    // ── Form login flow ──────────────────────────────────────────────────────

    @Test
    void formLogin_withBadCredentials_redirectsToLoginWithError() throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(LOGIN_USERNAME).password("wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?error=true"));
    }

    @Test
    void formLogin_withValidCredentials_redirectsToAdminDashboard() throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(LOGIN_USERNAME).password(LOGIN_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void formLogin_withUnknownUser_redirectsToLoginWithError() throws Exception {
        mockMvc.perform(formLogin("/admin/login").user("nobody").password("anything"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?error=true"));
    }
}
