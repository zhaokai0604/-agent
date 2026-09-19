package com.award.log.mcp.tools;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.AiDiagnosisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogAnalysisToolCoverageTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void analyzesMixedLogsAndBuildsEvidencePayload(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("mixed.log");
        Files.writeString(log, String.join("\n",
                "2026-09-10 10:00:00 host kernel: Out of memory: Kill process 1234",
                "2026-09-10 10:01:00 host sshd[22]: Failed password for admin",
                "2026-09-10 10:02:00 app ERROR connection refused traceid=abc",
                "2026-09-10 10:03:00 db SQL select * from orders where id=1",
                "Information 2026/09/10 10:04:00 service started",
                "plain normal heartbeat"));

        AiDiagnosisService diagnosis = mock(AiDiagnosisService.class);
        when(diagnosis.generateDiagnosisFromFullResult(anyList())).thenReturn("基于证据的根因诊断");
        LogAnalysisTool tool = toolForRoot(tempDir, diagnosis);

        JsonNode root = mapper().readTree(tool.analyzeLogs(log.toString(), 100, null, null));
        JsonNode data = mapper().readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals("SUCCESS", root.path("status").asText());
        assertEquals("INTEGRATED", data.path("integrationStatus").asText());
        assertEquals(6, data.path("linesAnalyzed").asInt());
        assertTrue(data.path("distinctTemplateIds").asInt() > 0);
        assertTrue(data.path("templateSummary").isArray());
        assertTrue(data.path("lineTimeStats").path("linesWithParsedTime").asInt() >= 5);
        assertTrue(data.path("severityHistogram").size() > 0);
        assertTrue(data.path("rootCause").asText().contains("根因"));
        assertTrue(data.path("recommendation").asText().contains("检查"));
    }

    @Test
    void filtersByKeywordTimeWindowAndKeepsLastMaxLines(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("filtered.log");
        String old = "2020-01-01 00:00:00 old ERROR should-drop";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime recent = LocalDateTime.now().minusHours(2);
        Files.writeString(log, String.join("\n", old,
                recent.format(formatter) + " app INFO keep-alpha",
                recent.plusMinutes(1).format(formatter) + " app ERROR keep-beta",
                recent.plusMinutes(2).format(formatter) + " app WARN ignore-gamma"));

        LogAnalysisTool tool = toolForRoot(tempDir, mock(AiDiagnosisService.class));
        JsonNode root = mapper().readTree(tool.analyzeLogs(log.toString(), 1, 24, "alpha|beta"));
        JsonNode data = mapper().readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals(1, data.path("linesAnalyzed").asInt());
        assertTrue(data.path("filters").path("keywordContains").asText().contains("alpha"));
        assertFalse(data.path("anomalySamples").isMissingNode());
    }

    @Test
    void reportsEmptyAndRejectedFiles(@TempDir Path tempDir) throws Exception {
        Path empty = tempDir.resolve("empty.log");
        Files.createFile(empty);
        LogAnalysisTool tool = toolForRoot(tempDir, mock(AiDiagnosisService.class));
        ObjectMapper mapper = mapper();

        JsonNode emptyRoot = mapper.readTree(tool.analyzeLogs(empty.toString(), 10));
        assertTrue(emptyRoot.path("success").asBoolean(false));
        assertEquals("WARN", emptyRoot.path("status").asText());
        assertEquals("EMPTY", mapper.readTree(emptyRoot.path("data").asText()).path("status").asText());

        JsonNode rejected = mapper.readTree(tool.analyzeLogs(tempDir.resolveSibling("outside-log-root").resolve("outside.log").toString(), 10));
        assertFalse(rejected.path("success").asBoolean(true));
        assertFalse(rejected.path("error").asText().isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void helperBranchesHandleNullsLongLinesAndAiFallback(@TempDir Path tempDir) throws Exception {
        AiDiagnosisService diagnosis = mock(AiDiagnosisService.class);
        when(diagnosis.generateDiagnosisFromFullResult(anyList())).thenThrow(new IllegalStateException("offline"));
        LogAnalysisTool tool = toolForRoot(tempDir, diagnosis);

        Path log = tempDir.resolve("long.log");
        Files.writeString(log, "2026-09-10 10:05:00 app ERROR permission denied " + "x".repeat(280));
        JsonNode root = mapper().readTree(tool.analyzeLogs(log.toString(), 100));
        JsonNode data = mapper().readTree(root.path("data").asText());
        assertTrue(data.path("rootCause").asText().contains("进一步排查"));
        assertTrue(data.path("recommendation").asText().contains("权限"));

        MethodInvoker invoker = new MethodInvoker(tool);
        assertTrue(((List<String>) invoker.call("applyLineFilters", null, null, null, 3)).isEmpty());
        assertTrue((Boolean) invoker.call("matchesKeywordLine", "error happened", "error|warning"));
        assertFalse((Boolean) invoker.call("matchesKeywordLine", "normal", "error|warning"));
        assertTrue((Boolean) invoker.call("matchesKeywordLine", "normal", " "));

        EnhancedLogParseResultEntity parsed = new EnhancedLogParseResultEntity("line");
        parsed.setTemplateId("T1");
        parsed.setAnomalyScore(1.234);
        parsed.setTimestamp(0L);
        List<Map<String, Object>> samples = (List<Map<String, Object>>) invoker.call(
                "buildAnomalySamples", List.of(parsed), 1);
        assertEquals("T1", samples.get(0).get("templateId"));
        Map<String, Object> stats = (Map<String, Object>) invoker.call("buildLineTimeStats", List.of(parsed));
        assertEquals(0, stats.get("linesWithParsedTime"));
        assertNotNull(invoker.call("buildSeverityHistogram", List.of(parsed)));
    }

    private LogAnalysisTool toolForRoot(Path root, AiDiagnosisService diagnosis) {
        OpsPathPolicy policy = McpToolsTestFixtures.opsPathPolicy();
        String rootPath = root.toAbsolutePath().toString();
        policy.updateEditablePathLists(com.award.log.util.OsRuntime.isWindows(),
                List.of(rootPath), List.of(rootPath), List.of());
        return new LogAnalysisTool(executor, mapper(), new com.award.log.analyzer.DrainPlusParser(8, 50),
                diagnosis, McpToolsTestFixtures.minPrivilegeExecutor(executor), policy,
                new com.award.log.analyzer.LogCleaner(), new com.award.log.agent.DrainTemplateNoveltyTracker());
    }

    private static ObjectMapper mapper() {
        return McpToolsTestFixtures.objectMapper();
    }

    private static final class MethodInvoker {
        private final Object target;

        private MethodInvoker(Object target) {
            this.target = target;
        }

        private Object call(String name, Object... args) throws Exception {
            for (var method : target.getClass().getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(name);
        }
    }
}
