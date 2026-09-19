package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor.CommandResult;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerToolCoverageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listAndInspectUseReadOnlyCommandsAndBoundOutput() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
            DockerTool tool = new DockerTool(executor, mapper, min);
            String huge = "x".repeat(100_010);
            when(min.executeSafely(anyList(), eq(30_000L)))
                    .thenReturn(new CommandResult(true, huge, null, 0, false));
            String listed = tool.listDockerContainers(true);
            assertTrue(listed.contains("docker ps -a"));
            assertTrue(listed.contains("...[truncated]"));

            when(min.executeSafely(anyList(), eq(30_000L)))
                    .thenReturn(new CommandResult(true, "/api|running|healthy|nginx|172.17.0.2", null, 0, false));
            String inspected = tool.inspectDockerContainer("api_1");
            assertTrue(inspected.contains("docker-inspect"));
            verify(min).executeSafely(List.of("docker", "inspect", "api_1",
                    "--format", "{{.Name}}|{{.State.Status}}|{{.State.Health.Status}}|{{.Config.Image}}|{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}"), 30_000L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inspectValidationAndCommandFailureReturnErrors() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
            DockerTool tool = new DockerTool(executor, mapper, min);
            assertEquals("ERROR", mapper.readTree(tool.inspectDockerContainer(" ")).path("status").asText());
            assertEquals("ERROR", mapper.readTree(tool.inspectDockerContainer("bad name")).path("status").asText());
            when(min.executeSafely(anyList(), eq(30_000L)))
                    .thenReturn(new CommandResult(false, "daemon output", "daemon unavailable", 1, false));
            JsonNode result = mapper.readTree(tool.inspectDockerContainer("api"));
            assertEquals("WARN", result.path("status").asText());
            assertTrue(result.path("data").asText().contains("daemon output"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restartCoversPreviewConfirmationSimulationFailureAndSuccess() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
            DockerTool tool = new DockerTool(executor, mapper, min);
            assertEquals("ERROR", mapper.readTree(tool.restartDockerContainer(" ", false, true)).path("status").asText());
            assertEquals("ERROR", mapper.readTree(tool.restartDockerContainer("bad name", true, null)).path("status").asText());
            assertTrue(tool.restartDockerContainer("api", true, null).contains("DRY-RUN"));
            assertEquals("ERROR", mapper.readTree(tool.restartDockerContainer("api", false, false)).path("status").asText());

            when(min.executeSafely(anyList(), eq(60_000L)))
                    .thenReturn(new CommandResult(true, "[DRY-RUN] simulated", null, 0, false))
                    .thenReturn(new CommandResult(false, null, "permission denied", 1, false))
                    .thenReturn(new CommandResult(true, "restarted", null, 0, false));
            assertEquals("ERROR", mapper.readTree(tool.restartDockerContainer("api", false, true)).path("status").asText());
            assertEquals("ERROR", mapper.readTree(tool.restartDockerContainer("api", false, true)).path("status").asText());
            assertEquals("SUCCESS", mapper.readTree(tool.restartDockerContainer("api", false, true)).path("status").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopAndGatewayCoverWritePolicyAndUnknownOperation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
            DockerTool tool = new DockerTool(executor, mapper, min);
            assertEquals("ERROR", mapper.readTree(tool.stopDockerContainer(null, false, true)).path("status").asText());
            assertTrue(tool.stopDockerContainer("api", null, null).contains("DRY-RUN"));
            assertEquals("ERROR", mapper.readTree(tool.stopDockerContainer("api", false, false)).path("status").asText());
            when(min.executeSafely(anyList(), eq(60_000L)))
                    .thenReturn(new CommandResult(true, "stopped", null, 0, false));
            assertEquals("SUCCESS", mapper.readTree(tool.stopDockerContainer("api", false, true)).path("status").asText());

            when(min.executeSafely(anyList(), eq(30_000L)))
                    .thenReturn(new CommandResult(true, "CONTAINER api running", null, 0, false));
            assertTrue(tool.executeGateway("ps", true, null, null, null, null).contains("docker ps -a"));
            assertEquals("ERROR", mapper.readTree(tool.executeGateway("unsupported", null, null, null, null, null))
                    .path("status").asText());
        } finally {
            executor.shutdownNow();
        }
    }
}
