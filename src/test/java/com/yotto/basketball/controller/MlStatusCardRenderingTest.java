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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the admin ML status card with the ONNX fixture bundle loaded, so the
 * metrics rows of ml-status.html (only evaluated when metrics are present) are
 * exercised. Complements {@link com.yotto.basketball.service.MlPredictionServiceTest},
 * which covers the service in isolation.
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
    void mlStatusCard_rendersMetricsFromFeaturesJson() throws Exception {
        mockMvc.perform(get("/admin/ml/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enabled")))
                .andExpect(content().string(containsString("test-fixture-1")))
                .andExpect(content().string(containsString("10.50 RMSE")))   // spread
                .andExpect(content().string(containsString("8.25 MAE")))
                .andExpect(content().string(containsString("17.75 RMSE")))   // total
                .andExpect(content().string(containsString("0.1875 Brier")))
                .andExpect(content().string(containsString("72.5% winners")));
    }
}
