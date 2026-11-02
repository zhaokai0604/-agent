package com.award.log.agent;

import com.award.log.agent.awm.WorkflowExecutionService;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.OpsWorkflowStep;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ProcessTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.agent.skill.SkillCatalogLoader;
import com.award.log.agent.skill.SkillRoutingAdvisor;
import com.award.log.util.TestRuntimePlatform;
import com.award.log.util.TestTimeSource;
import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantOrchestratorSummaryCoverageTest {

    @Mock private DiskTool diskTool;
    @Mock private DiskAnalyzeTool diskAnalyzeTool;
    @Mock private LogCleanupTool logCleanupTool;
    @Mock private CleanTempTool cleanTempTool;
    @Mock private SystemLoadTool systemLoadTool;
    @Mock private ProcessTool processTool;
    @Mock private ServiceRestartTool serviceRestartTool;
    @Mock private OpsRemediationGate opsRemediationGate;
    @Mock private OpsPathPolicy opsPathPolicy;
    @Mock private ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    @Mock private AssistantAuditRecorder auditRecorder;
    @Mock private OpsIntentRouter opsIntentRouter;
    @Mock private WorkflowRetriever workflowRetriever;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowInductionService workflowInductionService;
    @Mock private OpsAutoRemediationService opsAutoRemediationService;

    private AssistantOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AssistantOrchestrator(
                diskTool, diskAnalyzeTool, logCleanupTool, cleanTempTool, systemLoadTool, processTool,
                serviceRestartTool, opsRemediationGate, opsPathPolicy, serviceRestartCandidateResolver,
                auditRecorder, new ObjectMapper(), opsIntentRouter, workflowRetriever,
                workflowExecutionService, workflowInductionService, new TestRuntimePlatform(false),
                new TestTimeSource(1_500L), new TestTraceIdGenerator("trace-summary"),
                new DiagnosisConfidenceGate(), new SkillRoutingAdvisor(new SkillCatalogLoader()));
        com.award.log.governance.GovernedPipelineFixture.install(orchestrator, cleanTempTool, logCleanupTool, serviceRestartTool);
        ReflectionTestUtils.setField(orchestrator, "logCleanDays", 30);
        ReflectionTestUtils.setField(orchestrator, "tempCleanDays", 7);
        ReflectionTestUtils.setField(orchestrator, "diskPressurePercent", 80.0);
        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
        lenient().when(opsPathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of("/var/log"));
        lenient().when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
    }

    @Test
    void diskAndHotspotSummariesCoverTextualArraysErrorsAndEmptyData() {
        String analyze = "{\"success\":true,\"data\":{\"multiDrive\":["
                + "{\"drive\":\"C:\",\"hotspots\":{\"entries\":["
                + "{\"path\":\"C:/cache|one\",\"kb\":2048},"
                + "{\"path\":\"C:/cache-two\",\"kb\":1024},"
                + "{\"path\":\"C:/cache-three\",\"kb\":512},"
                + "{\"path\":\"C:/cache-four\",\"kb\":256}]}},"
                + "{\"root\":\"D:\",\"hotspotsError\":\"access denied\"}]}}";
        String multi = callString("summarizeDiskAnalyze", analyze);
        assertTrue(multi.contains("C:"));
        assertTrue(multi.contains("access denied"));
        assertTrue(multi.contains("等 4 项"));

        assertEquals("- **磁盘热点扫描失败：** boom",
                callString("summarizeDiskAnalyze", "{\"success\":false,\"error\":\"boom\"}"));
        assertEquals("- 已完成磁盘热点扫描，未发现明显热点目录。",
                callString("summarizeDiskAnalyze", "{\"success\":true,\"data\":{}}"));
        assertEquals("无热点", callString("summarizeHotspotsBrief", (Object) null));
        assertEquals("未发现明显大目录", callString("summarizeHotspotsBrief", node("{\"entries\":[]}")));

        String disk = callString("summarizeDisk", "{\"success\":true,\"data\":\""
                + "[{\\\"filesystem\\\":\\\"/dev/sda\\\",\\\"usage\\\":\\\"88%\\\","
                + "\\\"used\\\":\\\"2048\\\",\\\"available\\\":\\\"1048576\\\"}]\"}");
        assertTrue(disk.contains("/dev/sda"));
        assertTrue(disk.contains("88%"));
        assertTrue(callString("summarizeDisk", "not-json").contains("未提取到"));
    }

    @Test
    void loadAndProcessSummariesCoverThresholdsAndFallbacks() {
        assertTrue((Boolean) call("loadSignalsHigh",
                "{\"success\":true,\"data\":{\"cpuUsagePercent\":\"76%\"}}"));
        assertTrue((Boolean) call("loadSignalsHigh",
                "{\"success\":true,\"data\":{\"loadAverage1m\":4.2}}"));
        assertFalse((Boolean) call("loadSignalsHigh", "{\"success\":false,\"error\":\"down\"}"));

        String load = callString("summarizeSystemLoad",
                "{\"success\":true,\"data\":{\"cpu\":91,\"mem\":65.5,\"load1\":4.25}}");
        assertTrue(load.contains("CPU 使用率") && load.contains("91.0%"));
        assertTrue(load.contains("1 分钟负载") && load.contains("4.25"));
        assertTrue(callString("summarizeSystemLoad", "{\"success\":false,\"error\":\"timeout\"}")
                .contains("timeout"));
        assertTrue(callString("summarizeSystemLoad", "{\"success\":true,\"data\":{}}")
                .contains("未发现可展示"));

        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) rows.append(',');
            rows.append("{\"command\":\"worker-").append(i)
                    .append("\",\"pid\":\"").append(100 + i)
                    .append("\",\"cpu\":\"").append(i == 0 ? "35" : "n/a")
                    .append("\",\"memPct\":\"").append(i == 1 ? "7.5%" : "")
                    .append("\",\"etime\":\"1:02\",\"state\":\"")
                    .append(i == 2 ? "ZOMBIE" : "RUNNING").append("\"}");
        }
        rows.append(']');
        String process = "{\"success\":true,\"data\":" + rows + "}";
        assertTrue((Boolean) call("processSignalsHigh", process));
        String processMarkdown = callString("summarizeProcessPressure", process);
        assertTrue(processMarkdown.contains("高占用进程"));
        assertTrue(processMarkdown.contains("其余 2 个"));
        assertTrue(processMarkdown.contains("僵尸进程"));
        assertFalse((Boolean) call("processSignalsHigh", "{\"success\":false,\"error\":\"bad\"}"));
        assertTrue(callString("summarizeProcessPressure", "{\"success\":true,\"data\":[]}")
                .contains("未发现 CPU"));
        assertTrue(callString("summarizeProcessPressure", "{\"success\":false,\"error\":\"ps failed\"}")
                .contains("ps failed"));
    }

    @Test
    void workflowSummaryAndPrimitiveBranchesAreExplicit() throws Exception {
        WorkflowExecutionService.StepResult success = new WorkflowExecutionService.StepResult(
                "DiskTool", "READ", true, false, false, false, "disk read ok", Map.of(), "raw");
        WorkflowExecutionService.StepResult emptyMessage = new WorkflowExecutionService.StepResult(
                "ProcessTool", "READ", true, false, false, false, "", Map.of(), "raw");

        assertEquals("", callString("buildWorkflowResultSummary", (Object) null));
        assertTrue(callString("buildWorkflowResultSummary", new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(success), false, false, false, true, false)).contains("中断"));
        assertTrue(callString("buildWorkflowResultSummary", new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(success, emptyMessage), true, false, false, false, false))
                .contains("真实执行动作"));
        assertTrue(callString("buildWorkflowResultSummary", new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(success), false, true, false, false, false)).contains("预览步骤"));
        assertTrue(callString("buildWorkflowResultSummary", new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(success), false, false, true, false, false)).contains("只读诊断"));
        assertTrue(callString("buildWorkflowResultSummary", new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(), false, false, false, false, false)).contains("未形成"));

        assertEquals(82.5, (Double) call("extractMaxDiskUsePercent",
                "{\"success\":true,\"data\":[{\"usage\":\"82.5%\"},{\"usePercent\":\"70%\"}]}"), 0.01);
        assertEquals(0.0, (Double) call("extractMaxDiskUsePercent", "not-json"), 0.01);
        assertEquals(25.0, (Double) call("parsePercent", "25%"), 0.01);
        assertEquals(0.0, (Double) call("parsePercent", "bad"), 0.01);
        assertEquals(7, (Integer) call("asInt", "7", 1));
        assertEquals(1, (Integer) call("asInt", "bad", 1));
        assertEquals("未采集", callStaticString("formatProcessPercent", "n/a"));
        assertEquals("12.3%", callStaticString("formatProcessPercent", "12.3"));
        assertEquals("a\\|b", callStaticString("escapeTableCell", "a|b"));
    }

    @Test
    void routingAndMetaHelpersCoverNullEmptyAndReadOnlyPaths() {
        assertFalse(orchestrator.supports(null));
        assertFalse(orchestrator.supports("  "));
        for (OpsIntentRouter.Playbook playbook : OpsIntentRouter.Playbook.values()) {
            org.mockito.Mockito.when(opsIntentRouter.resolve("message")).thenReturn(playbook);
            boolean expected = playbook == OpsIntentRouter.Playbook.DISK_CLEANUP
                    || playbook == OpsIntentRouter.Playbook.CPU_PRESSURE
                    || playbook == OpsIntentRouter.Playbook.PATROL_CONTINUATION;
            assertEquals(expected, orchestrator.supports("message"));
        }

        org.mockito.Mockito.when(opsIntentRouter.resolve("disk read")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        AssistantOrchestrator.RunResult diskRead = orchestrator.run("disk read", McpToolSurface.READ_ONLY, RiskLevel.LOW);
        assertTrue(diskRead.markdown().contains("只读模式"));
        org.mockito.Mockito.when(opsIntentRouter.resolve("cpu read")).thenReturn(OpsIntentRouter.Playbook.CPU_PRESSURE);
        assertTrue(orchestrator.run("cpu read", McpToolSurface.READ_ONLY, RiskLevel.MEDIUM)
                .markdown().contains("只读模式"));
        org.mockito.Mockito.when(opsIntentRouter.resolve("patrol read")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);
        assertTrue(orchestrator.run("patrol read", McpToolSurface.READ_ONLY, RiskLevel.HIGH)
                .markdown().contains("只读模式"));

        assertEquals("EXECUTED", callStaticString("resolveSecurityOutcome", true, null));
        assertEquals("DIAGNOSED", callStaticString("resolveSecurityOutcome",
                false, new WorkflowExecutionService.WorkflowRunResult(null, List.of(), true, false, false, false, false)));
        assertEquals("PREVIEW", callStaticString("resolveSecurityOutcome", false, null));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(orchestrator, "buildReadOnlyReply", "trace-x"))
                .contains("只读模式"));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(orchestrator, "buildReadOnlyReply", (Object) null))
                .contains("`-`"));
        assertEquals("/var/log", callString("defaultLogRoot"));
        assertEquals("/tmp", callString("defaultTempRoot"));
        assertTrue((Boolean) callStatic("sameWindowsPath", "C:\\Temp", "c:/temp"));
        assertFalse((Boolean) callStatic("sameWindowsPath", null, "c:/temp"));
        assertTrue((Boolean) callStatic("mentionsTemp", "垃圾 temp"));
        assertFalse((Boolean) callStatic("mentionsTemp", "hello"));
        assertTrue((Boolean) callStatic("mentionsLog", "系统日志"));
        assertFalse((Boolean) callStatic("mentionsLog", "hello"));
    }

    @Test
    void workflowCollectionsAndPatrolContinuationCoverPreviewAndEmptyStates() {
        WorkflowExecutionService.StepResult good = new WorkflowExecutionService.StepResult(
                "DiskTool", "READ", true, false, false, false, "ok", Map.of(), "raw");
        WorkflowExecutionService.StepResult blankTool = new WorkflowExecutionService.StepResult(
                " ", "READ", true, false, false, false, "", Map.of(), "raw");
        WorkflowExecutionService.StepResult failed = new WorkflowExecutionService.StepResult(
                "FailTool", "READ", false, false, false, false, "bad", Map.of(), "raw");
        WorkflowExecutionService.WorkflowRunResult run = new WorkflowExecutionService.WorkflowRunResult(
                null, List.of(good, blankTool, failed), true, false, false, false, false);
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        callStatic("collectToolsFromWorkflow", run, tools);
        assertEquals(Set.of("DiskTool"), tools);
        callStatic("collectToolsFromWorkflow", null, tools);
        callStatic("collectToolsFromWorkflow", run, null);

        OpsWorkflow hit = new OpsWorkflow("wf-meta", "disk", List.of("DISK_PRESSURE"),
                "磁盘", "desc", List.of(OpsWorkflowStep.of("s", "检查", "DiskTool")),
                "TEST", "trace", 1, true);
        Map<String, Object> meta = call("buildStreamMeta", "trace", "PREVIEW", hit,
                new WorkflowExecutionService.WorkflowRunResult(null, List.of(good), true, false, false, false, false),
                new java.util.LinkedHashSet<>(List.of("ExtraTool")));
        assertEquals("wf-meta", meta.get("awmWorkflowId"));
        assertEquals(1, meta.get("awmStepsOk"));
        assertTrue(((List<?>) meta.get("toolsUsed")).contains("ExtraTool"));

        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", null);
        AssistantOrchestrator.RunResult noAuto = call("runPatrolContinuation", "继续处理",
                McpToolSurface.FULL, RiskLevel.LOW,"unavailable-trace");
        assertEquals("自动修复服务未启用。", noAuto.markdown());
        assertEquals("服务重启", callStaticString("actionLabel", "ServiceRestartTool"));
        assertEquals("临时文件清理", callStaticString("actionLabel", "CleanTempTool"));
        assertEquals("日志清理", callStaticString("actionLabel", "LogCleanupTool"));
        assertEquals("进程处置", callStaticString("actionLabel", "ProcessOpsTool"));
        assertEquals("处置动作", callStaticString("actionLabel", "unknown"));
        assertEquals("未返回可展示的执行明细。", invokeStaticObject("summarizeExecutionActions", null));
        assertTrue(callStaticString("summarizeExecutionActions", List.of("raw", Map.of(
                "tool", "CleanTempTool", "path", "/tmp", "success", true))).contains("成功"));
    }

    @Test
    void multiDriveTempCleanupCoversScanSkipPolicyFailureAndVerifiedDelete() throws Exception {
        when(cleanTempTool.scanAllTempJunk(0)).thenReturn("{\"success\":true,\"data\":{"
                + "\"totalFilesFound\":7,\"drives\":[\"C:\",\"D:\"],\"locations\":["
                + "{\"drive\":\"C:\",\"path\":\"/tmp/error\",\"error\":\"access denied\"},"
                + "{\"drive\":\"C:\",\"path\":\"/tmp/empty\",\"filesFound\":0,\"autoCleanEligible\":true},"
                + "{\"drive\":\"C:\",\"path\":\"/tmp/skip\",\"filesFound\":2,\"autoCleanEligible\":false,\"skipReason\":\"system temp\"},"
                + "{\"drive\":\"D:\",\"path\":\"/tmp/blocked\",\"filesFound\":2,\"autoCleanEligible\":true},"
                + "{\"drive\":\"D:\",\"path\":\"/tmp/fail\",\"filesFound\":1,\"autoCleanEligible\":true},"
                + "{\"drive\":\"D:\",\"path\":\"/tmp/ok\",\"filesFound\":2,\"autoCleanEligible\":true}"
                + "]}}");
        when(opsRemediationGate.decideTempCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0, String.class);
                    if ("/tmp/blocked".equals(path)) {
                        return new OpsRemediationGate.RemediationDecision(
                                OpsRemediationGate.WriteDecision.PREVIEW,
                                com.award.log.governance.GovernanceAdmissionVerdict.CONFIRM_ONLY,
                                com.award.log.security.OpsTrustTier.APPROVE, "需要确认");
                    }
                    return new OpsRemediationGate.RemediationDecision(
                            OpsRemediationGate.WriteDecision.EXECUTE,
                            com.award.log.governance.GovernanceAdmissionVerdict.ALLOW_AUTO,
                            com.award.log.security.OpsTrustTier.AUTO, "允许执行");
                });
        when(cleanTempTool.cleanTempFiles("/tmp/fail", 0, false, true, false))
                .thenReturn("{\"success\":false,\"error\":\"delete failed\"}");
        when(cleanTempTool.cleanTempFiles("/tmp/ok", 0, false, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":2,\"bytesFreed\":2048,"
                        + "\"protectedFilesPreserved\":true,\"businessEffect\":\"DELETED\"}}");

        StringBuilder markdown = new StringBuilder();
        boolean result = (Boolean) ReflectionTestUtils.invokeMethod(orchestrator,
                "appendMultiDriveTempCleanup", markdown, new ArrayList<Map<String, Object>>(), true, "直接删除");
        assertTrue(result);
        assertTrue(markdown.toString().contains("扫描失败"));
        assertTrue(markdown.toString().contains("无文件"));
        assertTrue(markdown.toString().contains("system temp"));
        assertTrue(markdown.toString().contains("delete failed"));
        assertTrue(markdown.toString().contains("已删 2"));
    }

    @Test
    void explicitTempCleanupCoversPreviewFailureNoOpPolicyAndVerifiedExecution() throws Exception {
        String previewFail = "/tmp/preview-fail";
        String noFiles = "/tmp/no-files";
        String forbidden = "/tmp/forbidden";
        String waiting = "/tmp/waiting";
        String zeroDeleted = "/tmp/zero-deleted";
        String verified = "/tmp/verified";

        when(opsRemediationGate.decideTempCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0, String.class);
                    OpsRemediationGate.WriteDecision decision = forbidden.equals(path)
                            ? OpsRemediationGate.WriteDecision.FORBIDDEN
                            : waiting.equals(path)
                            ? OpsRemediationGate.WriteDecision.PREVIEW
                            : OpsRemediationGate.WriteDecision.EXECUTE;
                    com.award.log.governance.GovernanceAdmissionVerdict verdict =
                            decision == OpsRemediationGate.WriteDecision.FORBIDDEN
                                    ? com.award.log.governance.GovernanceAdmissionVerdict.FORBIDDEN
                                    : decision == OpsRemediationGate.WriteDecision.PREVIEW
                                    ? com.award.log.governance.GovernanceAdmissionVerdict.CONFIRM_ONLY
                                    : com.award.log.governance.GovernanceAdmissionVerdict.ALLOW_AUTO;
                    return new OpsRemediationGate.RemediationDecision(
                            decision, verdict, com.award.log.security.OpsTrustTier.AUTO, "策略结论：" + path);
                });

        when(cleanTempTool.cleanTempFiles(previewFail, 7, true, false, false))
                .thenReturn("{\"success\":false,\"error\":\"preview unavailable\"}");
        when(cleanTempTool.cleanTempFiles(noFiles, 7, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":0}}");
        when(cleanTempTool.cleanTempFiles(forbidden, 7, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        when(cleanTempTool.cleanTempFiles(waiting, 7, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        when(cleanTempTool.cleanTempFiles(zeroDeleted, 7, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        when(cleanTempTool.cleanTempFiles(verified, 7, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        when(cleanTempTool.cleanTempFiles(zeroDeleted, 7, false, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":0,\"businessEffect\":\"NO_EFFECT\"}}");
        when(cleanTempTool.cleanTempFiles(verified, 7, false, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":2,\"bytesFreed\":2048,"
                        + "\"protectedFilesPreserved\":true,\"businessEffect\":\"DELETED\"}}");

        StringBuilder markdown = new StringBuilder();
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + previewFail));
        assertTrue(markdown.toString().contains("preview unavailable"));

        markdown.setLength(0);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + noFiles));
        assertTrue(markdown.toString().contains("无可清理文件"));

        markdown.setLength(0);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + forbidden));
        assertTrue(markdown.toString().contains("已拒绝执行"));

        markdown.setLength(0);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + waiting));
        assertTrue(markdown.toString().contains("等待您回复"));

        markdown.setLength(0);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + zeroDeleted));
        assertTrue(markdown.toString().contains("未删除到文件"));

        markdown.setLength(0);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(orchestrator, "appendTempCleanupSection",
                markdown, new ArrayList<Map<String, Object>>(), false, "清理 " + verified));
        assertTrue(markdown.toString().contains("已执行删除"));
    }

    @Test
    void patrolContinuationCoversPendingPreviewSuccessAndFailureClosure() {
        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", opsAutoRemediationService);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("hasPending", true);
        pending.put("proposalId", "proposal-1");
        pending.put("summary", "需要确认的巡检修复");
        Map<String, Object> reversibleRecover = Map.of(
                "reversible", true, "recoverTool", "RecycleBin", "recoverHint", "可恢复");
        Map<String, Object> irreversibleRecover = Map.of(
                "reversible", false, "irreversibilityNote", "删除不可逆");
        List<Map<String, Object>> actions = List.of(
                Map.of("tool", "CleanTempTool", "success", true,
                        "verifyOutcome", "VERIFIED", "remediationClosed", true,
                        "recover", reversibleRecover),
                Map.of("tool", "ServiceRestartTool", "success", false,
                        "recover", irreversibleRecover));
        Map<String, Object> success = Map.of(
                "success", true, "resultSummary", "执行并验证完成", "actions", actions);
        Map<String, Object> failure = Map.of(
                "success", false, "error", "验证未通过", "resultSummary", "执行未闭环",
                "actions", List.of(Map.of("tool", "CleanTempTool", "success", false)));

        when(opsAutoRemediationService.getPendingProposalView()).thenReturn(pending, pending);
        when(opsAutoRemediationService.confirmPendingFromAssistant("proposal-1"))
                .thenReturn(success, failure);
        lenient().when(opsIntentRouter.forceRemediate(anyString()))
                .thenAnswer(invocation -> "确认执行".equals(invocation.getArgument(0, String.class)));

        AssistantOrchestrator.RunResult preview = (AssistantOrchestrator.RunResult)
                ReflectionTestUtils.invokeMethod(orchestrator, "runPatrolContinuation",
                        "继续处理", McpToolSurface.FULL, RiskLevel.MEDIUM,"patrol-summary-trace");
        assertTrue(preview.markdown().contains("待确认方案"));

        AssistantOrchestrator.RunResult executed = (AssistantOrchestrator.RunResult)
                ReflectionTestUtils.invokeMethod(orchestrator, "runPatrolContinuation",
                        "确认执行", McpToolSurface.FULL, RiskLevel.MEDIUM,"patrol-summary-trace");
        assertTrue(executed.markdown().contains("执行完成且验证通过"));
        assertTrue(executed.markdown().contains("步骤验证"));

        AssistantOrchestrator.RunResult failed = (AssistantOrchestrator.RunResult)
                ReflectionTestUtils.invokeMethod(orchestrator, "runPatrolContinuation",
                        "确认执行", McpToolSurface.FULL, RiskLevel.MEDIUM,"patrol-summary-trace");
        assertTrue(failed.markdown().contains("未完全闭环"));
        assertTrue(failed.markdown().contains("验证未通过"));
    }

    private JsonNode node(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T call(String name, Object... args) {
        return (T) ReflectionTestUtils.invokeMethod(orchestrator, name, args);
    }

    private String callString(String name, Object... args) {
        return call(name, args);
    }

    @SuppressWarnings("unchecked")
    private <T> T callStatic(String name, Object... args) {
        return (T) ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, name, args);
    }

    private String callStaticString(String name, Object... args) {
        return callStatic(name, args);
    }

    private Object invokeStaticObject(String name, Object arg) {
        try {
            java.lang.reflect.Method method = AssistantOrchestrator.class.getDeclaredMethod(name, Object.class);
            method.setAccessible(true);
            return method.invoke(null, arg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
