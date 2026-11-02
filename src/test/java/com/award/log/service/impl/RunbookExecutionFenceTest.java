package com.award.log.service.impl;

import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual SQL transitions; model, policy and host-tool boundary remain isolated. */
class RunbookExecutionFenceTest {
    JdbcTemplate jdbc;
    OpsExecutionPipeline pipeline;
    McpExecutionService raw;
    McpSecurityService security;
    McpToolCatalog catalog;
    OpsAuditTraceService audit;
    OpsPathPolicy policy;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        pipeline=mock(OpsExecutionPipeline.class);raw=mock(McpExecutionService.class);
        security=mock(McpSecurityService.class);catalog=mock(McpToolCatalog.class);
        audit=mock(OpsAuditTraceService.class);policy=mock(OpsPathPolicy.class);
        when(catalog.isRegistered("ServiceRestartTool")).thenReturn(true);
        when(catalog.isHttpAllowed("ServiceRestartTool")).thenReturn(true);
        when(security.evaluatePostConfirm(anyString(),anyMap(),anyString()))
                .thenReturn(GateDecision.allow(RiskLevel.MEDIUM,4,Map.of(),"allowed"));
        when(policy.getPolicyVersion()).thenReturn("fixture");
        when(pipeline.preview(anyString(),anyMap(),anyString(),anyString()))
                .thenReturn(result(Map.of("success",true),"preview"));
        AtomicInteger number=new AtomicInteger();
        when(pipeline.propose(anyString(),anyMap(),anyString(),anyString(),anyString()))
                .thenAnswer(c -> result(Map.of("needConfirm",true,"confirmationId","ticket-"+number.incrementAndGet(),
                        "capabilityToken","cap","confirmToken","hmac","expiresAtMs",System.currentTimeMillis()+600000),"proposal"));
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(result(Map.of("success",true,"verifyOutcome","VERIFIED"),"receipt"));
    }
    OpsExecutionPipeline.PipelineResult result(Map<String,Object> body,String trace) {
        return new OpsExecutionPipeline.PipelineResult(body,trace,0,"ServiceRestartTool",null,"","preview");
    }
    RunbookApprovalService service() {
        var service=new RunbookApprovalService(raw,security,catalog,new ObjectMapper(),audit,policy,jdbc,pipeline);
        service.init();return service;
    }
    long submit(RunbookApprovalService service) {
        return ((Number)service.submit("Restart fixture","ServiceRestartTool",null,"ServiceRestartTool",
                Map.of("serviceName","fixture","dryRun",false),"owner").get("id")).longValue();
    }
    @Test void restartRequiresReapprovalAndAnotherOperatorCannotConsumeTicket() {
        var first=service();long id=submit(first);first.approve(id,"admin");
        var restarted=service();
        assertThrows(IllegalStateException.class,()->restarted.execute(id,"admin"));
        restarted.approve(id,"admin");
        assertThrows(IllegalStateException.class,()->restarted.execute(id,"other-admin"));
        assertEquals("EXECUTED",restarted.execute(id,"admin").get("status"));
        assertThrows(IllegalStateException.class,()->first.execute(id,"admin"));
        verify(pipeline,times(1)).executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(raw);
    }
    @Test void newerApprovalFencesAnOlderInstanceBeforeAnyToolCall() {
        var first=service();long id=submit(first);first.approve(id,"admin");
        var second=service();second.approve(id,"admin");
        assertThrows(IllegalStateException.class,()->first.execute(id,"admin"));
        verify(pipeline,never()).executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString());
        assertEquals("EXECUTED",second.execute(id,"admin").get("status"));
    }
    @Test void receiptFailurePreservesExecutingIntentAndCannotReplay() {
        var service=service();long id=submit(service);service.approve(id,"admin");
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("receipt unavailable"));
        assertThrows(IllegalStateException.class,()->service.execute(id,"admin"));
        assertEquals("EXECUTING",jdbc.queryForObject("SELECT status FROM ops_runbook_approval WHERE id=?",String.class,id));
        assertThrows(IllegalStateException.class,()->service().approve(id,"admin"));
        assertThrows(IllegalStateException.class,()->service().execute(id,"admin"));
        verify(pipeline,times(1)).executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void missingPipelineCannotCreateAnExecutableApproval() {
        var legacy=new RunbookApprovalService(raw,security,catalog,new ObjectMapper(),audit,policy,jdbc);
        legacy.init();long id=submit(legacy);
        assertThrows(IllegalStateException.class,()->legacy.approve(id,"admin"));
        verifyNoInteractions(raw);
    }
    @Test void inFlightWriteCannotBeReapprovedOrExecutedByAnotherInstance() throws Exception {
        var first=service();long id=submit(first);first.approve(id,"admin");
        var second=service();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(c->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    return result(Map.of("success",true),"receipt");});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var execution=pool.submit(()->first.execute(id,"admin"));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->second.approve(id,"admin"));
            assertThrows(IllegalStateException.class,()->second.execute(id,"admin"));
            release.countDown();
            assertEquals("EXECUTED",execution.get(5,java.util.concurrent.TimeUnit.SECONDS).get("status"));
            verify(pipeline,times(1)).executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString());
        } finally {release.countDown();pool.shutdownNow();}
    }

    long interrupted(RunbookApprovalService service) {
        long id=submit(service);service.approve(id,"admin");
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("receipt unavailable"));
        assertThrows(IllegalStateException.class,()->service.execute(id,"admin"));
        return id;
    }
    @Test void manualReviewIsDurableRedactedAndNeverGrantsReplay() {
        var first=service();long id=interrupted(first);
        String trace=jdbc.queryForObject("SELECT trace_id FROM ops_runbook_approval WHERE id=?",String.class,id);
        var closed=service().reconcile(id,trace,"RECOVERED","已核对服务状态 password=fixture-private", "核对结案",true,"reviewer");
        assertEquals("MANUALLY_CLOSED",closed.get("status"));
        String stored=jdbc.queryForObject("SELECT mcp_result_json FROM ops_runbook_approval WHERE id=?",String.class,id);
        assertFalse(stored.contains("fixture-private"));
        assertTrue(stored.contains("HUMAN_REVIEW"));
        assertTrue(stored.contains("\"automaticVerification\":false"));
        assertEquals("MANUALLY_CLOSED",service().list("reviewer",true).get(0).get("status"));
        assertThrows(IllegalStateException.class,()->first.approve(id,"admin"));
        assertThrows(IllegalStateException.class,()->first.execute(id,"admin"));
        assertThrows(IllegalStateException.class,()->service().reconcile(id,trace,"UNKNOWN","repeat", "核对结案",true,"reviewer"));
        verify(pipeline,times(1)).executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void reviewRequiresEvidenceExplicitConfirmationAndCurrentTrace() {
        var first=service();long id=interrupted(first);
        String trace=jdbc.queryForObject("SELECT trace_id FROM ops_runbook_approval WHERE id=?",String.class,id);
        assertThrows(IllegalArgumentException.class,()->first.reconcile(id,trace,"UNKNOWN","evidence", "确认执行",true,"admin"));
        assertThrows(IllegalArgumentException.class,()->first.reconcile(id,trace,"UNKNOWN","evidence", "核对结案",false,"admin"));
        assertThrows(IllegalArgumentException.class,()->first.reconcile(id,trace,"VERIFIED","evidence", "核对结案",true,"admin"));
        assertThrows(IllegalArgumentException.class,()->first.reconcile(id,trace,"UNKNOWN"," ", "核对结案",true,"admin"));
        assertThrows(IllegalStateException.class,()->first.reconcile(id,"stale-trace","UNKNOWN","evidence", "核对结案",true,"admin"));
        assertEquals("EXECUTING",jdbc.queryForObject("SELECT status FROM ops_runbook_approval WHERE id=?",String.class,id));
    }
    @Test void lateReceiptCannotOverwriteManualClosureOrLoseTheEvidence() throws Exception {
        var first=service();long id=submit(first);first.approve(id,"admin");
        var second=service();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(pipeline.executeConfirmed(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(c->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    return result(Map.of("success",true,"verifyOutcome","VERIFIED"),"late-trace");});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var execution=pool.submit(()->first.execute(id,"admin"));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            String trace=jdbc.queryForObject("SELECT trace_id FROM ops_runbook_approval WHERE id=?",String.class,id);
            second.reconcile(id,trace,"UNKNOWN","主机状态待进一步确认，行政结案", "核对结案",true,"reviewer");
            release.countDown();
            assertEquals("MANUALLY_CLOSED",execution.get(5,java.util.concurrent.TimeUnit.SECONDS).get("status"));
            String stored=jdbc.queryForObject("SELECT mcp_result_json FROM ops_runbook_approval WHERE id=?",String.class,id);
            assertTrue(stored.contains("manualReview"));assertTrue(stored.contains("lateReceipt"));
            assertTrue(stored.contains("late-trace"));assertTrue(stored.contains("UNKNOWN"));
        } finally {release.countDown();pool.shutdownNow();}
    }
}
