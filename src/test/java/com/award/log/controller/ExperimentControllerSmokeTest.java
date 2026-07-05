package com.award.log.controller;

import com.award.log.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExperimentControllerSmokeTest {

    @Mock private ExperimentService experimentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ExperimentController controller = new ExperimentController(experimentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void startExperimentReturnsSuccess() throws Exception {
        when(experimentService.start("ab-default")).thenReturn(true);

        mockMvc.perform(post("/api/v1/experiment/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ab-default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void reportReturnsSummary() throws Exception {
        when(experimentService.report()).thenReturn(Map.of("running", false));

        mockMvc.perform(get("/api/v1/experiment/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(false));
    }
}
