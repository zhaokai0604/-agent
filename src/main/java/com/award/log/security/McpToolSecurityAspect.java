package com.award.log.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Map;
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

    private final McpInvocationSecurityGate mcpInvocationSecurityGate;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx == null || ctx.isSkipPerToolCheck()) {
            return pjp.proceed();
        }

        String toolBeanName = ClassUtils.getUserClass(pjp.getTarget()).getSimpleName();
        Map<String, Object> parameters = ChatToolParamExtractor.extract(pjp);
        String instruction = buildInstruction(pjp, toolBeanName);
        String userUtterance = ctx.getUserMessage();

        McpInvocationSecurityGate.GateDecision decision =
                mcpInvocationSecurityGate.evaluateChatToolInvocation(
                        toolBeanName, parameters, instruction, userUtterance);

        if (decision.getType() == McpInvocationSecurityGate.GateDecision.Type.BLOCK) {
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
