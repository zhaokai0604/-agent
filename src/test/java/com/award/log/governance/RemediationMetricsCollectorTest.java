package com.award.log.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemediationMetricsCollectorTest {

    @Test
    void collectsStableFieldsAndComputesAfterProbeDelta() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> beforeResponse = Map.of(
                "success", true,
                "status", "SUCCESS",
                "data", "{\"mode\":\"DRY-RUN\",\"filesFound\":3,\"deletableCount\":1,\"protectedSkipped\":2}");
        Map<String, Object> afterResponse = Map.of(
                "success", true,
                "status", "SUCCESS",
                "data", "{\"mode\":\"DRY-RUN\",\"filesFound\":2,\"deletableCount\":0,\"protectedSkipped\":2}");

        Map<String, Object> before = RemediationMetricsCollector.fromResponse(
                mapper, "LogCleanupTool", beforeResponse, "preview");
        Map<String, Object> after = RemediationMetricsCollector.fromResponse(
                mapper, "LogCleanupTool", afterResponse, "post-execution-probe");

        assertEquals(1, ((Number) before.get("deletableCount")).intValue());
        assertEquals(0, ((Number) after.get("deletableCount")).intValue());
        assertEquals(-1, ((Number) RemediationMetricsCollector.numericDelta(before, after)
                .get("deletableCount")).intValue());
        assertTrue(Boolean.TRUE.equals(after.get("available")));
    }
}
