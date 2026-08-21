package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.service.SeasonPhase;
import com.yotto.basketball.service.SeasonPhaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Nav bracket-link visibility is phase-driven, and the admin force-phase override round-trips. */
@AutoConfigureMockMvc
class SeasonPhaseWebTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonPhaseService seasonPhaseService;

    @AfterEach
    void clearOverride() {
        seasonPhaseService.clearOverride();
    }

    @Test
    void bracketLink_alwaysVisibleRegardlessOfPhase() throws Exception {
        // Since the menu reorganization (spec OQ-5) the bracket entry lives in the
        // Games dropdown year-round — historical brackets are evergreen content.
        seasonPhaseService.setOverride(SeasonPhase.Phase.IN_SEASON, null);
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/bracket\"")));

        seasonPhaseService.setOverride(SeasonPhase.Phase.POSTSEASON, null);
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/bracket\"")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPhaseOverride_setsAndClears() throws Exception {
        mockMvc.perform(post("/admin/phase").with(csrf())
                        .param("phase", "EPILOGUE")
                        .param("date", "2026-04-10"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attributeExists("success"));
        assertThat(seasonPhaseService.getForcedPhase()).isEqualTo(SeasonPhase.Phase.EPILOGUE);
        assertThat(seasonPhaseService.getForcedDate()).isEqualTo(LocalDate.of(2026, 4, 10));

        mockMvc.perform(post("/admin/phase").with(csrf()))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attributeExists("success"));
        assertThat(seasonPhaseService.isOverridden()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPhaseOverride_rejectsGarbage() throws Exception {
        mockMvc.perform(post("/admin/phase").with(csrf())
                        .param("phase", "MARCH_MADNESS"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attributeExists("error"));
        assertThat(seasonPhaseService.isOverridden()).isFalse();
    }

    @Test
    void adminPhaseOverride_requiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/phase").with(csrf()).param("phase", "IN_SEASON"))
                .andExpect(status().isUnauthorized()); // anonymous → 401 (basic-auth entry point)
        assertThat(seasonPhaseService.isOverridden()).isFalse();
    }
}
