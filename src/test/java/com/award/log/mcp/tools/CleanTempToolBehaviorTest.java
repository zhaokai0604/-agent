package com.award.log.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.award.log.security.OpsPathPolicy;
import com.award.log.config.AgentOpsProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanTempToolBehaviorTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void deleteShouldReachDeepTempFiles(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        ObjectMapper mapper = McpToolsTestFixtures.objectMapper();

        Path deep = tempDir.resolve("a/b/c/d/e");
        Files.createDirectories(deep);
        Path file = deep.resolve("old-cache.bin");
        Files.writeString(file, "cleanup-me");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        String raw = tool.cleanTempFiles(tempDir.toString(), 0, false, true, false);
        JsonNode root = mapper.readTree(raw);
        JsonNode data = mapper.readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals("SUCCESS", root.path("status").asText());
        assertEquals("DELETE", data.path("mode").asText());
        assertEquals(1, data.path("filesDeleted").asInt());
        assertTrue(data.path("bytesFreed").asLong(0) > 0);
        assertEquals("EFFECTIVE", data.path("businessEffect").asText());
        assertFalse(Files.exists(file));
    }

    @Test
    void realDeleteZeroShouldBeWarn(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        ObjectMapper mapper = McpToolsTestFixtures.objectMapper();

        String raw = tool.cleanTempFiles(tempDir.toString(), 0, false, true, false);
        JsonNode root = mapper.readTree(raw);
        JsonNode data = mapper.readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals("WARN", root.path("status").asText());
        assertEquals(0, data.path("filesDeleted").asInt());
        assertEquals("NO_EFFECT", data.path("businessEffect").asText());
    }

    @Test
    void realDeleteRequiresExplicitConfirmation(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        JsonNode root = McpToolsTestFixtures.objectMapper().readTree(
                tool.cleanTempFiles(tempDir.toString(), 7, false, false, false));

        assertFalse(root.path("success").asBoolean(true));
        assertTrue(root.path("error").asText().contains("confirmDelete"));
    }

    @Test
    void rejectsPathOutsideWhitelist(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        Path outside = tempDir.resolveSibling("not-allowed-clean-root");
        JsonNode root = McpToolsTestFixtures.objectMapper().readTree(
                tool.cleanTempFiles(outside.toString(), 7, true, false, false));

        assertFalse(root.path("success").asBoolean(true));
        assertTrue(root.path("error").asText().contains("白名单")
                || root.path("error").asText().contains("拒绝"));
    }

    @Test
    void removeDirectoryCannotDeleteTempRoot(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        JsonNode root = McpToolsTestFixtures.objectMapper().readTree(
                tool.cleanTempFiles(tempDir.toString(), 0, true, false, true));

        assertFalse(root.path("success").asBoolean(true));
        assertTrue(root.path("error").asText().contains("子目录")
                || root.path("error").asText().contains("Temp 根"));
    }

    @Test
    void removeDirectoryChildSupportsPreviewAndConfirmedDelete(@TempDir Path tempDir) throws Exception {
        Path child = tempDir.resolve("old-cache");
        Files.createDirectories(child);
        Path file = child.resolve("cache.bin");
        Files.writeString(file, "cache");
        CleanTempTool tool = toolForRoot(tempDir);
        ObjectMapper mapper = McpToolsTestFixtures.objectMapper();

        JsonNode preview = mapper.readTree(tool.cleanTempFiles(
                child.toString(), 0, true, false, true));
        assertTrue(preview.path("success").asBoolean(false));
        assertTrue(mapper.readTree(preview.path("data").asText()).path("removeDirectory").asBoolean(false));

        JsonNode deleted = mapper.readTree(tool.cleanTempFiles(
                child.toString(), 0, false, true, true));
        assertTrue(deleted.path("success").asBoolean(false));
        assertTrue(mapper.readTree(deleted.path("data").asText()).path("removed").asBoolean(false));
        assertFalse(Files.exists(child));
    }

    @Test
    void scanAllTempJunkReportsConfiguredLocation(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        Files.writeString(tempDir.resolve("old.log"), "preview");
        JsonNode root = McpToolsTestFixtures.objectMapper().readTree(tool.scanAllTempJunk(0));

        assertTrue(root.path("success").asBoolean(false));
        JsonNode data = McpToolsTestFixtures.objectMapper().readTree(root.path("data").asText());
        assertEquals("SCAN", data.path("mode").asText());
        assertTrue(data.path("locationCount").asInt(0) >= 1);
    }

    private CleanTempTool toolForRoot(Path root) {
        OpsPathPolicy policy = McpToolsTestFixtures.opsPathPolicy();
        String rootPath = root.toString();
        if (com.award.log.util.OsRuntime.isWindows()) {
            policy.updateEditablePathLists(true, java.util.List.of(rootPath), java.util.List.of(rootPath), java.util.List.of());
        } else {
            policy.updateEditablePathLists(false, java.util.List.of(rootPath), java.util.List.of(rootPath), java.util.List.of());
        }
        ReflectionTestUtils.setField(policy, "policyVersion", "test-clean-root");
        return new CleanTempTool(executor, McpToolsTestFixtures.objectMapper(),
                McpToolsTestFixtures.minPrivilegeExecutor(executor), policy, new com.award.log.config.OpsRemediationProperties(), null);
    }
}
