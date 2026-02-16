package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityConfigTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminDashboard_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin"))
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
}
