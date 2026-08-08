package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BracketTextControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void noBracketData_returns404PlainText() throws Exception {
        mockMvc.perform(get("/bracket.txt"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("see you in march")));
    }

    @Test
    void unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/seasons/1997/bracket.txt"))
                .andExpect(status().isNotFound());
    }
}
