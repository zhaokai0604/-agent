package com.award.log.mcp.tools;

import com.award.log.agent.OpsAnalysisSkillCatalog;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.AiAnalysisService;
import com.award.log.service.OpsRecycleBinService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 低覆盖 MCP 工具的行为契约：每个用例都走真实参数归一化、结果封装或安全拒绝路径。
 */
class McpToolsExtendedCoverageTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void firewallWrapsWindowsSuccessAndFailure() throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        FirewallTool tool = new FirewallTool(executor, new ObjectMapper(), min);
        when(min.executeSafely(anyList(), anyLong()))
                .thenReturn(result(true, "Domain Profile: State ON", null));
        JsonNode success = json(tool.checkFirewallStatus());
        assertTrue(success.path("success").asBoolean(false));

        when(min.executeSafely(anyList(), anyLong()))
                .thenReturn(result(false, "partial", "netsh unavailable"));
        JsonNode failure = json(tool.checkFirewallStatus());
        assertTrue(failure.path("success").asBoolean(false));
        assertEquals("WARN", failure.path("status").asText());
    }

    @Test
    void sslCertificateValidatesInputBeforeReadOnlyHandshake() throws Exception {
        SslCertTool tool = new SslCertTool(new ObjectMapper());
        assertFalse(json(tool.checkSslCertificate(null, null, null)).path("success").asBoolean(true));
        assertFalse(json(tool.checkSslCertificate("bad host!", 443, 1000)).path("success").asBoolean(true));
        assertFalse(json(tool.checkSslCertificate("127.0.0.1", 1, 1000)).path("success").asBoolean(true));
        assertFalse(json(tool.checkSslCertificate("localhost", 65535, 1000)).path("success").asBoolean(true));
    }

    @Test
    void privilegeToolCoversInvalidMissingExistingAndUnknownCommand(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = McpToolsTestFixtures.opsPathPolicy();
        PrivilegeTool tool = new PrivilegeTool(executor, new ObjectMapper(), min, policy);
        when(min.executeSafely(anyList())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("whoami")) {
                return result(true, "agent-user", null);
            }
            return result(false, "", "not found");
        });

        assertFalse(json(tool.checkPrivilege(" ", "read")).path("success").asBoolean(true));
        assertFalse(json(tool.checkPrivilege("ping", "invalid")).path("success").asBoolean(true));
        JsonNode missing = json(tool.checkPrivilege("C:/Temp/not-present-threshcore", "read"));
        assertTrue(missing.path("success").asBoolean(false));
        assertEquals("WARN", missing.path("status").asText());

        Path existing = Files.writeString(root.resolve("probe.txt"), "probe");
        policy.updateEditablePathLists(true, List.of(root.toString()), List.of(root.toString()), List.of());
        JsonNode existingResult = json(tool.checkPrivilege(existing.toString(), "read"));
        assertTrue(existingResult.path("success").asBoolean(false));

        when(min.executeSafely(anyList())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("whoami")) {
                return result(true, "agent-user", null);
            }
            return result(false, "", "where failed");
        });
        JsonNode unknown = json(tool.checkPrivilege("command-that-is-not-installed", "execute"));
        assertTrue(unknown.path("success").asBoolean(false));
        assertTrue(unknown.path("data").asText().contains("命令不存在"));
    }

    @Test
    void diskToolCoversWindowsJsonFallbackAndPolicyBranches(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = McpToolsTestFixtures.opsPathPolicy();
        policy.updateEditablePathLists(true, List.of(root.toString()), List.of(root.toString()), List.of());
        DiskTool tool = new DiskTool(executor, new ObjectMapper(), min, policy);

        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(true,
                "[{\"Name\":\"C\",\"Used\":50,\"Free\":50,\"Size\":100}]", null));
        assertTrue(json(tool.checkDiskUsage()).path("success").asBoolean(false));

        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(true, "not-json", null));
        assertTrue(json(tool.checkDiskUsage()).path("success").asBoolean(false));

        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(false, "", "df failed"));
        assertFalse(json(tool.checkDiskUsage()).path("success").asBoolean(true));

        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(true,
                "{\"kb\":12,\"path\":\"C:/Temp/a\"}", null));
        JsonNode rank = json(tool.rankDiskUsageUnderPath(root.toString(), 0, 100));
        assertTrue(rank.path("success").asBoolean(false));
        assertEquals(1, json(rank.path("data").asText()).path("entries").size());
        assertFalse(json(tool.rankDiskUsageUnderPath("C:/not-allowed-threshcore", 2, 5))
                .path("success").asBoolean(true));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> parsed = (List<Map<String, String>>) ReflectionTestUtils.invokeMethod(
                tool, "parseOutput", "Filesystem Size Used Avail Use% Mounted on\nC 100G 50G 50G 50% C:/\ninvalid");
        assertEquals(1, parsed.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> du = (List<Map<String, Object>>) ReflectionTestUtils.invokeMethod(
                tool, "parseDuLines", "12\t/tmp/a\nnot-a-row\n5 /tmp/b", 1);
        assertEquals(1, du.size());
        assertEquals(Integer.valueOf(50), ReflectionTestUtils.invokeMethod(DiskTool.class, "parseUsePercent", "50%"));
        assertEquals(Integer.valueOf(0), ReflectionTestUtils.invokeMethod(DiskTool.class, "parseUsePercent", "bad"));
    }

    @Test
    void systemLoadToolUsesDashboardFallbackAndHandlesMissingMetrics() throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        com.award.log.service.StatisticsService stats = mock(com.award.log.service.StatisticsService.class);
        when(stats.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", 21.5, "memoryUsage", 44.25));
        SystemLoadTool tool = new SystemLoadTool(executor, new ObjectMapper(), min, stats);
        JsonNode response = json(tool.checkSystemLoad());
        assertTrue(response.path("success").asBoolean(false));
        JsonNode data = json(response.path("data").asText());
        assertEquals(21.5, data.path("cpuUsagePercent").asDouble());
        assertEquals(44.3, data.path("memUsagePercent").asDouble());

        when(stats.getSystemPerformance(null)).thenReturn(Map.of("cpuUsage", -1, "memoryUsage", 101));
        when(min.executeSafely(anyList())).thenReturn(result(true, "unparseable", null));
        assertTrue(json(tool.checkSystemLoad()).path("success").asBoolean(false));
        assertEquals(42.5, ReflectionTestUtils.invokeMethod(tool, "parseDoubleOrDefault", "42.5", 0.0));
        assertEquals(7.0, ReflectionTestUtils.invokeMethod(tool, "parseDoubleOrDefault", "bad", 7.0));
        assertEquals(7.0, ReflectionTestUtils.invokeMethod(tool, "parseDoubleOrDefault", null, 7.0));
    }

    @Test
    void k8sGatewayNormalizesOperationsAndReturnsCommandFailure() throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        K8sTool tool = new K8sTool(min, new ObjectMapper());
        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(true, "pods", null));
        assertTrue(json(tool.k8sDiagnose(null, " ")).path("success").asBoolean(false));
        assertTrue(json(tool.k8sDiagnose("get-nodes", null)).path("success").asBoolean(false));
        assertTrue(json(tool.k8sDiagnose("get-events", "ops")).path("success").asBoolean(false));
        when(min.executeSafely(anyList(), anyLong())).thenReturn(result(false, "", "kubectl unavailable"));
        assertFalse(json(tool.k8sDiagnose("get-pods", "default")).path("success").asBoolean(true));
    }

    @Test
    void opsAnalysisAndRecycleBinExposeReadOnlyAndRecoveryContracts(@TempDir Path root) throws Exception {
        AiAnalysisService analysis = mock(AiAnalysisService.class);
        when(analysis.listAnalysisSkills()).thenReturn(List.of(Map.of("id", "log_analysis")));
        when(analysis.invokeSkill("log_analysis", Map.of("content", "ERROR timeout")))
                .thenReturn("根因待验证");
        OpsAnalysisSkillTool skillTool = new OpsAnalysisSkillTool(analysis, new ObjectMapper());
        assertTrue(json(skillTool.listOpsAnalysisSkills()).path("success").asBoolean(false));
        assertFalse(json(skillTool.invokeOpsAnalysisSkill(" ", null, null, null, null, null, null))
                .path("success").asBoolean(true));
        assertTrue(json(skillTool.invokeOpsAnalysisSkill("log_analysis", "ERROR timeout", "", null,
                null, null, null)).path("success").asBoolean(false));
        assertFalse(json(skillTool.invokeOpsAnalysisSkill("missing", "x", null, null, null, null, null))
                .path("success").asBoolean(true));
        assertTrue(OpsAnalysisSkillCatalog.find("LOG_ANALYSIS").isPresent());

        OpsRecycleBinService recycle = mock(OpsRecycleBinService.class);
        Path original = Files.writeString(root.resolve("restore.log"), "restore");
        when(recycle.listEntries()).thenReturn(List.of(Map.of(
                "handle", "abc123def456", "originalPath", original.toString())));
        when(recycle.restore("abc123def456")).thenReturn(true);
        RecycleBinTool recycleTool = new RecycleBinTool(recycle, new ObjectMapper());
        assertTrue(json(recycleTool.recycleBinOps("list", null)).path("success").asBoolean(false));
        assertTrue(json(recycleTool.recycleBinOps("restore", "abc123def456"))
                .path("success").asBoolean(false));
        assertTrue(json(recycleTool.recycleBinOps("purge", null)).path("success").asBoolean(false));
        assertFalse(json(recycleTool.recycleBinOps("unknown", null)).path("success").asBoolean(true));
        when(recycle.listEntries()).thenThrow(new RuntimeException("recycle unavailable"));
        assertFalse(json(recycleTool.recycleBinOps("list", null)).path("success").asBoolean(true));
    }

    private static AbstractCommandExecutor.CommandResult result(boolean success, String output, String error) {
        return new AbstractCommandExecutor.CommandResult(success, output, error, success ? 0 : 1, false);
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
