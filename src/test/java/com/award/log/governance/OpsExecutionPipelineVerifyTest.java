package com.award.log.governance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsExecutionPipelineVerifyTest {

    @Test
    void verifyPassesReadOnlyExecution() {
        OpsExecutionPipeline pipeline = new OpsExecutionPipeline(
                null, null, null, null, null, null, null, null, null);
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of("mode", "DRY-RUN"));
        Map<String, Object> report = pipeline.verify(
                "DiskTool", exec, "", Map.of("dryRun", true));
        assertTrue(Boolean.TRUE.equals(report.get("verified")));
    }

    @Test
    void verifyFailsWhenWriteRequestedWithoutEvidence() {
        OpsExecutionPipeline pipeline = new OpsExecutionPipeline(
                null, null, null, null, null, null, null, null, null);
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of("mode", "DRY-RUN", "filesDeleted", 0));
        Map<String, Object> report = pipeline.verify(
                "CleanTempTool", exec, "{\"preview\":true}", Map.of("dryRun", false, "confirmDelete", true));
        assertFalse(Boolean.TRUE.equals(report.get("verified")));
    }
}
