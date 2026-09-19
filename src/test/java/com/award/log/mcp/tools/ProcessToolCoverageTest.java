package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessToolCoverageTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void windowsProcessListParsesThresholdReasonsAndSorts() throws Exception {
        MinPrivilegeExecutor minPrivilege = mock(MinPrivilegeExecutor.class);
        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true,
                        "[{\"ProcessName\":\"java\",\"Id\":12,\"cpuPct\":20,\"memPct\":30,\"wsMb\":100},"
                                + "{\"ProcessName\":\"idle\",\"Id\":13,\"cpuPct\":1,\"memPct\":20},"
                                + "{\"ProcessName\":\"low\",\"Id\":14,\"cpuPct\":1,\"memPct\":1}]",
                        null, 0, false));
        ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), minPrivilege);

        JsonNode root = new ObjectMapper().readTree(tool.listProcesses(5.0, 5.0));
        JsonNode data = new ObjectMapper().readTree(root.path("data").asText());
        assertTrue(root.path("success").asBoolean(false));
        assertEquals(2, data.size());
        assertEquals("cpu+mem", data.get(0).path("reason").asText());
        assertEquals("mem", data.get(1).path("reason").asText());

        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(false, null, "wmi down", 1, false));
        assertFalse(new ObjectMapper().readTree(tool.listProcesses(null, null)).path("success").asBoolean(true));
    }

    @Test
    void windowsProcessListFallsBackToMemoryAndHandlesMalformedJson() throws Exception {
        MinPrivilegeExecutor minPrivilege = mock(MinPrivilegeExecutor.class);
        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "{bad", null, 0, false),
                new AbstractCommandExecutor.CommandResult(true,
                        "{\"ProcessName\":\"java\",\"Id\":22,\"cpuPct\":-1,\"memPct\":15,\"wsMb\":12}",
                        null, 0, false));
        ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), minPrivilege);

        JsonNode root = new ObjectMapper().readTree(tool.listProcesses(5.0, 5.0));
        JsonNode data = new ObjectMapper().readTree(root.path("data").asText());
        assertTrue(root.path("success").asBoolean(false));
        assertEquals("n/a", data.get(0).path("cpu").asText());
        assertEquals("mem", data.get(0).path("reason").asText());

        Method parse = ProcessTool.class.getDeclaredMethod("parseWindowsProcessJson",
                AbstractCommandExecutor.CommandResult.class, double.class, double.class, boolean.class);
        parse.setAccessible(true);
        assertTrue(((List<?>) parse.invoke(tool,
                new AbstractCommandExecutor.CommandResult(true, "not-json", null, 0, false), 5.0, 5.0, true)).isEmpty());
    }

    @Test
    void ownershipAndTerminationExposeSafeOutcomes() throws Exception {
        MinPrivilegeExecutor minPrivilege = mock(MinPrivilegeExecutor.class);
        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "{\"ProcessId\":99,\"Name\":\"java\"}", null, 0, false));
        ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), minPrivilege);

        assertFalse(new ObjectMapper().readTree(tool.inspectProcessOwnership(0)).path("success").asBoolean(true));
        JsonNode ownership = new ObjectMapper().readTree(tool.inspectProcessOwnership(99));
        assertTrue(ownership.path("success").asBoolean(false));
        JsonNode invalid = new ObjectMapper().readTree(tool.terminateProcess(10, null, true, false));
        assertFalse(invalid.path("success").asBoolean(true));
        JsonNode preview = new ObjectMapper().readTree(tool.terminateProcess(99, "KILL", true, false));
        assertTrue(preview.path("success").asBoolean(false));
        JsonNode needsConfirm = new ObjectMapper().readTree(tool.terminateProcess(99, "TERM", false, false));
        assertFalse(needsConfirm.path("success").asBoolean(true));

        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "[DRY-RUN] taskkill", null, 0, false));
        assertFalse(new ObjectMapper().readTree(tool.terminateProcess(99, "TERM", false, true)).path("success").asBoolean(true));
        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(false, null, "access denied", 5, false));
        assertFalse(new ObjectMapper().readTree(tool.terminateProcess(99, "TERM", false, true)).path("success").asBoolean(true));
        when(minPrivilege.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "terminated", null, 0, false));
        JsonNode executed = new ObjectMapper().readTree(tool.executeGateway("stop", null, null, 99, "TERM", false, true));
        assertTrue(executed.path("success").asBoolean(false));
        assertEquals("EXECUTED", new ObjectMapper().readTree(executed.path("data").asText()).path("mode").asText());
        assertFalse(new ObjectMapper().readTree(tool.executeGateway("unknown", null, null, null, null, null, null))
                .path("success").asBoolean(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateParsingHelpersHandleLegacyZombieAndAssociationBranches() throws Exception {
        ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), mock(MinPrivilegeExecutor.class));
        Method parse = ProcessTool.class.getDeclaredMethod("parseOutput", String.class, double.class, double.class);
        parse.setAccessible(true);
        String longCommand = "x".repeat(240);
        List<Map<String, String>> rows = (List<Map<String, String>>) parse.invoke(tool,
                "u 20 1.0 2.0 S cmd --arg\n" +
                        "u 21 2 88.0 4.0 01:00 S " + longCommand + "\n" +
                        "u 22 0.0 0.0 01:00 Z zombie\n" +
                        "bad line", 5.0, 5.0);
        assertEquals(2, rows.size());
        assertEquals("zombie", rows.get(1).get("state"));

        Method serviceUnit = ProcessTool.class.getDeclaredMethod("serviceUnitFromCgroup", String.class);
        Method association = ProcessTool.class.getDeclaredMethod("associationHint", String.class, String.class);
        Method probe = ProcessTool.class.getDeclaredMethod("probe", AbstractCommandExecutor.CommandResult.class);
        serviceUnit.setAccessible(true);
        association.setAccessible(true);
        probe.setAccessible(true);
        assertEquals("nginx.service", serviceUnit.invoke(tool, "0::/system.slice/nginx.service"));
        assertEquals("worker.scope", serviceUnit.invoke(tool, "0::/user.slice/worker.scope"));
        assertEquals("", serviceUnit.invoke(tool, ""));
        assertTrue(String.valueOf(association.invoke(tool, "0::/system.slice/nginx.service", "nginx.service")).contains("nginx.service"));
        assertTrue(String.valueOf(association.invoke(tool, "0::/user.slice", "")).contains("已取得"));
        assertTrue(String.valueOf(association.invoke(tool, "unavailable: no", "")).contains("未取得"));
        Map<String, Object> noResult = (Map<String, Object>) probe.invoke(tool, new Object[]{null});
        assertEquals("no result", noResult.get("error"));
        Map<String, Object> failed = (Map<String, Object>) probe.invoke(tool,
                new AbstractCommandExecutor.CommandResult(false, "x", null, 7, false));
        assertEquals("exit=7", failed.get("error"));
    }
}
