package com.award.log.security.effect;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemediationVerifyEvaluatorTest {

    @Test
    void skipWhenNotRealWrite() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of("mode", "DRY-RUN"));
        ToolEffect effect = new ToolEffectResolver().resolve("CleanTempTool", Map.of("dryRun", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "CleanTempTool", Map.of("dryRun", true), effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "CleanTempTool", exec, "{}", Map.of("dryRun", true), contract, effect);
        assertEquals(VerifyOutcome.VERIFY_SKIPPED.name(), report.get("verifyOutcome"));
        assertTrue(Boolean.TRUE.equals(report.get("verified")));
        assertNotNull(report.get("expectedEffect"));
    }

    @Test
    void evidenceIncompleteWithoutWriteProof() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of("mode", "DRY-RUN", "filesDeleted", 0));
        ToolEffect effect = new ToolEffectResolver().resolve(
                "CleanTempTool", Map.of("dryRun", false, "confirmDelete", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "CleanTempTool", Map.of("dryRun", false, "confirmDelete", true), effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "CleanTempTool", exec, "{\"preview\":true}",
                Map.of("dryRun", false, "confirmDelete", true), contract, effect);
        assertEquals(VerifyOutcome.EVIDENCE_INCOMPLETE.name(), report.get("verifyOutcome"));
        assertFalse(Boolean.TRUE.equals(report.get("verified")));
    }

    @Test
    void verifiedWhenDeleteEvidencePresent() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of(
                "mode", "DELETE",
                "filesDeleted", 3,
                "bytesFreed", 1024,
                "recycleHandle", "rb-1"));
        ToolEffect effect = new ToolEffectResolver().resolve(
                "CleanTempTool", Map.of("path", "/tmp", "dryRun", false, "confirmDelete", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "CleanTempTool", Map.of("path", "/tmp", "dryRun", false, "confirmDelete", true), effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "CleanTempTool", exec, "{}",
                Map.of("dryRun", false, "confirmDelete", true), contract, effect);
        assertEquals(VerifyOutcome.VERIFIED.name(), report.get("verifyOutcome"));
        assertTrue(Boolean.TRUE.equals(report.get("verified")));
        @SuppressWarnings("unchecked")
        Map<String, Object> recover = (Map<String, Object>) report.get("recover");
        assertTrue(Boolean.TRUE.equals(recover.get("reversible")));
    }

    @Test
    void serviceRestartVerifiedOnActiveState() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of(
                "mode", "EXECUTED",
                "service", "nginx",
                "afterState", "active",
                "success", true));
        ToolEffect effect = new ToolEffectResolver().resolve(
                "ServiceRestartTool", Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "ServiceRestartTool",
                Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true),
                effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "ServiceRestartTool", exec, "{}",
                Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true),
                contract, effect);
        assertEquals(VerifyOutcome.VERIFIED.name(), report.get("verifyOutcome"));
        assertFalse(Boolean.TRUE.equals(
                ((Map<?, ?>) report.get("recover")).get("reversible")));
    }

    @Test
    void weakWhenServiceRestartMissingAfterState() {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", Map.of(
                "mode", "EXECUTED",
                "service", "nginx",
                "success", true));
        ToolEffect effect = new ToolEffectResolver().resolve(
                "ServiceRestartTool", Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true));
        WriteRemediationContract contract = RemediationContractFactory.build(
                "ServiceRestartTool",
                Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true),
                effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "ServiceRestartTool", exec, "{}",
                Map.of("serviceName", "nginx", "dryRun", false, "confirmRestart", true),
                contract, effect);
        assertEquals(VerifyOutcome.VERIFY_WEAK.name(), report.get("verifyOutcome"));
        assertFalse(Boolean.TRUE.equals(report.get("verified")));
    }
}
