package com.award.log.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 对 Spring AI {@code @Tool} 方法在调用前做与 McpExecuteController 同类的二次过滤，
 * 避免仅拦截用户首句而 LLM 多步工具调用绕开安全策略。
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class McpToolSecurityAspect {

    private final PromptInjectionGuard promptInjectionGuard;
    private final IntentRiskFilter intentRiskFilter;
    private final HighRiskCommandDetector highRiskCommandDetector;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx == null || ctx.isSkipPerToolCheck()) {
            return pjp.proceed();
        }

        String instruction = buildInstruction(pjp);
        if (promptInjectionGuard.isInjection(instruction)) {
            log.warn("Tool 调用被拦截(注入): {}", instruction);
            throw new OpsSecurityRejectedException("安全拦截：检测到注入攻击（工具层）");
        }
        RiskLevel risk = intentRiskFilter.evaluate(instruction);
        if (risk == RiskLevel.HIGH) {
            log.warn("Tool 调用被拦截(高风险意图): {}", instruction);
            throw new OpsSecurityRejectedException("安全拦截：高风险操作（工具层），需要人工确认");
        }
        if (highRiskCommandDetector.isHighRiskCommand(instruction)) {
            log.warn("Tool 调用被拦截(高危命令): {}", instruction);
            throw new OpsSecurityRejectedException("安全拦截：高危命令（工具层）");
        }
        if (risk == RiskLevel.MEDIUM) {
            log.warn("Tool 调用被拦截(中风险需二次确认): {}", instruction);
            throw new OpsSecurityRejectedException("安全拦截：中等风险操作请在 MCP 页面二次确认后执行");
        }

        return pjp.proceed();
    }

    private static String buildInstruction(ProceedingJoinPoint pjp) {
        String kind = pjp.getSignature().toShortString();
        String args = Arrays.stream(pjp.getArgs())
                .map(a -> a == null ? "null" : String.valueOf(a))
                .collect(Collectors.joining(", "));
        return "Tool " + kind + " args=[" + args + "]";
    }
}
