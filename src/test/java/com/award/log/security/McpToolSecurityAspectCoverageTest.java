package com.award.log.security;

import com.award.log.agent.ActionGuardrailService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.CodeSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolSecurityAspectCoverageTest {

    private final McpInvocationSecurityGate gate = mock(McpInvocationSecurityGate.class);
    private final ActionGuardrailService guardrail = mock(ActionGuardrailService.class);
    private final PromptInjectionGuard injection = mock(PromptInjectionGuard.class);
    private final McpToolSecurityAspect aspect = new McpToolSecurityAspect(gate, guardrail, injection);

    @AfterEach
    void clearContext() {
        OpsSecurityContext.clear();
    }

    @Test
    void absentOrSkippedContextLeavesToolUntouched() throws Throwable {
        ProceedingJoinPoint pjp = point(new Object[]{"x"});
        when(pjp.proceed()).thenReturn("plain");
        assertEquals("plain", aspect.aroundTool(pjp));

        OpsSecurityContext.open("t", "inspect", true);
        assertEquals("plain", aspect.aroundTool(pjp));
    }

    @Test
    void unconfirmedChatMutationIsRejectedBeforeGate() {
        OpsSecurityContext.openChatAgent("t", "clean", McpToolSurface.FULL, false, false, "u", "s");
        ProceedingJoinPoint pjp = point(new Object[]{"C:/Temp", false});
        assertThrows(OpsSecurityRejectedException.class, () -> aspect.aroundTool(pjp));
    }

    @Test
    void gateAndGuardrailBlocksAreFailClosed() {
        OpsSecurityContext.open("t", "inspect", false);
        ProceedingJoinPoint pjp = point(new Object[]{"read"});
        when(gate.evaluateChatToolInvocation(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(McpInvocationSecurityGate.GateDecision.block("BLOCK", "gate"));
        assertThrows(OpsSecurityRejectedException.class, () -> aspect.aroundTool(pjp));

        when(gate.evaluateChatToolInvocation(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(McpInvocationSecurityGate.GateDecision.allow(RiskLevel.LOW, 1.0, Map.of(), "safe"));
        when(guardrail.beforeToolCall(anyString(), anyString(), anyMap()))
                .thenReturn(new ActionGuardrailService.GuardrailVerdict(false, "guardrail", Map.of()));
        assertThrows(OpsSecurityRejectedException.class, () -> aspect.aroundTool(pjp));
    }

    @Test
    void needConfirmDistinguishesNonChatAndUnconfirmedChatWrites() {
        when(gate.evaluateChatToolInvocation(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(McpInvocationSecurityGate.GateDecision.needConfirm(
                        RiskLevel.MEDIUM, "confirm", 4.0, Map.of("risk", 4.0), "medium"));
        when(guardrail.beforeToolCall(anyString(), anyString(), anyMap()))
                .thenReturn(new ActionGuardrailService.GuardrailVerdict(true, "", Map.of()));

        OpsSecurityContext.open("t", "write", false);
        assertThrows(OpsSecurityRejectedException.class, () -> aspect.aroundTool(point(new Object[]{"x"})));

        OpsSecurityContext.clear();
        OpsSecurityContext.openChatAgent("t", "write", McpToolSurface.FULL, false, false, "u", "s");
        assertThrows(OpsSecurityRejectedException.class,
                () -> aspect.aroundTool(point(new Object[]{"C:/Temp", false})));
    }

    @Test
    void allowedToolRecordsSuccessAndBlocksInjectedOutput() throws Throwable {
        OpsSecurityContext.openChatAgent("t", "inspect", McpToolSurface.FULL, true, false, "u", "s");
        when(gate.evaluateChatToolInvocation(anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(McpInvocationSecurityGate.GateDecision.allow(RiskLevel.LOW, 0.5, Map.of(), "safe"));
        when(guardrail.beforeToolCall(anyString(), anyString(), anyMap()))
                .thenReturn(new ActionGuardrailService.GuardrailVerdict(true, "", Map.of()));
        ProceedingJoinPoint pjp = point(new Object[]{"read"});
        when(pjp.proceed()).thenReturn("{\"success\":true}");
        when(injection.isInjectionInToolOutput(anyString())).thenReturn(false);
        assertEquals("{\"success\":true}", aspect.aroundTool(pjp));
        verify(guardrail).afterToolCall(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true), anyString());

        when(injection.isInjectionInToolOutput(anyString())).thenReturn(true);
        assertThrows(OpsSecurityRejectedException.class, () -> aspect.aroundTool(pjp));
    }

    private static ProceedingJoinPoint point(Object[] args) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        CodeSignature signature = mock(CodeSignature.class);
        when(pjp.getTarget()).thenReturn(new CleanTempTool());
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getParameterNames()).thenReturn(new String[]{"path", "dryRun"});
        return pjp;
    }

    /** 仅用于提供与 Spring Bean 相同的简单类名，避免在切面测试中创建真实文件操作工具。 */
    private static final class CleanTempTool {
    }
}
