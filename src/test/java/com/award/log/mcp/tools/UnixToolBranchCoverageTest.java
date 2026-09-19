package com.award.log.mcp.tools;

import com.award.log.config.AgentOpsProperties;
import com.award.log.config.OpsRemediationProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.LogSafetyClassifier;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.SafeDeleteSupport;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnixToolBranchCoverageTest {

    @Test
    void cleanTempUnixPreviewDeleteAndPolicyHelpers(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        SafeDeleteSupport delete = mock(SafeDeleteSupport.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        when(policy.isAllowedCleanDirectory(anyString())).thenReturn(true);
        Path old = Files.writeString(root.resolve("old.tmp"), "payload");
        when(min.executeSafely(anyList())).thenReturn(command(true, old + "\n"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CleanTempTool tool = new CleanTempTool(executor, new ObjectMapper(), min,
                policy, new OpsRemediationProperties(), delete);

        try (MockedStatic<OsRuntime> os = org.mockito.Mockito.mockStatic(OsRuntime.class)) {
            os.when(OsRuntime::isWindows).thenReturn(false);
            JsonNode preview = json(tool.cleanTempFiles(root.toString(), 0, true, false, false));
            JsonNode previewData = json(preview.path("data").asText());
            assertTrue(preview.path("success").asBoolean(false));
            assertEquals("MODIFY_TIME", previewData.path("cleanupStrategy").asText());
            assertEquals(1, previewData.path("filesFound").asInt());
            assertTrue(Files.exists(old));

            when(delete.deleteFile(any(Path.class), any(), eq(true)))
                    .thenAnswer(invocation -> {
                        Files.deleteIfExists(old);
                        return new SafeDeleteSupport.DeleteOutcome("RECYCLE", true, "unix-handle",
                                old.toString(), "");
                    });
            JsonNode removed = json(tool.cleanTempFiles(root.toString(), 0, false, true, false));
            JsonNode removedData = json(removed.path("data").asText());
            assertTrue(removed.path("success").asBoolean(false));
            assertEquals(1, removedData.path("filesDeleted").asInt());
            assertEquals("EFFECTIVE", removedData.path("businessEffect").asText());
            assertFalse(Files.exists(old));

            Files.writeString(root.resolve("nested.tmp"), "1234");
            assertTrue((Long) ReflectionTestUtils.invokeMethod(tool, "estimateDirectoryBytes", root.toString()) > 0);
            Object age = ReflectionTestUtils.invokeMethod(tool, "resolveCleanupAgePolicy", root.toString(), 0);
            assertEquals("MODIFY_TIME", ReflectionTestUtils.invokeMethod(age, "strategy"));
            assertEquals("SKIP: 目标目录不存在",
                    ReflectionTestUtils.invokeMethod(tool, "quickWriteSkipReason", root.resolve("missing").toString()));
            assertEquals("a\\\"b", ReflectionTestUtils.invokeMethod(tool, "jsonEscapePath", "a\"b"));

            String warn = ReflectionTestUtils.invokeMethod(tool, "finishZeroDeleteResponse",
                    "{\"mode\":\"DELETE\",\"filesDeleted\":0}", root.toString(),
                    System.currentTimeMillis() - 1_500L, true);
            assertEquals("WARN", json(warn).path("status").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logCleanupUnixPreviewDeleteAndZeroDeleteWarning(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        LogSafetyClassifier classifier = mock(LogSafetyClassifier.class);
        SafeDeleteSupport delete = mock(SafeDeleteSupport.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        when(policy.isAllowedLogCleanupPath(anyString())).thenReturn(true);
        when(policy.rejectReason(anyString())).thenReturn("rejected");
        Path log = Files.writeString(root.resolve("old.log"), "log-content");
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(true, log + "\n"));
        when(classifier.filterDeletable(anyList())).thenReturn(new LogSafetyClassifier.FilterResult(
                List.of(log.toString()), List.of()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LogCleanupTool tool = new LogCleanupTool(executor, new ObjectMapper(), min,
                policy, classifier, new OpsRemediationProperties(), delete);

        try (MockedStatic<OsRuntime> os = org.mockito.Mockito.mockStatic(OsRuntime.class)) {
            os.when(OsRuntime::isWindows).thenReturn(false);
            JsonNode preview = json(tool.cleanupOldLogs(root.toString(), 1, true, false));
            assertTrue(preview.path("success").asBoolean(false));
            assertEquals(1, json(preview.path("data").asText()).path("deletableCount").asInt());
            assertTrue(Files.exists(log));

            when(delete.deleteFile(any(Path.class), any(), eq(true)))
                    .thenAnswer(invocation -> {
                        Files.deleteIfExists(log);
                        return new SafeDeleteSupport.DeleteOutcome("RECYCLE", true, "log-handle",
                                log.toString(), "");
                    });
            JsonNode removed = json(tool.cleanupOldLogs(root.toString(), 1, false, true));
            assertTrue(removed.path("success").asBoolean(false));
            assertEquals(1, json(removed.path("data").asText()).path("filesDeleted").asInt());
            assertFalse(Files.exists(log));

            String enriched = ReflectionTestUtils.invokeMethod(tool, "enrichZeroDeleteWarnPayload",
                    "{\"filesDeleted\":0}", 1_500L);
            assertEquals("WARN", json(enriched).path("status").asText());
            assertTrue(json(enriched).path("message").asText().contains("耗时超过"));
            assertEquals("a\\\"b", ReflectionTestUtils.invokeMethod(tool, "jsonEscape", "a\"b"));
            assertEquals("SKIP: 目标目录不存在",
                    ReflectionTestUtils.invokeMethod(tool, "quickWriteSkipReason", root.resolve("missing").toString()));
            String warn = ReflectionTestUtils.invokeMethod(tool, "finishZeroDeleteResponse",
                    "{\"filesDeleted\":0}", root.toString(), System.currentTimeMillis() - 1_500L);
            assertEquals("WARN", json(warn).path("status").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }

    private static AbstractCommandExecutor.CommandResult command(boolean success, String output) {
        return new AbstractCommandExecutor.CommandResult(success, output, "", success ? 0 : 1, false);
    }
}
