package com.award.log.mcp.tools;

import com.award.log.config.AgentOpsProperties;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogCleanupToolBehaviorTest {

    @Mock
    private MinPrivilegeExecutor executor;
    @Mock
    private OpsPathPolicy pathPolicy;
    @Mock
    private LogSafetyClassifier classifier;
    @Mock
    private SafeDeleteSupport safeDeleteSupport;

    private ExecutorService toolExecutor;

    @AfterEach
    void tearDown() {
        if (toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
    }

    @Test
    void rejectsDisallowedAndUnconfirmedWrites() throws Exception {
        when(pathPolicy.isAllowedLogCleanupPath(anyString())).thenReturn(false);
        LogCleanupTool tool = newTool(null);
        JsonNode denied = json(tool.cleanupOldLogs("C:/not-allowed", 30, true, false));
        assertFalse(denied.path("success").asBoolean(true));

        when(pathPolicy.isAllowedLogCleanupPath(anyString())).thenReturn(true);
        JsonNode unconfirmed = json(tool.cleanupOldLogs("C:/logs", 30, false, false));
        assertFalse(unconfirmed.path("success").asBoolean(true));
        assertTrue(unconfirmed.path("error").asText().contains("confirmDelete"));
    }

    @Test
    void previewsWindowsCandidatesAndPreservesProtectedEntries(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Path candidate = Files.writeString(tempDir.resolve("old.log"), "old log");
        Path protectedFile = Files.writeString(tempDir.resolve("mysql-slow.log"), "db log");
        when(pathPolicy.isAllowedLogCleanupPath(tempDir.toString())).thenReturn(true);
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(command(
                "[\"" + candidate.toString().replace("\\", "\\\\") + "\",\""
                        + protectedFile.toString().replace("\\", "\\\\") + "\"]"));
        when(classifier.filterDeletable(anyList())).thenReturn(new LogSafetyClassifier.FilterResult(
                List.of(candidate.toString()), List.of(protectedFile + " → protected")));

        JsonNode root = json(newTool(null).cleanupOldLogs(tempDir.toString(), 30, true, false));
        assertTrue(root.path("success").asBoolean(false));
        JsonNode data = json(root.path("data").asText());
        assertEquals("DRY-RUN", data.path("mode").asText());
        assertEquals(2, data.path("filesFound").asInt());
        assertEquals(1, data.path("deletableCount").asInt());
        assertEquals(1, data.path("protectedSkipped").asInt());
        assertTrue(data.path("protectedSamples").toString().contains("protected"));
    }

    @Test
    void executesDeletableWindowsCandidateThroughRecycleBin(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Path candidate = Files.writeString(tempDir.resolve("old.log"), "old log");
        when(pathPolicy.isAllowedLogCleanupPath(tempDir.toString())).thenReturn(true);
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(command(
                "[\"" + candidate.toString().replace("\\", "\\\\") + "\"]"));
        when(classifier.filterDeletable(anyList())).thenReturn(new LogSafetyClassifier.FilterResult(
                List.of(candidate.toString()), List.of()));
        when(safeDeleteSupport.deleteFile(any(Path.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new SafeDeleteSupport.DeleteOutcome("RECYCLE", true, "recycle-1",
                        candidate.toString(), ""));

        JsonNode root = json(newTool(safeDeleteSupport).cleanupOldLogs(tempDir.toString(), 30, false, true));
        assertTrue(root.path("success").asBoolean(false));
        JsonNode data = json(root.path("data").asText());
        assertEquals("DELETE", data.path("mode").asText());
        assertEquals(1, data.path("filesDeleted").asInt());
        assertEquals(1, data.path("recycledCount").asInt());
        assertTrue(data.path("protectedFilesPreserved").asBoolean(false));
    }

    @Test
    void failsClosedWhenRecycleBinIsUnavailableAndHandlesEmptyScan(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        when(pathPolicy.isAllowedLogCleanupPath(tempDir.toString())).thenReturn(true);
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(command("[]"));
        JsonNode empty = json(newTool(null).cleanupOldLogs(tempDir.toString(), 0, true, false));
        assertTrue(empty.path("success").asBoolean(false));
        assertEquals(0, json(empty.path("data").asText()).path("filesFound").asInt());

        Path candidate = Files.writeString(tempDir.resolve("old.log"), "old");
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(command(
                "[\"" + candidate.toString().replace("\\", "\\\\") + "\"]"));
        when(classifier.filterDeletable(anyList())).thenReturn(new LogSafetyClassifier.FilterResult(
                List.of(candidate.toString()), List.of()));
        JsonNode rejected = json(newTool(null).cleanupOldLogs(tempDir.toString(), 30, false, true));
        assertFalse(rejected.path("success").asBoolean(true));
        assertTrue(rejected.path("error").asText().contains("回收站"));
    }

    private LogCleanupTool newTool(SafeDeleteSupport deleteSupport) {
        toolExecutor = Executors.newSingleThreadExecutor();
        return new LogCleanupTool(toolExecutor, new ObjectMapper(), executor, pathPolicy, classifier,
                new OpsRemediationProperties(), deleteSupport);
    }

    private static AbstractCommandExecutor.CommandResult command(String output) {
        return new AbstractCommandExecutor.CommandResult(true, output, "", 0, false);
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
