package com.award.log.security;

import com.award.log.mcp.dispatch.McpToolParamReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 安全内核门面：HTTP MCP、编排器、@Tool AOP 统一口径的裁决入口。
 */
@Component
@RequiredArgsConstructor
public class OpsSecurityKernel {

    private final McpInvocationSecurityGate mcpInvocationSecurityGate;
    private final OpsTrustPolicy opsTrustPolicy;

    public McpInvocationSecurityGate.GateDecision evaluateTool(
            String toolName,
            Map<String, Object> parameters,
            McpInvocationSecurityGate.McpSecurityProfile profile) {
        return mcpInvocationSecurityGate.evaluate(toolName, parameters, profile);
    }

    public McpInvocationSecurityGate.GateDecision evaluateChatTool(
            String toolBeanName,
            String instruction) {
        return mcpInvocationSecurityGate.evaluateChatToolInvocation(toolBeanName, instruction);
    }

    public OpsTrustTier trustTierForTool(String toolName, Map<String, Object> parameters, String instruction) {
        return opsTrustPolicy.tierForTool(toolName, parameters, instruction);
    }

    public String buildInstruction(String toolName, Map<String, Object> parameters) {
        return mcpInvocationSecurityGate.buildInstruction(toolName, parameters);
    }

    public void applyConfirmedWriteParams(String toolName, Map<String, Object> parameters, String userMessage) {
        McpToolParamReader.applyConfirmedWriteToolParams(toolName, parameters, userMessage);
    }
}
