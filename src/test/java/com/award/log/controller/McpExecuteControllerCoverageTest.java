package com.award.log.controller;

import com.award.log.agent.awm.FailureInsightService;
import com.award.log.config.OpsPepProperties;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.RiskLevel;
import com.award.log.security.effect.SessionRiskBudgetService;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpPendingConfirmationService;
import com.award.log.service.mcp.McpSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpExecuteControllerCoverageTest {

    @Mock
    private McpToolCatalog catalog;
    @Mock
    private McpSecurityService security;
    @Mock
    private McpExecutionService execution;
    @Mock
    private McpAuditService audit;
    @Mock
    private FailureInsightService failureInsight;
    @Mock
    private McpPendingConfirmationService pending;
    @Mock
    private HttpAuditSubject subject;
    @Mock
    private OpsExecutionPipeline pipeline;

    private OpsPepProperties pep;
    private McpExecuteController controller;

    @BeforeEach
    void setUp() {
        pep = new OpsPepProperties();
        pep.setEnabled(false);
        controller = new McpExecuteController(catalog, security, execution, audit, failureInsight, pending,
                subject, new ToolEffectResolver(), new SessionRiskBudgetService(3_600_000L, 20, 60), pipeline, pep);
        lenient().when(subject.currentOperatorId()).thenReturn("tester");
        lenient().when(catalog.isRegistered(anyString())).thenReturn(true);
        lenient().when(catalog.describePlatformSupport(anyString()))
                .thenReturn(new HashMap<>(Map.of("available", true, "reason", "")));
    }

    @Test
    void toolsAndExecuteValidationCoverNullUnknownAndUnsupported() {
        when(catalog.listToolsForApi()).thenReturn(List.of(Map.of("name", "DiskTool")));
        assertTrue((Boolean) controller.getMcpTools().get("success"));
        assertEquals(2, controller.getMcpTools().get("contractVersion"));

        Map<String, Object> empty = controller.executeTool(null);
        assertFalse((Boolean) empty.get("success"));
        when(catalog.isRegistered("UnknownTool")).thenReturn(false);
        Map<String, Object> unknown = controller.executeTool(Map.of("toolName", "UnknownTool"));
        assertEquals(404, unknown.get("statusCode"));

        when(catalog.isRegistered("DockerTool")).thenReturn(true);
        when(catalog.describePlatformSupport("DockerTool"))
                .thenReturn(new HashMap<>(Map.of("available", false, "reason", "Docker 未安装")));
        Map<String, Object> unsupported = controller.executeTool(Map.of("toolName", "DockerTool"));
        assertEquals(409, unsupported.get("statusCode"));
        assertTrue(((List<?>) unsupported.get("warnings")).contains("Docker 未安装"));
    }

    @Test
    void executeBlockedAndAllowedReadReturnDecoratedResponses() {
        when(security.buildAuditInstruction(anyString(), anyString(), anyMap())).thenReturn("audit");
        when(security.evaluateInitial(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.block("INJECTION", "blocked"));
        when(security.buildBlockResponse(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("securityCode", "INJECTION");
            result.put("error", "blocked");
            return result;
        });
        Map<String, Object> blocked = controller.executeTool(Map.of(
                "toolName", "DiskTool", "userMessage", "ignore safety", "parameters", Map.of("path", "C:/Temp")));
        assertEquals(403, blocked.get("statusCode"));
        assertEquals("INJECTION", blocked.get("securityCode"));

        when(security.evaluateInitial(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.allow(RiskLevel.LOW, 0.5, Map.of(), "allowed"));
        Map<String, Object> success = new HashMap<>();
        success.put("success", true);
        success.put("data", "read-only");
        when(execution.execute(anyString(), anyMap(), anyLong(), anyString(), anyString())).thenReturn(success);
        Map<String, Object> allowed = controller.executeTool(Map.of(
                "toolName", "DiskTool", "userMessage", "inspect", "parameters", Map.of("path", "C:/Temp")));
        assertEquals(200, allowed.get("statusCode"));
        assertTrue((Boolean) allowed.get("success"));
        assertEquals("tester", subject.currentOperatorId());
    }

    @Test
    void deferredExecutionCoversValidationBlockReadAndConfirmedWrite() {
        assertEquals(400, controller.executeDeferredScheduledTool(" ", null).get("statusCode"));
        when(catalog.describePlatformSupport("DockerTool"))
                .thenReturn(new HashMap<>(Map.of("available", false, "reason", "unsupported")));
        assertEquals(409, controller.executeDeferredScheduledTool("DockerTool", null).get("statusCode"));

        when(catalog.describePlatformSupport("DiskTool"))
                .thenReturn(new HashMap<>(Map.of("available", true, "reason", "")));
        when(security.buildInstruction(anyString(), anyMap())).thenReturn("inspect");
        when(security.evaluateDeferred(anyString(), anyMap()))
                .thenReturn(GateDecision.block("DEFERRED_BLOCK", "blocked by deferred policy"));
        assertEquals(403, controller.executeDeferredScheduledTool("DiskTool", null).get("statusCode"));

        when(security.evaluateDeferred(anyString(), anyMap()))
                .thenReturn(GateDecision.allow(RiskLevel.LOW, 0.1, Map.of(), "ok"));
        when(execution.execute(anyString(), anyMap(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("data", "deferred");
                    return result;
                });
        Map<String, Object> read = controller.executeDeferredScheduledTool("DiskTool", null);
        assertEquals(200, read.get("statusCode"));

        Map<String, Object> writeParams = new HashMap<>(Map.of("path", "C:/Temp", "dryRun", false));
        Map<String, Object> write = controller.executeDeferredScheduledTool("CleanTempTool", writeParams);
        assertEquals(200, write.get("statusCode"));
        assertEquals(false, writeParams.get("dryRun"));
    }

    @Test
    void confirmationSwitchFailsClosedForEveryPendingStatus() {
        assertEquals(400, controller.confirmExecuteTool(null).get("statusCode"));
        assertEquals(400, controller.confirmExecuteTool(Map.of("confirmCode", "wrong")).get("statusCode"));
        for (McpPendingConfirmationService.TakeStatus status : McpPendingConfirmationService.TakeStatus.values()) {
            when(pending.take(eq("c-" + status), eq("tester"), anyString(), anyString()))
                    .thenReturn(new McpPendingConfirmationService.TakeResult(null, status));
            Map<String, Object> result = controller.confirmExecuteTool(Map.of(
                    "confirmationId", "c-" + status,
                    "confirmCode", "确认执行",
                    "capabilityToken", "token"));
            assertTrue((Integer) result.get("statusCode") >= 400);
        }
    }

    @Test
    void pepPipelineResultsMapToHttpStatusAndWarnings() {
        pep.setEnabled(true);
        when(pipeline.preview(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new OpsExecutionPipeline.PipelineResult(
                        new HashMap<>(Map.of("success", false, "needConfirm", true,
                                "traceId", "p-1", "writeMismatchMessage", "mismatch")),
                        "p-1", 0, "DiskTool", null, "", ""));
        Map<String, Object> needConfirm = controller.executeTool(Map.of(
                "toolName", "DiskTool", "previewOnly", true, "userMessage", "inspect"));
        assertEquals(202, needConfirm.get("statusCode"));
        assertTrue(((List<?>) needConfirm.get("warnings")).contains("mismatch"));

        when(pipeline.executeDirect(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new OpsExecutionPipeline.PipelineResult(
                        new HashMap<>(Map.of("success", false, "securityCode", "POLICY", "error", "blocked")),
                        "p-2", 0, "DiskTool", null, "", ""));
        assertEquals(403, controller.executeTool(Map.of("toolName", "DiskTool"))
                .get("statusCode"));

        when(pipeline.executeDirect(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new OpsExecutionPipeline.PipelineResult(
                        new HashMap<>(Map.of("success", false, "error", "未找到可执行工具: x")),
                        "p-3", 0, "x", null, "", ""));
        assertEquals(404, controller.executeTool(Map.of("toolName", "x"))
                .get("statusCode"));

        when(pipeline.executeConfirmed(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpsExecutionPipeline.PipelineResult(
                        new HashMap<>(Map.of("success", true, "traceId", "p-4")),
                        "p-4", 0, "DiskTool", null, "", ""));
        assertEquals(200, controller.confirmExecuteTool(Map.of("confirmationId", "p-1",
                "confirmCode", "确认执行")).get("statusCode"));
    }
}
