package com.award.log.integration;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.service.AiDiagnosisService;
import com.award.log.service.LogTaskDiagnosisService;
import com.award.log.task.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LogDiagnosisApiIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisTaskManager tasks;
    @Autowired LogTaskDiagnosisService diagnosis;
    @Autowired com.award.log.security.RequestUserResolver users;
    @MockBean AiDiagnosisService model;
    MockHttpSession owner;
    String id;
    @BeforeEach void setup() throws Exception {
        owner=IntegrationTestSupport.registerAndLogin(mvc,"diagnosis",jdbc);
        var request=new MockHttpServletRequest();request.setSession(owner);
        id=UUID.randomUUID().toString();tasks.initTask(id,users.currentUserId(request),"diagnosis.log");
        var log=new EnhancedLogParseResultEntity("ERROR connection refused");
        log.setAnomaly(true);log.setDesensitizedLog("ERROR connection refused");
        assertTrue(tasks.completeTask(id,List.of(log),new TaskInfo.TaskSummary()));
    }
    @AfterEach void cleanup() { tasks.deleteTask(id);diagnosis.reconcile(); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJob(String suffix,String payload) {
        return IntegrationTestSupport.withSession(post("/log/diagnosis/"+id+suffix).contentType("application/json").content(payload),owner);
    }
    @Test @Timeout(30) void ownerCanStartObserveAndReadActualPersistedResult() throws Exception {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.just("evidence: connection refused"));
        mvc.perform(postJob("","{\"requestId\":\"success\"}")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("SUCCEEDED",diagnosis.status(id).status()));
        mvc.perform(get("/log/diagnosis/"+id).session(owner)).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.diagnosis").value("evidence: connection refused"));
        mvc.perform(postJob("","{\"requestId\":\"success\"}")).andExpect(jsonPath("$.data.attempt").value(1));
        verify(model,times(1)).generateTrackedDiagnosis(anyList());
        assertEquals("COMPLETED",tasks.getDbStatus(id));
    }
    @Test @Timeout(30) void anotherUserCannotReadStartOrCancelDiagnosis() throws Exception {
        var other=IntegrationTestSupport.registerAndLogin(mvc,"diagnosis-other");
        mvc.perform(get("/log/diagnosis/"+id).session(other)).andExpect(status().isNotFound());
        mvc.perform(IntegrationTestSupport.withSession(post("/log/diagnosis/"+id).contentType("application/json")
                .content("{\"requestId\":\"other\"}"),other)).andExpect(status().isNotFound());
        mvc.perform(IntegrationTestSupport.withSession(post("/log/diagnosis/"+id+"/cancel").contentType("application/json")
                .content("{\"runId\":\"other\"}"),other)).andExpect(status().isNotFound());
        verifyNoInteractions(model);
    }
    @Test @Timeout(30) void failedModelCanBeRetriedWithoutReparsingAndCancelIsBoundToRun() throws Exception {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.error(new IllegalStateException("provider unavailable")));
        mvc.perform(postJob("","{\"requestId\":\"failed\"}")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals("FAILED",diagnosis.status(id).status()));
        String oldRun=diagnosis.status(id).runId();
        assertNull(tasks.getTask(id).getAiDiagnosis());
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.never());
        mvc.perform(postJob("","{\"requestId\":\"retry\"}")).andExpect(jsonPath("$.data.attempt").value(2));
        mvc.perform(postJob("/cancel","{\"runId\":\""+oldRun+"\"}")).andExpect(status().isConflict());
        String newRun=diagnosis.status(id).runId();
        mvc.perform(postJob("/cancel","{\"runId\":\""+newRun+"\"}")).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM log_analysis_detail WHERE task_id=?",Integer.class,id));
    }
    @Test @Timeout(30) void invalidRequestsAndUnfinishedTaskDoNotStartModel() throws Exception {
        mvc.perform(postJob("","{}")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id=?",id);
        mvc.perform(postJob("","{\"requestId\":\"not-ready\"}")).andExpect(status().isConflict());
        verifyNoInteractions(model);
    }
}
