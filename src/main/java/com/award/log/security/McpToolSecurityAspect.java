package com.award.log.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 对 Spring AI {@code @Tool} 方法在调用前走 {@link McpInvocationSecurityGate}，与 HTTP {@code /api/mcp} 同源裁决。
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class McpToolSecurityAspect {

    @Value("${agent.security.chat-agent-block-medium-tool-calls:false}")
    private boolean chatAgentBlockMediumToolCalls;

    private final McpInvocationSecurityGate mcpInvocationSecurityGate;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx == null || ctx.isSkipPerToolCheck()) {
            return pjp.proceed();
        }

        String toolBeanName = pjp.getTarget().getClass().getSimpleName();
        String instruction = buildInstruction(pjp, toolBeanName);

        McpInvocationSecurityGate.GateDecision decision =
                mcpInvocationSecurityGate.evaluateChatToolInvocation(toolBeanName, instruction);

        if (decision.getType() == McpInvocationSecurityGate.GateDecision.Type.BLOCK) {
            if ("HIGH_INTENT".equals(decision.getCode()) && decision.getRiskLevel() == RiskLevel.MEDIUM
                    && !chatAgentBlockMediumToolCalls) {
                log.debug("对话工具中风险意图已放行（与 HTTP 控制台二次确认路径区分）: {}", toolBeanName);
                return pjp.proceed();
            }
            log.warn("Tool 调用被统一安全门拦截: {} code={} msg={}", toolBeanName, decision.getCode(), decision.getMessage());
            throw new OpsSecurityRejectedException(
                    decision.getMessage() != null ? decision.getMessage() : "安全拦截（工具层）");
        }

        if (decision.getAgenticRiskScore() != null) {
            log.debug("Chat @Tool 通过安检: {} score={}", toolBeanName, decision.getAgenticRiskScore());
        }
        return pjp.proceed();
    }

    private static String buildInstruction(ProceedingJoinPoint pjp, String toolBeanName) {
        String args = Arrays.stream(pjp.getArgs())
                .map(a -> a == null ? "null" : String.valueOf(a))
                .collect(Collectors.joining(", "));
        return "执行 " + toolBeanName + " 参数: " + args;
    }
}
