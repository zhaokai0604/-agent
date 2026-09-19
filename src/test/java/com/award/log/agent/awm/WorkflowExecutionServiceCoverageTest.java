package com.award.log.agent.awm;

import com.award.log.agent.AssistantAuditRecorder;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsTrustTier;
import com.award.log.util.TestRuntimePlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceCoverageTest {

    @Mock private McpToolDispatcher dispatcher;
    @Mock private OpsRemediationGate remediationGate;
    @Mock private AssistantAuditRecorder auditRecorder;
    @Mock private OpsPathPolicy pathPolicy;
    @Mock private WorkflowMemoryService memoryService;

    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionService(dispatcher, remediationGate, auditRecorder,
                new ObjectMapper(), pathPolicy, new TestRuntimePlatform(false), memoryService, null);
        ReflectionTestUtils.setField(service, "logCleanDays", 30);
        ReflectionTestUtils.setField(service, "tempCleanDays", 7);
        lenient().when(memoryService.isEnabled()).thenReturn(false);
        lenient().when(pathPolicy.isAllowedCleanDirectory(anyString())).thenReturn(false);
        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
    }

    @Test
    void summarizersCoverReadAndWriteToolPayloadShapes() throws Exception {
        Map<String, String> payloads = new LinkedHashMap<>();
        payloads.put("DiskTool", "{\"overview\":[{\"mountedOn\":\"/\",\"usePercent\":\"85%\"}]}");
        payloads.put("DiskAnalyzeTool", "{\"overview\":[{\"mountedOn\":\"/data\",\"usePercent\":\"90%\"}],\"multiDrive\":true}");
        payloads.put("DiskInsightTool", "{\"entries\":[{\"path\":\"/var/log\"}]}");
        payloads.put("SystemLoadTool", "{\"cpuUsagePercent\":80,\"memUsagePercent\":70}");
        payloads.put("ProcessTool", "{\"processes\":[{\"pid\":1}]}");
        payloads.put("ProcessOpsTool", "{\"processes\":[]}");
        payloads.put("SystemdTool", "{\"failedUnits\":[\"nginx\"]}");
        payloads.put("ServiceOpsTool", "{\"failedUnits\":[]}");
        payloads.put("NetworkTool", "{\"type\":\"ping\",\"target\":\"127.0.0.1\",\"packetLossPercent\":0}");
        payloads.put("PortHealthTool", "{\"unreachablePorts\":[]}");
        payloads.put("CleanTempTool", "{\"filesFound\":3}");
        payloads.put("LogCleanupTool", "{\"deletableCount\":2}");
        payloads.put("ServiceRestartTool", "{\"service\":\"nginx\"}");
        payloads.put("LogAnalysisTool", "{\"anomalyCount\":4}");
        payloads.put("DockerTool", "{\"containers\":[{}]}");
        payloads.put("ContainerOpsTool", "{\"containers\":[]}");
        payloads.put("FirewallTool", "{}");
        payloads.put("SslCertTool", "{\"daysUntilExpiry\":20}");
        payloads.put("CronJobTool", "{\"jobs\":[{}]}");
        payloads.put("ConfigCheckTool", "{\"passed\":true}");
        payloads.put("ConfigDriftTool", "{\"driftDetected\":true}");
        payloads.put("OsInsightTool", "{\"operation\":\"summary\"}");
        payloads.put("PrivilegeTool", "{}");
        payloads.put("DiskOpsTool", "{\"operation\":\"status\"}");
        payloads.put("LogOpsTool", "{\"mode\":\"status\"}");
        payloads.put("AutonomousOpsTool", "{}");

        for (Map.Entry<String, String> entry : payloads.entrySet()) {
            String mode = AwmToolProfile.isWrite(entry.getKey()) ? "PREVIEW" : "READ";
            String raw = "{\"success\":true,\"data\":" + entry.getValue() + "}";
            String summary = ReflectionTestUtils.invokeMethod(service, "summarizeToolPayload",
                    entry.getKey(), mode, raw);
            assertNotNull(summary, entry.getKey());
            assertFalse(summary.isBlank(), entry.getKey());
        }

        String malformed = ReflectionTestUtils.invokeMethod(service, "summarizeToolPayload",
                "DiskTool", "READ", "{\"success\":true,\"data\":\"not-json\"}");
        assertNotNull(malformed);
    }

    @Test
    void defaultArgumentsNormalizePlaceholdersAndCoerceValues() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("log-path", "/var/log/app");
        variables.put("temp-path", "/tmp/app");
        variables.put("service-name", "nginx");
        variables.put("log-days", 14);
        variables.put("temp-days", 3);
        for (String tool : List.of("DiskAnalyzeTool", "ProcessTool", "SystemdTool", "CleanTempTool",
                "LogCleanupTool", "ServiceRestartTool", "NetworkTool", "PortHealthTool", "DockerTool",
                "ContainerOpsTool", "LogAnalysisTool", "FirewallTool", "SslCertTool", "CronJobTool",
                "ConfigCheckTool", "ConfigDriftTool", "OsInsightTool", "PrivilegeTool", "ProcessOpsTool",
                "ServiceOpsTool", "LogOpsTool", "DiskOpsTool")) {
            Map<String, Object> params = new LinkedHashMap<>();
            ReflectionTestUtils.invokeMethod(service, "applyToolDefaults", tool, params, variables);
            assertFalse(params.isEmpty(), tool);
        }

        assertEquals("rootPath", ReflectionTestUtils.invokeMethod(service, "normalizeArgKey",
                "DiskAnalyzeTool", "path"));
        assertEquals("includeHotspots", ReflectionTestUtils.invokeMethod(service, "normalizeArgKey",
                "DiskAnalyzeTool", "includeSubdirs"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeArgKey", "DiskTool", ""));
        assertEquals("value", ReflectionTestUtils.invokeMethod(service, "resolveArgValue", "value", variables));
        assertEquals("/var/log/app", ReflectionTestUtils.invokeMethod(service, "resolveArgValue", "{log-path}", variables));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveArgValue", "{missing}", variables));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveArgValue", null, variables));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "coerceValue", "true"));
        assertEquals(1.5, ReflectionTestUtils.invokeMethod(service, "coerceValue", "1.5"));
        assertEquals(12, ((Number) ReflectionTestUtils.invokeMethod(service, "coerceValue", "12")).intValue());
        assertEquals("word", ReflectionTestUtils.invokeMethod(service, "coerceValue", " word "));
        assertEquals(14, ((Number) ReflectionTestUtils.invokeMethod(service, "intParam", Map.of("days", "bad"), "days", 14)).intValue());
        assertEquals(3, ((Number) ReflectionTestUtils.invokeMethod(service, "intParam", Map.of("days", 3), "days", 14)).intValue());
        assertEquals(14, ((Number) ReflectionTestUtils.invokeMethod(service, "intParam", Map.of(), "days", 14)).intValue());
        assertEquals("abc...", ReflectionTestUtils.invokeMethod(service, "truncate", "abcdef", 3));
    }

    @Test
    void executionPersistenceAndRecordHelpersCoverSummaryStates() {
        when(memoryService.isEnabled()).thenReturn(true);
        when(dispatcher.dispatch(eq("DiskTool"), anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"overview\":[]}}"));
        OpsWorkflow workflow = new OpsWorkflow("wf-read", "disk", List.of(), "read", "read",
                List.of(OpsWorkflowStep.of("inspect", "inspect", "DiskTool")),
                "test", "t-1", 0, true);
        List<Map<String, Object>> audit = new ArrayList<>();
        WorkflowExecutionService.WorkflowRunResult result = service.execute(workflow,
                new WorkflowExecutionService.ExecutionRequest("t-1", "inspect", McpToolSurface.FULL,
                        false, audit, Map.of()));
        assertTrue(result.anyReadSucceeded());
        assertTrue(result.completedOk());
        assertTrue(result.handled());
        assertEquals(1, result.successfulStepCount());
        assertTrue(result.rawResultForTool("DiskTool").isPresent());
        assertTrue(result.rawResultForTool("Missing").isEmpty());
        assertTrue(result.toMarkdown().contains("回放完成"));
        verify(memoryService).recordRun(eq("wf-read"), eq("t-1"), eq(true), eq(1), eq(1), anyString());
        verify(memoryService).recordSuccess("wf-read");
        verify(memoryService).recordUtility("wf-read");
        verify(auditRecorder, atLeastOnce()).addStructuredStep(anyList(), eq("workflow"), anyMap());

        WorkflowExecutionService.WorkflowRunResult empty = new WorkflowExecutionService.WorkflowRunResult(
                workflow, List.of(), false, false, false, false, true);
        assertFalse(empty.handled());
        assertFalse(empty.completedOk());
        assertTrue(empty.toMarkdown().contains("只能提供参考"));
        assertTrue(new WorkflowExecutionService.StepResult("DiskTool", "READ", true,
                false, false, false, "ok", Map.of(), "raw").toMarkdownLine().contains("读取"));
        assertTrue(new WorkflowExecutionService.StepResult("CleanTempTool", "EXECUTE", true,
                true, false, false, "ok", Map.of(), "raw").toMarkdownLine().contains("执行"));
        assertTrue(new WorkflowExecutionService.StepResult("CleanTempTool", "PREVIEW", true,
                false, true, false, "ok", Map.of(), "raw").toMarkdownLine().contains("预览"));
        assertTrue(new WorkflowExecutionService.StepResult("CleanTempTool", "SKIP", true,
                false, false, true, "ok", Map.of(), null).toMarkdownLine().contains("跳过"));
    }

    @Test
    void forbiddenAndUnintegratedWriteModesAreBlocked() {
        when(remediationGate.decideServiceRestart(anyString(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.FORBIDDEN,
                        GovernanceAdmissionVerdict.FORBIDDEN,
                        OpsTrustTier.BLOCK,
                        "service forbidden"));
        OpsWorkflow workflow = new OpsWorkflow("wf-forbidden", "service", List.of(), "restart", "restart",
                List.of(OpsWorkflowStep.of("restart", "restart", "ServiceRestartTool",
                        Map.of("serviceName", "nginx"))), "test", "t-2", 0, true);
        WorkflowExecutionService.WorkflowRunResult denied = service.execute(workflow,
                new WorkflowExecutionService.ExecutionRequest("t-2", "restart", McpToolSurface.FULL,
                        true, new ArrayList<>(), Map.of()));
        assertTrue(denied.blocked());
        assertFalse(denied.failed());
        assertEquals("SKIP", denied.stepResults().get(0).mode());

        when(dispatcher.dispatch(eq("DiskOpsTool"), anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"operation\":\"status\"}}"));
        OpsWorkflow unintegrated = new OpsWorkflow("wf-unintegrated", "disk", List.of(), "disk status", "read",
                List.of(OpsWorkflowStep.of("read", "read", "DiskOpsTool")), "test", "t-3", 0, true);
        WorkflowExecutionService.WorkflowRunResult skipped = service.execute(unintegrated,
                new WorkflowExecutionService.ExecutionRequest("t-3", "write", McpToolSurface.FULL,
                        true, new ArrayList<>(), Map.of()));
        assertFalse(skipped.blocked());
        assertFalse(skipped.failed());
        assertTrue(skipped.handled());
    }
}
