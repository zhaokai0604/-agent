package com.award.log.service;

import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.handler.PerformanceWebSocketHandler;
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

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpsAutoRemediationServiceHelperCoverageTest {

    @Mock private CleanTempTool cleanTempTool;
    @Mock private LogCleanupTool logCleanupTool;
    @Mock private ServiceRestartTool serviceRestartTool;
    @Mock private OpsPathPolicy pathPolicy;
    @Mock private AgenticRiskScoreEngine scoreEngine;
    @Mock private McpInvocationSecurityGate securityGate;
    @Mock private OpsGovernanceService governance;
    @Mock private OpsAuditTraceService audit;
    @Mock private HttpAuditSubject subject;

    private OpsAutoRemediationService service;

    @BeforeEach
    void setUp() {
        service = new OpsAutoRemediationService(cleanTempTool, logCleanupTool, serviceRestartTool, pathPolicy,
                new ObjectMapper(), scoreEngine, securityGate, governance, audit, subject);
        com.award.log.governance.GovernedPipelineFixture.install(service, cleanTempTool, logCleanupTool, serviceRestartTool);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "runMode", "HYBRID");
        ReflectionTestUtils.setField(service, "riskPatrolAutoMax", 6.0d);
        ReflectionTestUtils.setField(service, "tempCleanDays", 7);
        ReflectionTestUtils.setField(service, "logCleanDays", 30);
        ReflectionTestUtils.setField(service, "cpuPainRestartServicesRaw", "nginx, sshd,,");
        ReflectionTestUtils.setField(service, "cpuRestartCpuMinPercent", 85.0d);
        ReflectionTestUtils.setField(service, "proposalTtlMs", 60_000L);
        lenient().when(governance.filterPlanSteps(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(governance.isEnabled()).thenReturn(false);
        lenient().when(subject.currentOperatorId()).thenReturn("operator");
        lenient().when(pathPolicy.isAllowedCleanDirectory(anyString())).thenReturn(true);
        lenient().when(scoreEngine.getConfirmMax()).thenReturn(10.0d);
        lenient().when(securityGate.buildInstruction(anyString(), anyMap())).thenReturn("score instruction");
        lenient().when(scoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(4.0, Map.of("risk", 4.0), "low"));
    }

    @Test
    void planAndPolicyHelpersCoverAllSupportedRemediationKinds() {
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("diskUsagePct", 95.0);
        correlation.put("cpuUsagePct", 90.0);
        correlation.put("diskHotspotsTop", List.of(Map.of("path", "/tmp/hotspot")));
        correlation.put("timestamp", "now");
        when(pathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp", "", "/tmp"));
        when(pathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of("/var/log"));

        List<Map<String, Object>> findings = List.of(
                finding("disk"), finding("disk_hotspot"), finding("memory"), finding("cpu"),
                finding("log_anomaly"), finding("alarm_severity"), finding("process"), finding("drain"), finding("other"));
        List<Map<String, Object>> plan = ReflectionTestUtils.invokeMethod(service, "buildPlanSteps", correlation,
                findings, Set.of("disk", "disk_hotspot", "memory", "cpu", "log_anomaly", "alarm_severity"), 95.0, 90.0);
        assertNotNull(plan);
        assertTrue(plan.stream().anyMatch(s -> "CLEAN_TEMP".equals(s.get("kind"))));
        assertTrue(plan.stream().anyMatch(s -> "CLEAN_LOG".equals(s.get("kind"))));
        assertTrue(plan.stream().anyMatch(s -> "RESTART_SERVICE".equals(s.get("kind"))));

        assertEquals("CleanTempTool", ReflectionTestUtils.invokeMethod(service, "toolNameForStep", step("CLEAN_TEMP")));
        assertEquals("LogCleanupTool", ReflectionTestUtils.invokeMethod(service, "toolNameForStep", step("CLEAN_LOG")));
        assertEquals("ServiceRestartTool", ReflectionTestUtils.invokeMethod(service, "toolNameForStep", step("RESTART_SERVICE")));
        assertEquals("Unknown", ReflectionTestUtils.invokeMethod(service, "toolNameForStep", step("UNKNOWN")));
        assertEquals(4, ((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "paramsForMcpScore", step("CLEAN_TEMP"))).size());
        assertEquals(4, ((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "paramsForMcpScore", step("CLEAN_LOG"))).size());
        assertEquals(3, ((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "paramsForMcpScore", step("RESTART_SERVICE"))).size());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "paramsForMcpScore", step("UNKNOWN"))).isEmpty());

        List<Map<String, Object>> duplicate = List.of(step("CLEAN_TEMP"), step("CLEAN_TEMP"), step("UNKNOWN"));
        assertEquals(2, ((List<?>) ReflectionTestUtils.invokeMethod(service, "dedupeSteps", duplicate)).size());
        assertEquals(List.of("nginx", "sshd"), ReflectionTestUtils.invokeMethod(service, "parseCsvServices", "nginx, sshd,,"));
        assertTrue(((Set<?>) ReflectionTestUtils.invokeMethod(service, "findingCodes", findings)).contains("cpu"));
        assertEquals("HYBRID", ReflectionTestUtils.invokeMethod(service, "normalizeMode", "bad"));
        assertEquals("IMMEDIATE", ReflectionTestUtils.invokeMethod(service, "normalizeMode", " immediate "));
        assertEquals("HYBRID", ReflectionTestUtils.invokeMethod(service, "normalizeMode", (Object) null));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "buildSummaryText", plan,
                Set.of("disk"), 95.0, 90.0)).contains("确认后生效"));
        assertEquals("anonymous", ReflectionTestUtils.invokeMethod(service, "normalizeRequester", " "));
        assertEquals(12, ((Integer) ReflectionTestUtils.invokeMethod(service, "asInt", "12", 1)).intValue());
        assertEquals(1, ((Integer) ReflectionTestUtils.invokeMethod(service, "asInt", "bad", 1)).intValue());
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "truncate", null, 5));
        assertEquals("abc…", ReflectionTestUtils.invokeMethod(service, "truncate", "abcdef", 3));
    }

    @Test
    void riskSplitAndClassificationExposeAutoConfirmAndManualLanes() {
        Map<String, Object> temp = step("CLEAN_TEMP");
        Map<String, Object> log = step("CLEAN_LOG");
        Map<String, Object> restart = step("RESTART_SERVICE");
        List<Map<String, Object>> plan = List.of(temp, log, restart);
        Object split = ReflectionTestUtils.invokeMethod(service, "splitByPatrolRisk", plan);
        assertNotNull(split);
        assertTrue(((List<?>) ReflectionTestUtils.invokeMethod(service, "filterStepsForImmediate", plan,
                96.0, 90.0, Set.of("disk", "cpu"))).size() >= 2);

        assertEquals("MANUAL", lane("process", "HYBRID", plan, split, true));
        assertEquals("MANUAL", lane("drain", "HYBRID", plan, split, true));
        assertEquals("MANUAL", lane("unknown", "HYBRID", plan, split, true));
        assertEquals("NONE", lane("disk", "HYBRID", List.of(restart), split, true));
        assertEquals("AUTO", lane("disk", "IMMEDIATE", plan, split, true));
        assertEquals("CONFIRM", lane("disk", "CONFIRM_FIRST", plan, split, true));
        assertEquals("AUTO", lane("cpu", "IMMEDIATE", plan, split, true));
        assertEquals("CONFIRM", lane("cpu", "CONFIRM_FIRST", plan, split, true));
        assertEquals("AUTO", lane("log_anomaly", "IMMEDIATE", plan, split, true));
        assertEquals("CONFIRM", lane("alarm_severity", "CONFIRM_FIRST", plan, split, true));
        assertEquals("NONE", lane("disk", "HYBRID", plan, split, false));

        Map<String, Object> forbidden = new HashMap<>(temp);
        forbidden.put("governanceVerdict", "FORBIDDEN");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isGovernanceAutoAllowed", forbidden));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isGovernanceAutoAllowed", temp));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAutoExecutionSource", "IMMEDIATE"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isAutoExecutionSource", "MANUAL"));
        assertEquals("sshd", ReflectionTestUtils.invokeMethod(service, "targetOfStep", restart));
        assertEquals("/tmp", ReflectionTestUtils.invokeMethod(service, "targetOfStep", temp));
    }

    @Test
    void verifiedActionAndJsonParserRejectNoEffectAndAcceptRealEffect() {
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess", (Object) null));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess", ""));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess", "{\"success\":false}"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess", "{\"success\":true,\"status\":\"WARN\"}"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess",
                "{\"success\":true,\"data\":{\"businessEffect\":\"NO_EFFECT\"}}"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess",
                "{\"success\":true,\"data\":{\"mode\":\"NOOP\"}}"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess",
                "{\"success\":true,\"data\":{\"businessEffect\":\"DELETED\"}}"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "parseToolJsonSuccess", "not-json"));

        Map<String, Object> row = ReflectionTestUtils.invokeMethod(service, "verifiedActionRow", "CleanTempTool",
                "/tmp", "{\"success\":false,\"error\":\"denied\"}", false);
        assertFalse((Boolean) row.get("success"));
        assertEquals("VERIFY_FAILED", row.get("verifyOutcome"));
        Map<String, Object> noEffect = ReflectionTestUtils.invokeMethod(service, "verifiedActionRow", "CleanTempTool",
                "/tmp", "{\"success\":true,\"data\":{\"businessEffect\":\"NO_EFFECT\"}}", false);
        assertFalse((Boolean) noEffect.get("success"));
    }

    @Test
    void actionSummariesFormatEffectsAndAllToolLabels() {
        assertEquals("本次没有执行任何修复动作。",
                ReflectionTestUtils.invokeMethod(service, "summarizeActions", List.of()));
        String actions = ReflectionTestUtils.invokeMethod(service, "summarizeActions", List.of(
                new LinkedHashMap<>(Map.of("tool", "CleanTempTool", "path", "/tmp",
                        "success", true, "detail", "{\"data\":{\"filesDeleted\":2,\"bytesFreed\":2048}}")),
                new LinkedHashMap<>(Map.of("tool", "LogCleanupTool", "path", "/var/log",
                        "success", false, "detail", "permission denied")),
                new LinkedHashMap<>(Map.of("tool", "OtherTool", "success", false, "detail", ""))));
        assertTrue(actions.contains("成功 1 个") && actions.contains("失败 2 个"));
        assertTrue(actions.contains("释放约 2.0 KiB"));
        assertTrue(actions.contains("permission denied"));
        assertEquals("临时文件清理", ReflectionTestUtils.invokeMethod(service, "toolDisplayName", "CleanTempTool"));
        assertEquals("日志裁剪", ReflectionTestUtils.invokeMethod(service, "toolDisplayName", "LogCleanupTool"));
        assertEquals("服务重启", ReflectionTestUtils.invokeMethod(service, "toolDisplayName", "ServiceRestartTool"));
        assertEquals("未知工具", ReflectionTestUtils.invokeMethod(service, "toolDisplayName", ""));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "actionEffect", ""));
        assertEquals("plain detail", ReflectionTestUtils.invokeMethod(service, "actionEffect", "plain detail"));
        String effect = (String) ReflectionTestUtils.invokeMethod(service, "actionEffect",
                "{\"result\":{\"filesFound\":3,\"protectedSkipped\":1,\"lockedSkipped\":2,"
                        + "\"businessEffect\":\"NO_EFFECT\"}}");
        assertTrue(effect.contains("扫描到 3 个文件"));
        String nestedEffect = (String) ReflectionTestUtils.invokeMethod(service, "actionEffect",
                "{\"data\":\"{\\\"message\\\":\\\"nothing\\\"}\"}");
        assertTrue(nestedEffect.contains("nothing"));
        assertEquals("1 B", ReflectionTestUtils.invokeMethod(service, "formatBytes", 1L));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "formatBytes", 1024L)).contains("KiB"));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "formatBytes", 1024L * 1024)).contains("MiB"));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "formatBytes", 1024L * 1024 * 1024)).contains("GiB"));
    }

    @Test
    void hybridClassificationCoversMixedAndNoAssociatedStepLanes() {
        Map<String, Object> temp = step("CLEAN_TEMP");
        Map<String, Object> log = step("CLEAN_LOG");
        Map<String, Object> restart = step("RESTART_SERVICE");
        List<Map<String, Object>> plan = List.of(temp, log, restart);
        when(scoreEngine.score(anyString(), anyMap(), anyString())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            double score = "LogCleanupTool".equals(tool) ? 8.0 : 4.0;
            return new AgenticRiskScoreEngine.ScoreResult(score, Map.of("risk", score), "test");
        });
        Object split = ReflectionTestUtils.invokeMethod(service, "splitByPatrolRisk", plan);
        assertEquals("MIXED", lane("disk", "HYBRID", plan, split, true));
        assertEquals("AUTO", lane("cpu", "HYBRID", List.of(restart), split, true));
        assertEquals("CONFIRM", lane("log_anomaly", "HYBRID", List.of(log), split, true));
        assertEquals("MANUAL", lane("alarm_severity", "HYBRID", List.of(), split, true));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "planHasKind", null, "CLEAN_TEMP"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "stepInBucket", null, temp));
        assertEquals("RESTART_SERVICE|/tmp|sshd",
                ReflectionTestUtils.invokeMethod(service, "stepSignature", restart));
    }

    @Test
    void executeStepsCoversEachWriteToolUnknownKindAndFailures() throws Exception {
        when(cleanTempTool.cleanTempFiles(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":1,\"bytesFreed\":1024,\"businessEffect\":\"EFFECTIVE\"}}");
        when(logCleanupTool.cleanupOldLogs(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":2,\"businessEffect\":\"EFFECTIVE\"}}");
        when(serviceRestartTool.restartService(anyString(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("{\"success\":true,\"data\":{\"businessEffect\":\"RESTARTED\"}}");

        List<Map<String, Object>> steps = List.of(step("CLEAN_TEMP"), step("CLEAN_LOG"),
                step("RESTART_SERVICE"), step("UNKNOWN"));
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "executeSteps", steps, "now",
                Map.of("diskUsagePct", 91.0), 4, "USER_CONFIRMED");
        List<?> actions = (List<?>) result.get("actions");
        assertEquals(4, actions.size());
        assertEquals(2, actions.stream().filter(a -> Boolean.TRUE.equals(((Map<?, ?>) a).get("success"))).count());
        assertNotNull(result.get("timestamp"));
        assertTrue(String.valueOf(result.get("resultSummary")).contains("成功"));

        when(cleanTempTool.cleanTempFiles(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true)))
                .thenThrow(new IllegalStateException("write failed"));
        Map<String, Object> failed = ReflectionTestUtils.invokeMethod(service, "executeSteps",
                List.of(step("CLEAN_TEMP")), "now", Map.of(), 1, "IMMEDIATE");
        Map<?, ?> failedAction = (Map<?, ?>) ((List<?>) failed.get("actions")).get(0);
        assertFalse((Boolean) failedAction.get("success"));
        assertTrue(String.valueOf(failedAction.get("detail")).contains("write failed"));
    }

    @Test
    void confirmPendingRejectsInvalidInputsEmptyPlanAndPartialExecution() throws Exception {
        assertFalse((Boolean) service.confirmPending(null, OpsAutoRemediationService.CONFIRM_CODE, "operator").get("success"));
        assertFalse((Boolean) service.confirmPending("p-1", "wrong", "operator").get("success"));

        ReflectionTestUtils.setField(service, "pendingProposalId", "empty");
        ReflectionTestUtils.setField(service, "pendingSteps", List.of());
        Map<String, Object> empty = service.confirmPending("empty", OpsAutoRemediationService.CONFIRM_CODE, "operator");
        assertFalse((Boolean) empty.get("success"));
        assertNotNull(empty.get("error"));

        ReflectionTestUtils.setField(service, "pendingProposalId", "partial");
        ReflectionTestUtils.setField(service, "pendingExpiresAtMs", System.currentTimeMillis() + 60_000L);
        ReflectionTestUtils.setField(service, "pendingSteps", List.of(step("CLEAN_TEMP")));
        when(cleanTempTool.cleanTempFiles(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("{\"success\":false,\"error\":\"denied\"}");
        Map<String, Object> partial = service.confirmPending("partial", OpsAutoRemediationService.CONFIRM_CODE, "operator");
        assertFalse((Boolean) partial.get("success"));
        assertEquals(1, partial.get("actionCount"));
        assertEquals(0, partial.get("successCount"));
    }

    @Test
    void cooldownGovernanceRiskAndSummaryHelpersCoverBoundaryBranches() {
        List<Map<String, Object>> allKinds = List.of(step("CLEAN_TEMP"), step("CLEAN_LOG"), step("RESTART_SERVICE"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "cooldownAllows", allKinds, 100L));
        ReflectionTestUtils.invokeMethod(service, "armCooldownsForSteps", allKinds, 100L);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "cooldownAllows", allKinds, 100L));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "cooldownAllows", allKinds, 60_100L));

        assertEquals("ALLOW_AUTO", String.valueOf((Object) ReflectionTestUtils.invokeMethod(
                OpsAutoRemediationService.class, "readGovernanceVerdict", step("CLEAN_TEMP"))));
        Map<String, Object> confirm = step("CLEAN_TEMP");
        confirm.put("governanceVerdict", "confirm_only");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isGovernanceAutoAllowed", confirm));
        confirm.put("governanceVerdict", "not-a-verdict");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isGovernanceAutoAllowed", confirm));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAutoExecutionSource", "IMMEDIATE"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isAutoExecutionSource", "HYBRID_AUTO"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "isAutoExecutionSource", "USER_CONFIRMED"));

        Map<String, Object> high = step("CLEAN_TEMP");
        high.put("riskScore", 11.0);
        Map<String, Object> medium = step("CLEAN_LOG");
        medium.put("riskScore", 7.0);
        assertEquals("HIGH", String.valueOf((Object) ReflectionTestUtils.invokeMethod(service, "riskLevelForSteps", List.of(high), "PATROL")));
        assertEquals("MEDIUM", String.valueOf((Object) ReflectionTestUtils.invokeMethod(service, "riskLevelForSteps", List.of(medium), "PATROL")));
        assertEquals("MEDIUM", String.valueOf((Object) ReflectionTestUtils.invokeMethod(service, "riskLevelForSteps", List.of(), "USER_CONFIRMED")));
        assertEquals("LOW", String.valueOf((Object) ReflectionTestUtils.invokeMethod(service, "riskLevelForSteps", null, "PATROL")));

        ReflectionTestUtils.invokeMethod(service, "finalizeSummary", "ts", 90.0, 2,
                List.of(Map.of("success", true)), "IMMEDIATE", "trace", "ok");
        assertEquals("EXECUTED", ((Map<?, ?>) ReflectionTestUtils.getField(service, "lastSummary")).get("status"));
        ReflectionTestUtils.invokeMethod(service, "finalizeSummary", "ts", 90.0, 2,
                List.of(Map.of("success", true), Map.of("success", false)), "IMMEDIATE", "trace", "partial");
        assertEquals("EXECUTION_PARTIAL", ((Map<?, ?>) ReflectionTestUtils.getField(service, "lastSummary")).get("status"));
        ReflectionTestUtils.invokeMethod(service, "finalizeSummary", "ts", 90.0, 2, null, "IMMEDIATE", "trace", "none");
        assertEquals("EXECUTED", ((Map<?, ?>) ReflectionTestUtils.getField(service, "lastSummary")).get("status"));

        PerformanceWebSocketHandler websocket = org.mockito.Mockito.mock(PerformanceWebSocketHandler.class);
        ReflectionTestUtils.setField(service, "performanceWebSocketHandler", websocket);
        ReflectionTestUtils.invokeMethod(service, "broadcastRemediationDone", "ts", Map.of("status", "EXECUTED"));
        verify(websocket).broadcastJson(anyMap());
    }

    private String lane(String code, String mode, List<Map<String, Object>> plan, Object split, boolean diskOk) {
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "classifyRemediationPolicy",
                code, mode, plan, split, diskOk);
        return String.valueOf(result.get("lane"));
    }

    private static Map<String, Object> step(String kind) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("path", "/tmp");
        step.put("days", 7);
        step.put("serviceName", "sshd");
        return step;
    }

    private static Map<String, Object> finding(String code) {
        return new LinkedHashMap<>(Map.of("code", code, "level", "WARN", "title", code, "detail", "detail"));
    }
}
