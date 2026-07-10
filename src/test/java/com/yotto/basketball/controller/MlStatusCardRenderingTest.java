package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Paths;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the admin ML models card with the ONNX fixture bundle loaded (legacy flat
 * layout → slug "baseline"), exercising the registry reconcile path and the models
 * table including per-bundle metrics. Complements
 * {@link com.yotto.basketball.service.MlPredictionServiceTest}.
 */
@AutoConfigureMockMvc
class MlStatusCardRenderingTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) {
        registry.add("prediction.ml.enabled", () -> "true");
        registry.add("prediction.ml.model-dir", () -> {
            try {
                return Paths.get(MlStatusCardRenderingTest.class.getClassLoader()
                        .getResource("ml-models/features.json").toURI()).getParent().toString();
            } catch (Exception e) {
                throw new IllegalStateException("ml-models fixtures missing from test classpath", e);
            }
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void mlStatusCard_rendersModelTableWithMetricsAfterReload() throws Exception {
        // The startup reconcile ran before the per-test truncate; re-reconcile first
        mockMvc.perform(post("/admin/ml/reload").with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/admin/ml/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("model loaded")))
                .andExpect(content().string(containsString("baseline")))
                .andExpect(content().string(containsString("Active")))
                .andExpect(content().string(containsString("test-fixture-1")))
                .andExpect(content().string(containsString("10.50")))    // spread RMSE
                .andExpect(content().string(containsString("0.1875"))); // Brier
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void promoteRetireLifecycle_updatesCard() throws Exception {
        mockMvc.perform(post("/admin/ml/reload").with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/ml/models/baseline/retire").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/ml/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Retired")));

        mockMvc.perform(post("/admin/ml/models/baseline/reinstate").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/ml/models/baseline/promote").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/ml/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Active")))
                .andExpect(content().string(containsString("★")));
    }
}
