package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityConfigTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CookieSameSiteSupplier cookieSameSiteSupplier;

    static final String ADMIN_USERNAME = "sec-admin";
    static final String USER_USERNAME = "sec-user";
    static final String PASSWORD = "Sec-Pass-1234!";

    @BeforeEach
    void setUp() {
        save(ADMIN_USERNAME, "sec-admin@example.com", Role.ADMIN);
        save(USER_USERNAME, "sec-user@example.com", Role.USER);
    }

    private void save(String username, String email, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setRole(role);
        u.setEnabled(true);
        userRepository.save(u);
    }

    // ── Authentication boundary ──────────────────────────────────────────────

    @Test
    void adminDashboard_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminDashboard_forbiddenForUserRole() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountPage_allowsAdminViaRoleHierarchy() throws Exception {
        // hasRole('USER') on /account/** must pass for ADMIN (ROLE_ADMIN > ROLE_USER).
        // Basic auth gives a real AppUserDetails principal, which the page needs.
        mockMvc.perform(get("/account/password").with(httpBasic(ADMIN_USERNAME, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void accountPage_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/account").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginPage_isPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void legacyAdminLogin_redirectsToSharedLogin() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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

    @Test
    void httpBasic_stillWorksForAdminScripts() throws Exception {
        // retrain.sh authenticates with basic auth against /admin/** endpoints
        mockMvc.perform(get("/admin").with(httpBasic(ADMIN_USERNAME, PASSWORD)))
                .andExpect(status().isOk());
    }

    // ── CSRF behavior ────────────────────────────────────────────────────────

    @Test
    void apiGet_staysPublic() throws Exception {
        // Read access to the REST API remains anonymous.
        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk());
    }

    @Test
    void apiPost_anonymous_isRejected() throws Exception {
        // Mutating API writes now require ADMIN + CSRF; an anonymous POST must not
        // reach the controller (previously this returned 400 after hitting it).
        mockMvc.perform(post("/api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiDelete_anonymous_isRejected() throws Exception {
        mockMvc.perform(delete("/api/teams/999999"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void apiDelete_asNonAdminUser_isForbidden() throws Exception {
        // A logged-in non-admin still cannot write, even with a CSRF token.
        mockMvc.perform(delete("/api/teams/999999").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void apiDelete_asAdminWithoutCsrf_isForbidden() throws Exception {
        // CSRF is enforced on /api now (no longer ignored): a token is required
        // even for an admin.
        mockMvc.perform(delete("/api/teams/999999"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void apiDelete_asAdminWithCsrf_reachesController() throws Exception {
        // ADMIN + CSRF passes security and hits the controller — 404 for a missing
        // id (NOT 403), proving admins can still perform writes.
        mockMvc.perform(delete("/api/teams/999999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPost_withoutCsrf_isRejected() throws Exception {
        mockMvc.perform(post("/admin/seasons")
                        .param("year", "2026"))
                .andExpect(status().isForbidden());
    }

    // ── Security response headers ─────────────────────────────────────────────

    @Test
    void responses_carryContentSecurityPolicyAndReferrerPolicy() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("object-src 'none'")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                // Spring Security defaults must still be present alongside the CSP
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    // ── Remember-me cookie SameSite ───────────────────────────────────────────

    @Test
    void rememberMeCookie_isTaggedSameSiteLax() {
        // The remember-me cookie is written by the container, so SameSite is applied
        // via CookieSameSiteSupplier rather than the session-cookie properties.
        assertThat(cookieSameSiteSupplier.getSameSite(new jakarta.servlet.http.Cookie("remember-me", "x")))
                .isEqualTo(org.springframework.boot.web.server.Cookie.SameSite.LAX);
        // Other cookies are left untouched by this supplier.
        assertThat(cookieSameSiteSupplier.getSameSite(new jakarta.servlet.http.Cookie("JSESSIONID", "x")))
                .isNull();
    }

    // ── Form login flow ──────────────────────────────────────────────────────

    @Test
    void formLogin_withBadCredentials_redirectsWithGenericError() throws Exception {
        mockMvc.perform(formLogin("/login").user(USER_USERNAME).password("wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=bad"));
    }

    @Test
    void formLogin_withUnknownUser_getsIdenticalGenericError() throws Exception {
        // Anti-enumeration: unknown user and bad password produce the same redirect
        mockMvc.perform(formLogin("/login").user("nobody-here").password("anything!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=bad"));
    }

    @Test
    void formLogin_withValidCredentials_succeeds() throws Exception {
        mockMvc.perform(formLogin("/login").user(USER_USERNAME).password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void formLogin_withEmailInsteadOfUsername_succeeds() throws Exception {
        mockMvc.perform(formLogin("/login").user("sec-user@example.com").password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void formLogin_unverifiedAccount_getsUnverifiedError() throws Exception {
        User u = new User();
        u.setUsername("sec-unverified");
        u.setEmail("sec-unverified@example.com");
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setEnabled(false);
        userRepository.save(u);

        mockMvc.perform(formLogin("/login").user("sec-unverified").password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=unverified"));
    }

    @Test
    void formLogin_lockedAccount_getsLockedError() throws Exception {
        User u = userRepository.findByUsernameIgnoreCase(USER_USERNAME).orElseThrow();
        u.setLocked(true);
        userRepository.save(u);

        mockMvc.perform(formLogin("/login").user(USER_USERNAME).password(PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=locked"));
    }

    @Test
    void rememberMe_setsPersistentCookie() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", USER_USERNAME)
                        .param("password", PASSWORD)
                        .param("remember-me", "on")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("remember-me"));
    }

    @Test
    void logout_redirectsHome() throws Exception {
        // Browser-shaped request: Spring Security 6 responds 204 to non-HTML clients
        mockMvc.perform(post("/logout")
                        .accept(MediaType.TEXT_HTML)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
