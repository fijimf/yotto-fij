package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PasswordChangeInterceptorTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AdminUserRepository adminUserRepository;

    static final String TEST_USERNAME = "pc-test-user";

    @BeforeEach
    void setUp() {
        adminUserRepository.findByUsername(TEST_USERNAME).ifPresent(adminUserRepository::delete);
    }

    private void saveUser(boolean mustChange) {
        AdminUser u = new AdminUser();
        u.setUsername(TEST_USERNAME);
        u.setPasswordHash("$2a$10$dummyDummyDummyDummyDummy.dummyDummyDummyDummyDummyDummyDum");
        u.setPasswordMustChange(mustChange);
        adminUserRepository.save(u);
    }

    // ── The behavior that matters ────────────────────────────────────────────

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void protectedAdminUrl_redirectsToChangePassword_whenPasswordMustChange() throws Exception {
        saveUser(true);

        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/change-password"));
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void protectedAdminUrl_passesThrough_whenPasswordMustChangeFalse() throws Exception {
        saveUser(false);

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void changePasswordPage_isNotIntercepted_evenWhenMustChange() throws Exception {
        saveUser(true);

        mockMvc.perform(get("/admin/change-password"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void loginPage_isNotIntercepted_evenWhenMustChange() throws Exception {
        saveUser(true);

        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void unknownUserInDb_passesThrough() throws Exception {
        // No AdminUser row → interceptor finds nothing → returns true (no redirect)
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anonymousUser_isNotIntercepted() throws Exception {
        // Anonymous user hitting /admin gets caught by Spring Security → 302 to /admin/login
        // (when the request looks like a browser), NOT by the password-change interceptor
        // (whose redirect would be to /admin/change-password).
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/admin/login"));
    }
}
