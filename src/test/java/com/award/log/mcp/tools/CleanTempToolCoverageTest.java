package com.award.log.mcp.tools;

import com.award.log.config.OpsRemediationProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.service.SafeDeleteSupport;
import com.award.log.security.OpsPathPolicy;
import com.award.log.config.AgentOpsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CleanTempToolCoverageTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void windowsPreviewParsesArrayAndUsesAccessStrategy(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        Path file = Files.writeString(root.resolve("old.tmp"), "12345");
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(true,
                "{\"strategy\":\"ACCESS_TIME\",\"bytes\":12345,\"files\":[\""
                        + file.toString().replace("\\", "\\\\") + "\"]}"));
        CleanTempTool tool = tool(root, min, null);

        JsonNode response = mapper().readTree(tool.cleanTempFiles(root.toString(), 2, true, false, false));
        JsonNode data = mapper().readTree(response.path("data").asText());
        assertTrue(response.path("success").asBoolean(false));
        assertEquals("DRY-RUN", data.path("mode").asText());
        assertEquals("ACCESS_TIME", data.path("cleanupStrategy").asText());
        assertEquals(1, data.path("filesFound").asInt());
        assertTrue(Files.exists(file));
    }

    @Test
    void confirmedWindowsDeleteUsesRecycleOutcomeAndRejectsFailedDelete(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        SafeDeleteSupport safeDelete = mock(SafeDeleteSupport.class);
        Path file = Files.writeString(root.resolve("delete.tmp"), "payload");
        String scan = "{\"strategy\":\"MODIFY_TIME\",\"bytes\":7,\"files\":[\""
                + file.toString().replace("\\", "\\\\") + "\"]}";
        when(min.executeSafely(anyList(), anyLong())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            String joined = String.join(" ", command);
            if (joined.contains("New-Item")) {
                return command(true, "OK");
            }
            return command(true, scan);
        });
        when(safeDelete.deleteFile(any(Path.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenAnswer(invocation -> {
                    Files.deleteIfExists(file);
                    return new SafeDeleteSupport.DeleteOutcome("RECYCLE", true, "handle-1",
                            file.toString(), "");
                });
        CleanTempTool tool = tool(root, min, safeDelete);

        JsonNode response = mapper().readTree(tool.cleanTempFiles(root.toString(), 0, false, true, false));
        JsonNode data = mapper().readTree(response.path("data").asText());
        assertTrue(response.path("success").asBoolean(false));
        assertEquals(1, data.path("filesDeleted").asInt());
        assertEquals(1, data.path("recycledCount").asInt());
        assertFalse(Files.exists(file));

        Path failed = Files.writeString(root.resolve("failed.tmp"), "payload");
        String failedScan = "{\"strategy\":\"MODIFY_TIME\",\"bytes\":7,\"files\":[\""
                + failed.toString().replace("\\", "\\\\") + "\"]}";
        when(min.executeSafely(anyList(), anyLong())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            String joined = String.join(" ", command);
            if (joined.contains("New-Item")) {
                return command(true, "OK");
            }
            return command(true, failedScan);
        });
        when(safeDelete.deleteFile(any(Path.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new SafeDeleteSupport.DeleteOutcome("ERROR", false, "", "", "recycle down"));
        JsonNode failedResponse = mapper().readTree(tool.cleanTempFiles(root.toString(), 0, false, true, false));
        assertFalse(failedResponse.path("success").asBoolean(true));
        assertTrue(Files.exists(failed));
    }

    @Test
    void directoryTreePreviewAndUnavailableRecycleAreExplicit(@TempDir Path root) throws Exception {
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("one.tmp"), "one");
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        CleanTempTool tool = tool(root, min, null);
        JsonNode preview = mapper().readTree(tool.cleanTempFiles(child.toString(), 0, true, false, true));
        assertTrue(preview.path("success").asBoolean(false));
        assertEquals(1, mapper().readTree(preview.path("data").asText()).path("filesInDirectory").asInt());
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(true, "OK"));
        JsonNode result = mapper().readTree(tool.cleanTempFiles(child.toString(), 0, false, true, true));
        assertFalse(result.path("success").asBoolean(true));
        assertTrue(Files.exists(child.resolve("one.tmp")));
    }

    @Test
    void malformedOrEmptyWindowsScanAndHelperParsingFailClosed(@TempDir Path root) throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        CleanTempTool tool = tool(root, min, null);
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(true, "not-json"));
        JsonNode preview = mapper().readTree(tool.cleanTempFiles(root.toString(), 0, true, false, false));
        assertTrue(preview.path("success").asBoolean(false));
        assertEquals(0, mapper().readTree(preview.path("data").asText()).path("filesFound").asInt());

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(tool, "isSimulatedDryRun",
                command(true, "[DRY-RUN] simulated")));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(tool, "isSimulatedDryRun",
                command(true, "normal")));
        assertTrue(ReflectionTestUtils.invokeMethod(tool, "parseNestedData", mapper().readTree("{}")) == null);
        assertTrue(ReflectionTestUtils.invokeMethod(tool, "parseNestedData", mapper().readTree(
                "{\"data\":\"not-json\"}")) == null);
        assertTrue(!((String) ReflectionTestUtils.invokeMethod(tool, "defaultTempTargetPath")).isBlank());
    }

    private CleanTempTool tool(Path root, MinPrivilegeExecutor min, SafeDeleteSupport safeDelete) {
        AgentOpsProperties props = new AgentOpsProperties();
        OpsPathPolicy policy = new OpsPathPolicy(props);
        policy.updateEditablePathLists(com.award.log.util.OsRuntime.isWindows(),
                List.of(root.toString()), List.of(root.toString()), List.of());
        ReflectionTestUtils.setField(policy, "policyVersion", "test-clean-coverage");
        OpsRemediationProperties remediation = new OpsRemediationProperties();
        remediation.setSkipLockedFiles(false);
        return new CleanTempTool(executor, mapper(), min, policy, remediation, safeDelete);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private static AbstractCommandExecutor.CommandResult command(boolean success, String output) {
        return command(success, output, null);
    }

    private static AbstractCommandExecutor.CommandResult command(boolean success, String output, String error) {
        return new AbstractCommandExecutor.CommandResult(success, output, error, success ? 0 : 1, false);
    }
}
