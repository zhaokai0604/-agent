package com.award.log.governance;

import com.award.log.security.effect.VerifyOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(VerifyOutcome.VERIFY_SKIPPED.name(), report.get("verifyOutcome"));
        assertNotNull(report.get("expectedEffect"));
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
        assertEquals(VerifyOutcome.EVIDENCE_INCOMPLETE.name(), report.get("verifyOutcome"));
    }

    @Test
    void verifyPassesCleanTempWithDeleteEvidence() {
        OpsExecutionPipeline pipeline = new OpsExecutionPipeline(
                null, null, null, null, null, null, null, null, null);
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("metricsBefore", Map.of("available", true, "filesFound", 2));
        exec.put("metricsAfter", Map.of("available", true, "filesFound", 0));
        exec.put("data", Map.of("mode", "DELETE", "filesDeleted", 2, "bytesFreed", 10));
        Map<String, Object> report = pipeline.verify(
                "CleanTempTool", exec, "{}", Map.of("path", "/tmp", "dryRun", false, "confirmDelete", true));
        assertTrue(Boolean.TRUE.equals(report.get("verified")));
        assertEquals(VerifyOutcome.VERIFIED.name(), report.get("verifyOutcome"));
        assertEquals("PATH_USAGE", ((Map<?, ?>) report.get("expectedEffect")).get("metric"));
    }

    @Test
    void successGateBlocksWhenVerifyFails() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("verified", false);
        verify.put("verifyOutcome", VerifyOutcome.VERIFY_WEAK.name());
        verify.put("message", "弱证据");
        OpsExecutionPipeline.applyRemediationSuccessGate(
                exec, verify, Map.of("dryRun", false, "confirmDelete", true));
        assertFalse(Boolean.TRUE.equals(exec.get("success")));
        assertTrue(Boolean.TRUE.equals(exec.get("executionSuccess")));
        assertTrue(Boolean.TRUE.equals(exec.get("verifyBlocked")));
        assertFalse(Boolean.TRUE.equals(exec.get("remediationClosed")));
    }
}
