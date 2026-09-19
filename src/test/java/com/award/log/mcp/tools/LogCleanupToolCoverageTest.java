package com.award.log.mcp.tools;

import com.award.log.config.OpsRemediationProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.LogSafetyClassifier;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.SafeDeleteSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogCleanupToolCoverageTest {

    private ExecutorService toolExecutor;

    @AfterEach
    void tearDown() {
        if (toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
    }

    @Test
    void scalarCandidateIsParsedButNonRecycleSuccessIsNotCounted(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        LogSafetyClassifier classifier = mock(LogSafetyClassifier.class);
        SafeDeleteSupport delete = mock(SafeDeleteSupport.class);
        Path candidate = Files.writeString(root.resolve("one.log"), "log");
        when(policy.isAllowedLogCleanupPath(anyString())).thenReturn(true);
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(
                "\"" + candidate.toString().replace("\\", "\\\\") + "\""));
        when(classifier.filterDeletable(anyList())).thenReturn(new LogSafetyClassifier.FilterResult(
                List.of(candidate.toString()), List.of()));
        when(delete.deleteFile(any(Path.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new SafeDeleteSupport.DeleteOutcome("DELETE", true, "", candidate.toString(), ""));

        JsonNode response = json(tool(min, policy, classifier, delete)
                .cleanupOldLogs(root.toString(), 1, false, true));
        assertFalse(response.path("success").asBoolean(true));
        assertTrue(response.path("error").asText().contains("未删除"));
    }

    @Test
    void malformedAndFailedWindowsScansReturnSafeEmptyResponses(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        LogSafetyClassifier classifier = mock(LogSafetyClassifier.class);
        when(policy.isAllowedLogCleanupPath(anyString())).thenReturn(true);
        when(min.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(false, "not-json", "scan failed", 1, false));
        JsonNode failedScan = json(tool(min, policy, classifier, null)
                .cleanupOldLogs(root.toString(), 30, true, false));
        assertTrue(failedScan.path("success").asBoolean(false));
        assertEquals(0, json(failedScan.path("data").asText()).path("filesFound").asInt());

        Path missing = root.resolve("missing-log-root");
        JsonNode quickSkipped = json(tool(min, policy, classifier, null)
                .cleanupOldLogs(missing.toString(), 30, false, true));
        assertTrue(quickSkipped.path("success").asBoolean(false));
        assertEquals("WARN", quickSkipped.path("status").asText());
        assertTrue(quickSkipped.path("data").asText().contains("SKIP"));
    }

    @Test
    void privateSafetyHelpersCoverProtectionTruncationAndLockEdges(@TempDir Path root) throws Exception {
        LogCleanupTool tool = tool(mock(MinPrivilegeExecutor.class), mock(OpsPathPolicy.class),
                mock(LogSafetyClassifier.class), null);
        Path protectedFile = Files.writeString(root.resolve("protected.log"), "protected");
        List<String> denied = new ArrayList<>();
        denied.add(protectedFile + " → protected");
        assertTrue((Boolean) staticCall("protectedFilesPreserved", new Class<?>[]{List.class}, denied));
        assertTrue((Boolean) staticCall("protectedFilesPreserved", new Class<?>[]{List.class}, new Object[]{null}));
        denied.add(" ");
        denied.add("not-a-real-path");
        assertFalse((Boolean) staticCall("protectedFilesPreserved", new Class<?>[]{List.class}, denied));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add("reason-" + i);
        }
        @SuppressWarnings("unchecked")
        List<String> truncated = (List<String>) staticCall("truncateDenied",
                new Class<?>[]{List.class, int.class}, many, 8);
        assertEquals(8, truncated.size());
        assertTrue(((List<?>) staticCall("truncateDenied", new Class<?>[]{List.class, int.class}, List.of(), 8)).isEmpty());

        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(tool, "isFileLocked", (Object) null));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(tool, "isFileLocked", " "));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(tool, "isFileLocked", root.toString()));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(tool, "isSimulatedDryRun",
                command("normal")));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(tool, "isSimulatedDryRun",
                command("[DRY-RUN]")));
        assertEquals(0L, (long) (Long) staticCall("sizeOfFile", new Class<?>[]{String.class}, " "));
    }

    private LogCleanupTool tool(MinPrivilegeExecutor min, OpsPathPolicy policy,
                                LogSafetyClassifier classifier, SafeDeleteSupport delete) {
        toolExecutor = Executors.newSingleThreadExecutor();
        return new LogCleanupTool(toolExecutor, new ObjectMapper(), min, policy, classifier,
                new OpsRemediationProperties(), delete);
    }

    private static AbstractCommandExecutor.CommandResult command(String output) {
        return new AbstractCommandExecutor.CommandResult(true, output, "", 0, false);
    }

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    private static Object staticCall(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = LogCleanupTool.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
}
