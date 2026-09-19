package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiskToolCoverageTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void windowsDiskUsageParsesArrayScalarAndMalformedOutput() throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        executor = Executors.newSingleThreadExecutor();
        DiskTool tool = new DiskTool(executor, new ObjectMapper(), min, policy);

        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(
                "[{\"Name\":\"C\",\"Used\":50,\"Free\":50,\"Size\":100},"
                        + "{\"Name\":\"D\",\"Used\":0,\"Free\":0,\"Size\":0}]"));
        JsonNode array = json(tool.checkDiskUsage());
        assertTrue(array.path("success").asBoolean(false));
        JsonNode data = json(array.path("data").asText());
        assertEquals(2, data.size());
        assertEquals("50%", data.get(0).path("usePercent").asText());

        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(
                "{\"Name\":\"E\",\"Used\":10,\"Free\":90,\"Size\":100}"));
        assertEquals(1, json(json(tool.checkDiskUsage()).path("data").asText()).size());

        when(min.executeSafely(anyList(), anyLong())).thenReturn(command("not-json"));
        JsonNode malformed = json(tool.checkDiskUsage());
        assertTrue(malformed.path("success").asBoolean(false));
        assertTrue(json(malformed.path("data").asText()).get(0).has("raw"));
    }

    @Test
    void diskRankingHonorsPolicyLimitsAndFailure(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        OpsPathPolicy policy = mock(OpsPathPolicy.class);
        when(policy.isAllowedDiskInsightRoot(anyString())).thenReturn(true);
        when(policy.rejectReason(anyString())).thenReturn("blocked");
        when(policy.normalizeUnixPath(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        executor = Executors.newSingleThreadExecutor();
        DiskTool tool = new DiskTool(executor, new ObjectMapper(), min, policy);

        String small = root.resolve("small").toString().replace("\\", "\\\\");
        String large = root.resolve("large").toString().replace("\\", "\\\\");
        when(min.executeSafely(anyList(), anyLong())).thenReturn(command(
                "[{\"kb\":20,\"path\":\"" + small + "\"},"
                        + "{\"kb\":50,\"path\":\"" + large + "\"}]"));
        JsonNode ranked = json(tool.rankDiskUsageUnderPath(root.toString(), 0, 100));
        assertTrue(ranked.path("success").asBoolean(false));
        assertEquals(2, json(ranked.path("data").asText()).path("entries").size());

        when(policy.isAllowedDiskInsightRoot(anyString())).thenReturn(false);
        JsonNode rejected = json(tool.rankDiskUsageUnderPath(root.toString(), 2, 10));
        assertFalse(rejected.path("success").asBoolean(true));

        when(policy.isAllowedDiskInsightRoot(anyString())).thenReturn(true);
        when(min.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(false, "", "du down", 1, false));
        JsonNode failed = json(tool.rankDiskUsageUnderPath(root.toString(), 2, 10));
        assertFalse(failed.path("success").asBoolean(true));
    }

    @Test
    void privateParsersCoverEmptyInvalidAndSortingEdges() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        DiskTool tool = new DiskTool(executor, new ObjectMapper(), mock(MinPrivilegeExecutor.class),
                mock(OpsPathPolicy.class));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>)
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(tool, "parseDuLines",
                        "20\t/not-top\ninvalid\n5 /small\n30 /large\n0 /zero\n", 2);
        assertEquals(2, rows.size());
        assertEquals(30L, rows.get(0).get("kb"));
        assertTrue(((List<?>) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                tool, "parseDuLines", " ", 2)).isEmpty());
        assertEquals(33, ((Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                tool, "parseUsePercent", "33%")).intValue());
        assertEquals(0, ((Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                tool, "parseUsePercent", "bad")).intValue());
        assertEquals(0, ((Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                tool, "parseUsePercent", (Object) null)).intValue());
        assertTrue(((List<?>) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                tool, "parseOutput", "bad line\n\n")).isEmpty());
    }

    private static AbstractCommandExecutor.CommandResult command(String output) {
        return new AbstractCommandExecutor.CommandResult(true, output, "", 0, false);
    }

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }
}
