package com.award.log.service;

import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.OpsPathPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OpsAutoRemediationServiceTest {

    @Mock
    private CleanTempTool cleanTempTool;
    @Mock
    private LogCleanupTool logCleanupTool;
    @Mock
    private ServiceRestartTool serviceRestartTool;
    @Mock
    private OpsPathPolicy opsPathPolicy;
    @Mock
    private AgenticRiskScoreEngine agenticRiskScoreEngine;
    @Mock
    private McpInvocationSecurityGate mcpInvocationSecurityGate;
    @Mock
    private OpsGovernanceService opsGovernanceService;
    @Mock
    private OpsAuditTraceService opsAuditTraceService;
    @Mock
    private HttpAuditSubject httpAuditSubject;

    private OpsAutoRemediationService service;

    @BeforeEach
    void setUp() {
        service = new OpsAutoRemediationService(
                cleanTempTool,
                logCleanupTool,
                serviceRestartTool,
                opsPathPolicy,
                new ObjectMapper(),
                agenticRiskScoreEngine,
                mcpInvocationSecurityGate,
                opsGovernanceService,
                opsAuditTraceService,
                httpAuditSubject);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "riskPatrolAutoMax", 6.0d);
        ReflectionTestUtils.setField(service, "pendingProposalId", "p-1");
        ReflectionTestUtils.setField(service, "pendingExpiresAtMs", System.currentTimeMillis() + 60_000L);
        ReflectionTestUtils.setField(service, "pendingSteps", List.of(Map.of(
                "kind", "CLEAN_TEMP",
                "path", "/tmp/demo",
                "days", 1)));
        ReflectionTestUtils.setField(service, "pendingSummary", "summary");
        ReflectionTestUtils.setField(service, "pendingCorrelationDigest", Map.of("diskUsagePct", 91.0));
        ReflectionTestUtils.setField(service, "pendingFindingsCount", 1);
    }

    @Test
    void pendingProposalIsClaimedPerRequester() {
        Map<String, Object> ownerView = service.getPendingProposalView("u1");
        Map<String, Object> otherView = service.getPendingProposalView("u2");
        Map<String, Object> denied = service.confirmPending("p-1", OpsAutoRemediationService.CONFIRM_CODE, "u2");
        Map<String, Object> ownerViewAgain = service.getPendingProposalView("u1");

        assertTrue(Boolean.TRUE.equals(ownerView.get("hasPending")));
        assertEquals("u1", ownerView.get("claimOwner"));

        assertFalse(Boolean.TRUE.equals(otherView.get("hasPending")));
        assertTrue(Boolean.TRUE.equals(otherView.get("claimedByOther")));

        assertFalse(Boolean.TRUE.equals(denied.get("success")));
        assertTrue(String.valueOf(denied.get("error")).contains("其他操作者"));

        assertTrue(Boolean.TRUE.equals(ownerViewAgain.get("hasPending")));
    }

    @Test
    void afterPatrolNoActionClearsStaleExecutionSummaryAndPendingProposal() {
        ReflectionTestUtils.setField(service, "lastSummary", Map.of(
                "traceId", "old-trace",
                "actions", List.of(Map.of("success", true))));

        service.afterPatrol(Map.of(
                "diskUsagePct", 42.0,
                "cpuUsagePct", 10.0,
                "memoryUsagePct", 35.0), List.of());

        Map<String, Object> summary = service.getLastSummary();
        assertEquals("NO_ACTION", summary.get("status"));
        assertEquals(0, summary.get("plannedCount"));
        assertFalse(summary.containsKey("traceId"));
        assertTrue(summary.get("actions") instanceof List<?> actions && actions.isEmpty());
        assertFalse(Boolean.TRUE.equals(service.getPendingProposalView("u1").get("hasPending")));
    }

    @Test
    void emptyFindingsNeverCreatePlanEvenWhenThresholdsAreLow() {
        configurePatrolDefaults();
        ReflectionTestUtils.setField(service, "proposeTempCleanDiskMin", 1.0d);
        ReflectionTestUtils.setField(service, "proposeLogCleanDiskMin", 1.0d);

        service.afterPatrol(Map.of("diskUsagePct", 20.0, "cpuUsagePct", 10.0), List.of());

        assertEquals("NO_ACTION", service.getLastSummary().get("status"));
        assertEquals(0, service.getLastSummary().get("plannedCount"));
        assertTrue(service.getRemediationCoverage().isEmpty());
        assertFalse(Boolean.TRUE.equals(service.getPendingProposalView("u1").get("hasPending")));
    }

    @Test
    void disabledAndInvalidPatrolInputsFailClosed() {
        clearPending();
        ReflectionTestUtils.setField(service, "enabled", false);
        service.afterPatrol(Map.of("diskUsagePct", 95.0), List.of(finding("disk")));
        assertEquals("DISABLED", service.getLastSummary().get("status"));
        assertTrue(service.getRemediationCoverage().isEmpty());

        ReflectionTestUtils.setField(service, "enabled", true);
        service.afterPatrol(Map.of(), List.of(finding("disk")));
        assertEquals("INVALID_CORRELATION", service.getLastSummary().get("status"));

        configurePatrolDefaults();
        service.afterPatrol(Map.of("diskUsagePct", 0.0, "cpuUsagePct", 10.0),
                List.of(finding("disk")));
        assertEquals("INVALID_METRICS", service.getLastSummary().get("status"));
        assertEquals("NONE", ((Map<?, ?>) service.getRemediationCoverage().get(0).get("remediation")).get("lane"));
    }

    @Test
    void confirmFirstPatrolProducesAndExecutesTwoStepProposal() throws Exception {
        clearPending();
        configurePatrolDefaults();
        ReflectionTestUtils.setField(service, "runMode", "CONFIRM_FIRST");
        when(cleanTempTool.cleanTempFiles(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));
        when(logCleanupTool.cleanupOldLogs(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));

        service.afterPatrol(Map.of("diskUsagePct", 91.0, "cpuUsagePct", 20.0,
                        "memoryUsagePct", 40.0), List.of(finding("disk")));

        Map<String, Object> pending = service.getPendingProposalView("u1");
        assertTrue(Boolean.TRUE.equals(pending.get("hasPending")));
        assertEquals(2, ((List<?>) pending.get("steps")).size());
        assertEquals("PENDING_CONFIRMATION", service.getLastSummary().get("status"));

        Map<String, Object> executed = service.confirmPending(
                String.valueOf(pending.get("proposalId")), OpsAutoRemediationService.CONFIRM_CODE, "u1");
        assertTrue(Boolean.TRUE.equals(executed.get("success")));
        assertEquals(2, executed.get("actionCount"));
        assertEquals(2, executed.get("successCount"));
        assertFalse(Boolean.TRUE.equals(service.getPendingProposalView("u1").get("hasPending")));
    }

    @Test
    void immediatePatrolExecutesOnceThenHonorsCooldown() throws Exception {
        clearPending();
        configurePatrolDefaults();
        ReflectionTestUtils.setField(service, "runMode", "IMMEDIATE");
        ReflectionTestUtils.setField(service, "immediateTempCleanDiskMin", 80.0d);
        ReflectionTestUtils.setField(service, "immediateLogCleanDiskMin", 80.0d);
        when(cleanTempTool.cleanTempFiles(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));
        when(logCleanupTool.cleanupOldLogs(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));

        Map<String, Object> correlation = Map.of("diskUsagePct", 96.0, "cpuUsagePct", 20.0);
        service.afterPatrol(correlation, List.of(finding("disk")));
        assertEquals("EXECUTED", service.getLastSummary().get("status"));
        assertEquals(2, service.getLastSummary().get("executedCount"));

        service.afterPatrol(correlation, List.of(finding("disk")));
        assertEquals("SKIPPED_COOLDOWN", service.getLastSummary().get("status"));
    }

    @Test
    void hybridPatrolSeparatesLowAndHighRiskStepsAndAllowsConfirmation() throws Exception {
        clearPending();
        configurePatrolDefaults();
        ReflectionTestUtils.setField(service, "runMode", "HYBRID");
        ReflectionTestUtils.setField(service, "riskPatrolAutoMax", 6.0d);
        when(agenticRiskScoreEngine.getConfirmMax()).thenReturn(10.0d);
        when(mcpInvocationSecurityGate.buildInstruction(anyString(), anyMap()))
                .thenReturn("scored instruction");
        when(agenticRiskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenAnswer(invocation -> {
                    String tool = invocation.getArgument(0);
                    double score = "CleanTempTool".equals(tool) ? 4.0d : 7.0d;
                    return new AgenticRiskScoreEngine.ScoreResult(score, Map.of("total", score), tool);
                });
        when(cleanTempTool.cleanTempFiles(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));
        when(logCleanupTool.cleanupOldLogs(anyString(), any(), any(), any()))
                .thenReturn(successfulWrite("filesDeleted", 1));

        service.afterPatrol(Map.of("diskUsagePct", 91.0, "cpuUsagePct", 20.0),
                List.of(finding("disk")));

        assertEquals("EXECUTED_WITH_PENDING", service.getLastSummary().get("status"));
        assertEquals(1, service.getLastSummary().get("pendingCount"));
        Map<?, ?> coverage = service.getRemediationCoverage().get(0);
        assertEquals("MIXED", ((Map<?, ?>) coverage.get("remediation")).get("lane"));

        Map<String, Object> pending = service.getPendingProposalView("u1");
        Map<String, Object> confirmed = service.confirmPending(
                String.valueOf(pending.get("proposalId")), OpsAutoRemediationService.CONFIRM_CODE, "u1");
        assertTrue(Boolean.TRUE.equals(confirmed.get("success")));
        assertEquals(1, confirmed.get("actionCount"));
    }

    private void configurePatrolDefaults() {
        lenient().when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
        lenient().when(opsPathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of("/var/log"));
        lenient().when(opsPathPolicy.getPolicyVersion()).thenReturn("test-policy");
        lenient().when(opsGovernanceService.filterPlanSteps(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(opsGovernanceService.isEnabled()).thenReturn(false);
        lenient().when(httpAuditSubject.currentOperatorId()).thenReturn("u1");
    }

    private void clearPending() {
        ReflectionTestUtils.setField(service, "pendingProposalId", null);
        ReflectionTestUtils.setField(service, "pendingExpiresAtMs", 0L);
        ReflectionTestUtils.setField(service, "pendingSteps", null);
        ReflectionTestUtils.setField(service, "pendingSummary", null);
        ReflectionTestUtils.setField(service, "pendingCorrelationDigest", null);
        ReflectionTestUtils.setField(service, "pendingFindingsCount", 0);
        ReflectionTestUtils.setField(service, "pendingClaimedBy", null);
        ReflectionTestUtils.setField(service, "pendingClaimedAtMs", 0L);
    }

    private static Map<String, Object> finding(String code) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("code", code);
        finding.put("level", "WARN");
        finding.put("title", code + " finding");
        finding.put("detail", "test finding");
        return finding;
    }

    private static String successfulWrite(String field, int value) {
        return "{\"success\":true,\"data\":{\"mode\":\"DELETE\",\""
                + field + "\":" + value + ",\"bytesFreed\":1024,"
                + "\"recycledCount\":1,\"protectedFilesPreserved\":true}}";
    }
}
