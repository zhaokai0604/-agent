package com.award.log.integration;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.DurableLogTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DurableLogFlowIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisTaskManager tasks;
    @Autowired DurableLogTaskStore runtime;
    @Autowired LogAnalysisService analysis;
    @Autowired com.award.log.security.RequestUserResolver resolver;
    @TempDir Path dir;
    MockHttpSession owner;
    final List<String> created=new ArrayList<>();

    @BeforeEach void login() throws Exception { owner=IntegrationTestSupport.registerAndLogin(mvc,"durable",jdbc); }
    @AfterEach void cleanup() { for(String id:created) tasks.deleteTask(id); }

    @Test @Timeout(45) void uploadCompletesExportsAndDeletionCannotBeUndoneByLateReportJob() throws Exception {
        var file=new MockMultipartFile("file","durable.log","text/plain",("2026-10-28 10:00:00 INFO request="+UUID.randomUUID()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=mvc.perform(IntegrationTestSupport.withSession(multipart("/log/upload").file(file),owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        String id=new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data").asText(); created.add(id);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertEquals("COMPLETED",tasks.getDbStatus(id)));
        mvc.perform(get("/log/task/"+id).session(owner)).andExpect(jsonPath("$.data.runtime.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.runtime.attempt").value(1));
        mvc.perform(get("/log/download/"+id+"/html").session(owner)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        assertEquals("READY",runtime.snapshot(id).reportStatus());
        Path retained=Path.of(jdbc.queryForObject("SELECT input_path FROM log_task_runtime WHERE task_id=?",String.class,id));
        assertTrue(Files.exists(retained));
        mvc.perform(IntegrationTestSupport.withSession(delete("/log/delete/"+id),owner))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(tasks.getDbStatus(id)); assertEquals("DELETED",runtime.snapshot(id).status());
        assertFalse(Files.exists(retained));
        analysis.ensureReportArtifacts(id,List.of(new EnhancedLogParseResultEntity("late export")));
        assertFalse(Files.exists(Path.of("target/output",id)));
        runtime.purgeExpiredInputs();
        assertNull(jdbc.queryForObject("SELECT input_path FROM log_task_runtime WHERE task_id=?",String.class,id));
    }

    @Test @Timeout(45) void ownerCanResumeExpiredExecutionFromPersistedInputThroughHttp() throws Exception {
        String id=UUID.randomUUID().toString(); created.add(id);
        var request=new org.springframework.mock.web.MockHttpServletRequest(); request.setSession(owner);
        Integer userId=resolver.currentUserId(request);
        tasks.initTask(id,userId,"interrupted.log");
        Path source=Files.writeString(dir.resolve("input.log"),"2026-10-28 10:00:00 INFO recovery="+id+"\n");
        tasks.prepareInput(id,source.toFile()); assertNotNull(runtime.claim(id));
        jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id=?",id);
        jdbc.update("UPDATE log_task_runtime SET lease_until=? WHERE task_id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
        runtime.reconcileExpired(); Files.delete(source);
        mvc.perform(get("/log/task/"+id).session(owner)).andExpect(jsonPath("$.data.status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.data.runtime.recoverable").value(true));
        MockHttpSession other=IntegrationTestSupport.registerAndLogin(mvc,"other");
        mvc.perform(IntegrationTestSupport.withSession(post("/log/resume/"+id),other)).andExpect(jsonPath("$.code").value(404));
        mvc.perform(IntegrationTestSupport.withSession(post("/log/resume/"+id),owner)).andExpect(jsonPath("$.code").value(200));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertEquals("COMPLETED",tasks.getDbStatus(id)));
        assertEquals(2,runtime.snapshot(id).attempt());
        assertEquals("COMPLETED",runtime.snapshot(id).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM log_analysis_detail WHERE task_id=?",Integer.class,id));
    }

    @Test @Timeout(45) void pauseBeforeAsyncExecutionStillResumesAndCompletes() throws Exception {
        String id=UUID.randomUUID().toString(); created.add(id);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSession(owner);
        tasks.initTask(id,resolver.currentUserId(request),"paused-queue.log");
        Path upload=Files.writeString(dir.resolve("queued.log"),"2026-10-28 10:00:00 INFO paused="+id+"\n");
        tasks.prepareInput(id,upload.toFile());
        mvc.perform(IntegrationTestSupport.withSession(post("/log/pause/"+id),owner)).andExpect(jsonPath("$.code").value(200));
        analysis.startAnalysisAsync(upload.toFile(),id);
        mvc.perform(IntegrationTestSupport.withSession(post("/log/resume/"+id),owner)).andExpect(jsonPath("$.code").value(200));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertEquals("COMPLETED",tasks.getDbStatus(id)));
        assertEquals(1,runtime.snapshot(id).attempt());
        assertEquals("COMPLETED",runtime.snapshot(id).status());
    }

    @Test @Timeout(45) void analysisUsesPersistedInputEvenIfOriginalUploadChanges() throws Exception {
        String id=UUID.randomUUID().toString(); created.add(id);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSession(owner);
        tasks.initTask(id,resolver.currentUserId(request),"frozen-input.log");
        Path upload=Files.writeString(dir.resolve("original.log"),"2026-10-28 10:00:00 INFO stable="+id+"\n2026-10-28 10:00:01 INFO stable="+id+"\n");
        tasks.prepareInput(id,upload.toFile());
        Files.writeString(upload,"2026-10-28 10:00:00 ERROR replaced upload\n");
        analysis.startAnalysisAsync(upload.toFile(),id);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertEquals("COMPLETED",tasks.getDbStatus(id)));
        assertEquals(2,tasks.getTask(id).getSummary().getTotalLogs());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM log_analysis_detail WHERE task_id=?",Integer.class,id));
    }
}
