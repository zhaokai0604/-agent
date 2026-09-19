package com.award.log.security.effect;

import com.award.log.governance.AssetTier;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsGovernanceService;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyReplayServiceCoverageTest {

    @Mock private McpInvocationSecurityGate securityGate;
    @Mock private OpsGovernanceService governance;
    @Mock private AgenticRiskScoreEngine riskScoreEngine;
    @Mock private OpsPathPolicy pathPolicy;
    @Mock private OpsAuditTraceService auditTraceService;

    private PolicyReplayService service;

    @BeforeEach
    void setUp() {
        PlanEffectGate planGate = new PlanEffectGate(new ToolEffectResolver(), governance);
        service = new PolicyReplayService(securityGate, new ToolEffectResolver(), planGate,
                governance, riskScoreEngine, pathPolicy, auditTraceService);
        lenient().when(pathPolicy.getPolicyVersion()).thenReturn("policy-test");
        lenient().when(riskScoreEngine.getAutoMax()).thenReturn(5.0);
        lenient().when(riskScoreEngine.getConfirmMax()).thenReturn(9.5);
        lenient().when(governance.isEnabled()).thenReturn(true);
        lenient().when(governance.summaryForPlatform()).thenReturn(Map.of("deliveryProfile", "TEST"));
        lenient().when(governance.evaluateToolCall(anyString(), anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.ALLOW_AUTO, AssetTier.NON_CORE, "local", "allowed"));
        lenient().when(securityGate.evaluate(anyString(), anyMap(), anyString(), any(McpInvocationSecurityGate.McpSecurityProfile.class)))
                .thenReturn(McpInvocationSecurityGate.GateDecision.allow(
                        com.award.log.security.RiskLevel.LOW, 1.0, Map.of("base", 1.0), "safe"));
    }

    @Test
    void replaysToolEffectDecisionGovernanceAndRequestedWrite() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "/tmp/cache");
        params.put("confirmDelete", true);

        Map<String, Object> out = service.replayTool("CleanTempTool", params, "清理临时文件", "post_confirm");

        assertTrue(Boolean.TRUE.equals(out.get("success")));
        assertEquals("TOOL", out.get("mode"));
        assertEquals("POST_CONFIRMATION", out.get("profile"));
        assertEquals("DELETE", ((Map<?, ?>) out.get("toolEffect")).get("action"));
        assertFalse(String.valueOf(out.get("effectFingerprint")).isBlank());
        assertEquals(true, out.get("requestedRealWrite"));
        assertEquals("ALLOW_AUTO", ((Map<?, ?>) out.get("governance")).get("verdict"));
    }

    @Test
    void replaysEmptyAndMixedPlans() {
        Map<String, Object> empty = service.replayPlan(null);
        assertEquals(0, empty.get("stepCount"));
        assertEquals("ALLOW", ((Map<?, ?>) empty.get("planDecision")).get("type"));

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(null);
        steps.add(Map.of("tool", "DiskTool", "parameters", Map.of("path", "/var/log")));
        steps.add(Map.of("toolName", "CleanTempTool", "parameters", Map.of("path", "/tmp")));
        steps.add(Map.of("toolName", " "));
        Map<String, Object> out = service.replayPlan(steps);
        assertEquals(2, out.get("stepCount"));
        assertTrue(((Map<?, ?>) out.get("planDecision")).containsKey("effectGraph"));
    }

    @Test
    void compareCoversBlockConfirmAndAllowScoreOnlyTendencies() {
        when(securityGate.evaluate(anyString(), anyMap(), anyString(), any(McpInvocationSecurityGate.McpSecurityProfile.class)))
                .thenReturn(McpInvocationSecurityGate.GateDecision.block("RISK_SCORE_HIGH", "too high"));
        Map<String, Object> blocked = service.compareTool("DiskTool", Map.of(), "inspect", null);
        assertEquals("BLOCK", ((Map<?, ?>) blocked.get("delta")).get("scorePathTendency"));

        when(securityGate.evaluate(anyString(), anyMap(), anyString(), any(McpInvocationSecurityGate.McpSecurityProfile.class)))
                .thenReturn(McpInvocationSecurityGate.GateDecision.needConfirm(
                        com.award.log.security.RiskLevel.MEDIUM, "治理要求人工确认", 6.0, Map.of(), "confirm"));
        Map<String, Object> confirm = service.compareTool("CleanTempTool", Map.of("path", "/tmp"), "clean", null);
        assertEquals("ALLOW_OR_SCORE_CONFIRM", ((Map<?, ?>) confirm.get("delta")).get("scorePathTendency"));
        assertEquals(true, ((Map<?, ?>) confirm.get("delta")).get("governanceChangedOutcome"));

        when(securityGate.evaluate(anyString(), anyMap(), anyString(), any(McpInvocationSecurityGate.McpSecurityProfile.class)))
                .thenReturn(McpInvocationSecurityGate.GateDecision.allow(
                        com.award.log.security.RiskLevel.LOW, 1.0, Map.of(), "ok"));
        Map<String, Object> allowed = service.compareTool("DiskTool", Map.of(), "inspect", "chat_agent_tool");
        assertEquals("ALLOW", ((Map<?, ?>) allowed.get("delta")).get("scorePathTendency"));
    }

    @Test
    void replaysAuditMissingAndEmbeddedStepRecords() {
        when(auditTraceService.findByTraceId("missing")).thenReturn(Map.of());
        assertFalse(Boolean.TRUE.equals(service.replayAudit("missing").get("success")));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("toolName", "DiskTool");
        step.put("parameters", Map.of("path", "/var/log"));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("toolName", "");
        audit.put("userInput", "inspect");
        audit.put("securityOutcome", "ALLOW");
        audit.put("policyVersion", "old-policy");
        audit.put("steps", List.of(step));
        when(auditTraceService.findByTraceId("trace-1")).thenReturn(audit);

        Map<String, Object> replay = service.replayAudit("trace-1");
        assertTrue(Boolean.TRUE.equals(replay.get("success")));
        assertEquals("AUDIT_REPLAY", replay.get("mode"));
        assertEquals("old-policy", replay.get("storedPolicyVersion"));
        assertTrue(replay.get("fresh") instanceof Map<?, ?>);
        assertFalse(Boolean.TRUE.equals(replay.get("outcomeChanged")));

        audit.remove("toolName");
        audit.remove("steps");
        when(auditTraceService.findByTraceId("trace-no-tool")).thenReturn(audit);
        assertTrue(String.valueOf(service.replayAudit("trace-no-tool").get("fresh")).contains("缺少 toolName"));
    }

    @Test
    void profileAndOutcomeNormalizersCoverAliases() throws Exception {
        Method profile = PolicyReplayService.class.getDeclaredMethod("parseProfile", String.class);
        Method outcome = PolicyReplayService.class.getDeclaredMethod("normalizeOutcome", String.class);
        profile.setAccessible(true);
        outcome.setAccessible(true);

        assertEquals("INITIAL_REQUEST", ((Enum<?>) profile.invoke(null, (Object) null)).name());
        assertEquals("POST_CONFIRMATION", ((Enum<?>) profile.invoke(null, "post-confirm")).name());
        assertEquals("DEFERRED_SCHEDULED", ((Enum<?>) profile.invoke(null, "deferred-job")).name());
        assertEquals("CHAT_AGENT_TOOL", ((Enum<?>) profile.invoke(null, "chat-tool")).name());
        assertEquals("INITIAL_REQUEST", ((Enum<?>) profile.invoke(null, "unknown")).name());
        assertEquals("NEED_CONFIRM", outcome.invoke(null, "confirmation pending"));
        assertEquals("BLOCK", outcome.invoke(null, "REJECTED"));
        assertEquals("ALLOW", outcome.invoke(null, "SUCCESS"));
        assertEquals("CUSTOM", outcome.invoke(null, "custom"));
        assertEquals("", outcome.invoke(null, new Object[]{null}));
    }
}
