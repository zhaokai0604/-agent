package com.award.log.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * MCP / Agent 工具调用的统一安全裁决：输入尺度、注入、意图分级、高危命令串。
 * <p>
 * {@link McpSecurityProfile} 区分「首次请求」「已二次确认」「延时任务」三种信任边界，
 * 避免 confirmExecute / 延时路径被 MEDIUM 风险重复绊住。
 */
@Slf4j
@Component
public class McpInvocationSecurityGate {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "DiskTool",
            "DiskInsightTool",
            "ProcessTool",
            "SystemLoadTool",
            "LogAnalysisTool",
            "CleanTempTool",
            "ConfigCheckTool",
            "NetworkTool",
            "PrivilegeTool",
            "OsInsightTool"
    );

    private final PromptInjectionGuard promptInjectionGuard;
    private final IntentRiskFilter intentRiskFilter;
    private final HighRiskCommandDetector highRiskCommandDetector;

    private final int maxInstructionLength;
    private final int maxParameterEntries;
    private final int maxSingleStringParamChars;

    public McpInvocationSecurityGate(
            PromptInjectionGuard promptInjectionGuard,
            IntentRiskFilter intentRiskFilter,
            HighRiskCommandDetector highRiskCommandDetector,
            @Value("${agent.security.max-instruction-length:16384}") int maxInstructionLength,
            @Value("${agent.security.max-parameter-entries:48}") int maxParameterEntries,
            @Value("${agent.security.max-single-string-param-chars:131072}") int maxSingleStringParamChars
    ) {
        this.promptInjectionGuard = promptInjectionGuard;
        this.intentRiskFilter = intentRiskFilter;
        this.highRiskCommandDetector = highRiskCommandDetector;
        this.maxInstructionLength = Math.max(4096, maxInstructionLength);
        this.maxParameterEntries = Math.max(8, maxParameterEntries);
        this.maxSingleStringParamChars = Math.max(4096, maxSingleStringParamChars);
    }

    public String buildInstruction(String toolName, Map<String, Object> parameters) {
        StringBuilder instruction = new StringBuilder();
        instruction.append("执行 ").append(toolName);
        if (parameters != null && !parameters.isEmpty()) {
            instruction.append(" 参数: ");
            parameters.forEach((k, v) -> instruction.append(k).append("=").append(v).append(" "));
        }
        return instruction.toString().trim();
    }

    /**
     * @param profile INITIAL：中等风险需界面二次确认；POST_CONFIRM / DEFERRED：不再因 MEDIUM 阻断。
     */
    public GateDecision evaluate(String toolName, Map<String, Object> parameters, McpSecurityProfile profile) {
        if (toolName == null || toolName.isBlank()) {
            return GateDecision.block("TOOL_NAME_EMPTY", "工具名称不能为空");
        }
        if (!toolName.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
            log.warn("非法工具名格式: {}", toolName);
            return GateDecision.block("TOOL_NAME_REJECTED", "工具名称格式不合法");
        }
        if (!ALLOWED_TOOLS.contains(toolName)) {
            return GateDecision.block("UNKNOWN_TOOL", "未知或未授权的工具: " + toolName);
        }

        if (parameters != null && parameters.size() > maxParameterEntries) {
            return GateDecision.block("PARAM_TOO_MANY", "参数项过多，请缩减请求体");
        }
        String tooLargeValue = findOversizedParameter(parameters);
        if (tooLargeValue != null) {
            return GateDecision.block("PARAM_TOO_LARGE", "参数「" + tooLargeValue + "」体积超限");
        }

        String instruction = buildInstruction(toolName, parameters);
        if (instruction.length() > maxInstructionLength) {
            return GateDecision.block("INSTRUCTION_TOO_LONG",
                    "指令串长度超过上限（" + maxInstructionLength + "），请缩短路径或分批查询");
        }

        if (promptInjectionGuard.isInjection(instruction)) {
            return GateDecision.block("INJECTION", "安全拦截：检测到提示注入特征");
        }

        RiskLevel risk = intentRiskFilter.evaluate(instruction);
        if (risk == RiskLevel.HIGH) {
            return GateDecision.block("HIGH_INTENT", "安全拦截：高风险意图，禁止自动执行");
        }

        if (highRiskCommandDetector.isHighRiskCommand(instruction)) {
            return GateDecision.block("HIGH_RISK_COMMAND", "安全拦截：指令构造命中高危命令模式");
        }

        if (profile == McpSecurityProfile.INITIAL_REQUEST && risk == RiskLevel.MEDIUM) {
            return GateDecision.needConfirm(risk);
        }

        return GateDecision.allow(risk);
    }

    private String findOversizedParameter(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof String s && s.length() > maxSingleStringParamChars) {
                return e.getKey();
            }
            if (!(v instanceof String) && v.toString().length() > maxSingleStringParamChars) {
                return e.getKey();
            }
        }
        return null;
    }

    public enum McpSecurityProfile {
        INITIAL_REQUEST,
        POST_CONFIRMATION,
        DEFERRED_SCHEDULED
    }

    public static final class GateDecision {
        private final Type type;
        private final RiskLevel riskLevel;
        private final String code;
        private final String message;

        private GateDecision(Type type, RiskLevel riskLevel, String code, String message) {
            this.type = type;
            this.riskLevel = riskLevel;
            this.code = code;
            this.message = message;
        }

        public static GateDecision allow(RiskLevel risk) {
            return new GateDecision(Type.ALLOW, risk, "OK", null);
        }

        public static GateDecision needConfirm(RiskLevel risk) {
            return new GateDecision(Type.NEED_CONFIRM, risk, "NEED_CONFIRM",
                    "中等风险操作需在界面二次确认后方可执行");
        }

        public static GateDecision block(String code, String message) {
            return new GateDecision(Type.BLOCK, RiskLevel.HIGH, code, message);
        }

        public Type getType() {
            return type;
        }

        public RiskLevel getRiskLevel() {
            return riskLevel;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public enum Type {
            ALLOW,
            NEED_CONFIRM,
            BLOCK
        }
    }

    /** 供 HTTP / Chat 等入口快速校验纯用户文本尺度（非 MCP 专用）。 */
    public static String enforceChatMessageLimit(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

}
