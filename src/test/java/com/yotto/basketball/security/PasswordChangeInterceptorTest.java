package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PasswordChangeInterceptorTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    static final String TEST_USERNAME = "pc-test-user";

    private void saveUser(boolean mustChange) {
        User u = new User();
        u.setUsername(TEST_USERNAME);
        u.setPasswordHash("{bcrypt}$2a$10$dummyDummyDummyDummyDummy.dummyDummyDummyDummyDummyDummyDum");
        u.setRole(Role.ADMIN);
        u.setEnabled(true);
        u.setPasswordMustChange(mustChange);
        userRepository.save(u);
    }

    // ── The behavior that matters ────────────────────────────────────────────

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void protectedAdminUrl_redirectsToChangePassword_whenPasswordMustChange() throws Exception {
        saveUser(true);

        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/password"));
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    void publicPage_redirectsToChangePassword_whenPasswordMustChange() throws Exception {
        // Interceptor now covers the whole site, not just /admin/**
        saveUser(true);

        mockMvc.perform(get("/teams"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/password"));
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

        mockMvc.perform(get("/account/password"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void loginPage_isNotIntercepted_evenWhenMustChange() throws Exception {
        saveUser(true);

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "ADMIN")
    void unknownUserInDb_passesThrough() throws Exception {
        // No users row → interceptor finds nothing → returns true (no redirect)
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anonymousUser_isNotIntercepted() throws Exception {
        // Anonymous user hitting /admin gets caught by Spring Security → 302 to /login
        // (when the request looks like a browser), NOT by the password-change interceptor
        // (whose redirect would be to /account/password).
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }
}
