package com.award.log.security;

import com.award.log.mcp.McpToolCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP / Agent 工具调用的统一安全裁决：输入尺度、注入、意图分级、高危命令串。
 * <p>
 * {@link McpSecurityProfile} 区分「首次请求」「已二次确认」「延时任务」三种信任边界，
 * 避免 confirmExecute / 延时路径被 MEDIUM 风险重复绊住。
 */
@Slf4j
@Component
public class McpInvocationSecurityGate {

    private static final Pattern DESTRUCTIVE_UTTERANCE_CN = Pattern.compile(
            "删除.{0,16}(整个|全部|所有|根|系统)|删.{0,4}(整个|全部|所有).{0,12}(系统|根|盘|文件)|清空.{0,8}(系统|根|盘)",
            Pattern.CASE_INSENSITIVE);

    /** 在 {@link McpToolSurface#READ_ONLY} 会话面下禁止的工具（含可配置追加项） */
    private final ReadOnlySurfaceDenylist readOnlySurfaceDenylist;
    private final McpToolCatalog mcpToolCatalog;

    private final PromptInjectionGuard promptInjectionGuard;
    private final IntentRiskFilter intentRiskFilter;
    private final HighRiskCommandDetector highRiskCommandDetector;
    private final AgenticRiskScoreEngine agenticRiskScoreEngine;

    private final int maxInstructionLength;
    private final int maxParameterEntries;
    private final int maxSingleStringParamChars;

    public McpInvocationSecurityGate(
            PromptInjectionGuard promptInjectionGuard,
            IntentRiskFilter intentRiskFilter,
            HighRiskCommandDetector highRiskCommandDetector,
            AgenticRiskScoreEngine agenticRiskScoreEngine,
            McpToolCatalog mcpToolCatalog,
            ReadOnlySurfaceDenylist readOnlySurfaceDenylist,
            @Value("${agent.security.max-instruction-length:16384}") int maxInstructionLength,
            @Value("${agent.security.max-parameter-entries:48}") int maxParameterEntries,
            @Value("${agent.security.max-single-string-param-chars:131072}") int maxSingleStringParamChars
    ) {
        this.promptInjectionGuard = promptInjectionGuard;
        this.intentRiskFilter = intentRiskFilter;
        this.highRiskCommandDetector = highRiskCommandDetector;
        this.agenticRiskScoreEngine = agenticRiskScoreEngine;
        this.mcpToolCatalog = mcpToolCatalog;
        this.readOnlySurfaceDenylist = readOnlySurfaceDenylist;
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
        return evaluate(toolName, parameters, null, null, profile);
    }

    /**
     * @param userUtterance 用户原始自然语言（工作台/MCP 表单），与 tool 构造串一并参与意图与注入判定。
     */
    public GateDecision evaluate(String toolName, Map<String, Object> parameters, String userUtterance,
                                 McpSecurityProfile profile) {
        return evaluate(toolName, parameters, null, userUtterance, profile);
    }

