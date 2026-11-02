package com.award.log.controller;

import com.award.log.service.AiAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpsAnalysisSkillControllerSmokeTest {

    @Mock
    private AiAnalysisService aiAnalysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OpsAnalysisSkillController(aiAnalysisService)).build();
    }

    @Test
    void listSkillsReturnsCatalog() throws Exception {
        mockMvc.perform(get("/api/ops/analysis-skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("log_analysis"))
                .andExpect(jsonPath("$.data[0].kind").value("analysis"));
    }

    @Test
    void invokeSkillReturnsAnswer() throws Exception {
        when(aiAnalysisService.invokeSkill(eq("log_analysis"), anyMap())).thenReturn("ok");

        mockMvc.perform(post("/api/ops/analysis-skills/log_analysis/invoke")
                        .contentType("application/json")
                        .content("{\"content\":\"error line\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("ok"));

        verify(aiAnalysisService).invokeSkill(eq("log_analysis"), anyMap());
    }
}