    private GateDecision evaluate(String toolName, Map<String, Object> parameters, String instructionOverride,
                                  String userUtterance, McpSecurityProfile profile) {
        if (toolName == null || toolName.isBlank()) {
            return GateDecision.block("TOOL_NAME_EMPTY", "工具名称不能为空");
        }
        if (!toolName.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
            log.warn("非法工具名格式: {}", toolName);
            return GateDecision.block("TOOL_NAME_REJECTED", "工具名称格式不合法");
        }
        if (!mcpToolCatalog.isHttpAllowed(toolName)) {
            return GateDecision.block("UNKNOWN_TOOL", "未知或未授权的工具: " + toolName);
        }

        if (profile == McpSecurityProfile.INITIAL_REQUEST || profile == McpSecurityProfile.CHAT_AGENT_TOOL) {
            OpsSecurityContext.Ctx secCtx = OpsSecurityContext.get();
            if (secCtx != null && secCtx.getToolSurface() == McpToolSurface.READ_ONLY
                    && readOnlySurfaceDenylist.denies(toolName)) {
                return GateDecision.block("READ_ONLY_TOOL_SURFACE",
                        "当前会话处于只读工具面：禁止调用 " + toolName + "。请降低指令风险或使用 MCP 控制台在低风险上下文中执行。");
            }
        }

        if (parameters != null && parameters.size() > maxParameterEntries) {
            return GateDecision.block("PARAM_TOO_MANY", "参数项过多，请缩减请求体");
        }
        String tooLargeValue = findOversizedParameter(parameters);
        if (tooLargeValue != null) {
            return GateDecision.block("PARAM_TOO_LARGE", "参数「" + tooLargeValue + "」体积超限");
        }

        String instruction = instructionOverride != null && !instructionOverride.isBlank()
                ? instructionOverride.trim()
                : buildInstruction(toolName, parameters);
        if (instruction.length() > maxInstructionLength) {
            return GateDecision.block("INSTRUCTION_TOO_LONG",
                    "指令串长度超过上限（" + maxInstructionLength + "），请缩短路径或分批查询");
        }

        String riskText = mergeRiskUtterance(userUtterance, instruction);
        if (promptInjectionGuard.isInjection(riskText)) {
            return GateDecision.block("INJECTION", "安全拦截：检测到提示注入特征");
        }

        AgenticRiskScoreEngine.ScoreResult scoreResult = agenticRiskScoreEngine.score(toolName, parameters, riskText);
        double autoMax = agenticRiskScoreEngine.getAutoMax();
        double confirmMax = agenticRiskScoreEngine.getConfirmMax();

        RiskLevel intentRisk = maxRiskLevel(intentRiskFilter.evaluate(instruction),
                evaluateUserUtteranceRisk(userUtterance));
        if (intentRisk == RiskLevel.HIGH) {
            return GateDecision.block("HIGH_INTENT", "安全拦截：高风险意图，禁止自动执行",
                    scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }
        if (isDestructiveUtteranceMismatch(userUtterance, toolName)) {
            return GateDecision.block("INTENT_TOOL_MISMATCH",
                    "安全拦截：用户表述为破坏性/删除类操作，与只读观测工具不匹配，已拒绝以防误导读",
                    scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }
        if (scoreResult.total() > confirmMax) {
            return GateDecision.block("RISK_SCORE_HIGH", String.format(Locale.ROOT,
                    "风险评分 %.1f/10 超过可执行上限 %.0f，已拒绝。%s",
                    scoreResult.total(), confirmMax, scoreResult.explanation()),
                    scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }
        if (highRiskCommandDetector.isHighRiskCommand(riskText)) {
            return GateDecision.block("HIGH_RISK_COMMAND", "安全拦截：指令构造命中高危命令模式",
                    scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }

        boolean needByScore = profile == McpSecurityProfile.INITIAL_REQUEST
                && scoreResult.total() >= autoMax
                && scoreResult.total() <= confirmMax;
        boolean needByIntent = profile == McpSecurityProfile.INITIAL_REQUEST
                && intentRisk == RiskLevel.MEDIUM;
        if (profile == McpSecurityProfile.CHAT_AGENT_TOOL) {
            return GateDecision.allow(intentRisk, scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }
        if (needByScore || needByIntent) {
            String extra = needByIntent && !needByScore ? "意图为中等风险；" : "";
            String msg = String.format(Locale.ROOT,
                    "Agentic 风险分 %.1f/10（<%.0f 倾向自动，[%.0f,%.0f] 为确认区间）。%s请在界面点击确认后执行。",
                    scoreResult.total(), autoMax, autoMax, confirmMax, extra);
            return GateDecision.needConfirm(
                    needByIntent ? intentRisk : RiskLevel.MEDIUM,
                    msg,
                    scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
        }

        return GateDecision.allow(intentRisk, scoreResult.total(), scoreResult.dimensions(), scoreResult.explanation());
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

    /**
     * 工作台 ChatClient 多步 {@code @Tool}：与 HTTP 共用评分/注入/意图规则，但不触发 NEED_CONFIRM（由工具层 dry-run/confirm 参数约束）。
     */
    public GateDecision evaluateChatToolInvocation(String toolBeanName, String instruction) {
        String instr = instruction == null ? "" : instruction.trim();
        return evaluate(toolBeanName, Map.of(), instr, null, McpSecurityProfile.CHAT_AGENT_TOOL);
    }

    private static String mergeRiskUtterance(String userUtterance, String instruction) {
        String inst = instruction == null ? "" : instruction.trim();
        String user = userUtterance == null ? "" : userUtterance.trim();
        if (user.isEmpty()) {
            return inst;
        }
        if (inst.isEmpty()) {
            return user;
        }
        return user + "\n" + inst;
    }

    private RiskLevel evaluateUserUtteranceRisk(String userUtterance) {
        if (userUtterance == null || userUtterance.isBlank()) {
            return RiskLevel.LOW;
        }
        return intentRiskFilter.evaluate(userUtterance.trim());
    }

    private static RiskLevel maxRiskLevel(RiskLevel a, RiskLevel b) {
        if (a == RiskLevel.HIGH || b == RiskLevel.HIGH) {
            return RiskLevel.HIGH;
        }
        if (a == RiskLevel.MEDIUM || b == RiskLevel.MEDIUM) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private boolean isDestructiveUtteranceMismatch(String userUtterance, String toolName) {
        if (userUtterance == null || userUtterance.isBlank() || toolName == null) {
            return false;
        }
        if (!mcpToolCatalog.isReadOnlyObservation(toolName)) {
            return false;
        }
        String u = userUtterance.trim();
        if (intentRiskFilter.evaluate(u) == RiskLevel.HIGH) {
            return true;
        }
        return DESTRUCTIVE_UTTERANCE_CN.matcher(u).find();
    }

    public Set<String> allowedToolNames() {
        return mcpToolCatalog.getHttpAllowedToolNames();
    }

    public enum McpSecurityProfile {
        INITIAL_REQUEST,
        POST_CONFIRMATION,
        DEFERRED_SCHEDULED,
        CHAT_AGENT_TOOL
    }

    public static final class GateDecision {
        private final Type type;
        private final RiskLevel riskLevel;
        private final String code;
        private final String message;
        private final Double agenticRiskScore;
        private final Map<String, Double> agenticRiskDimensions;
        private final String agenticRiskExplanation;

        private GateDecision(Type type, RiskLevel riskLevel, String code, String message,
                             Double agenticRiskScore, Map<String, Double> agenticRiskDimensions,
                             String agenticRiskExplanation) {
            this.type = type;
            this.riskLevel = riskLevel;
            this.code = code;
            this.message = message;
            this.agenticRiskScore = agenticRiskScore;
            this.agenticRiskDimensions = agenticRiskDimensions;
            this.agenticRiskExplanation = agenticRiskExplanation;
        }

        public static GateDecision allow(RiskLevel risk, double score, Map<String, Double> dimensions, String explanation) {
            return new GateDecision(Type.ALLOW, risk, "OK", null, score, dimensions, explanation);
        }

        public static GateDecision needConfirm(RiskLevel risk, String userMessage, double score,
                                              Map<String, Double> dimensions, String explanation) {
            return new GateDecision(Type.NEED_CONFIRM, risk, "NEED_CONFIRM", userMessage, score, dimensions, explanation);
        }

        public static GateDecision block(String code, String message) {
            return new GateDecision(Type.BLOCK, RiskLevel.HIGH, code, message, null, null, null);
        }

        public static GateDecision block(String code, String message, Double score, Map<String, Double> dimensions,
                                        String explanation) {
            return new GateDecision(Type.BLOCK, RiskLevel.HIGH, code, message, score, dimensions, explanation);
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

        public Double getAgenticRiskScore() {
            return agenticRiskScore;
        }

        public Map<String, Double> getAgenticRiskDimensions() {
            return agenticRiskDimensions;
        }

        public String getAgenticRiskExplanation() {
            return agenticRiskExplanation;
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
