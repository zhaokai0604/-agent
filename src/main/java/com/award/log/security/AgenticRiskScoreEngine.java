package com.award.log.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agentic AI 工具调用风险评分（0–10）：综合工具类型、路径敏感度、运行身份与指令文本特征。
 * 与 {@link McpInvocationSecurityGate} 阈值配合：&lt;autoMax 自动执行倾向；[autoMax, confirmMax] 二次确认；&gt;confirmMax 拒绝。
 */
@Component
public class AgenticRiskScoreEngine {

    private final double autoMax;
    private final double confirmMax;

    public AgenticRiskScoreEngine(
            @Value("${agent.security.risk-score-auto-max:3}") double autoMax,
            @Value("${agent.security.risk-score-confirm-max:7}") double confirmMax) {
        this.autoMax = clamp(autoMax, 0, 10);
        this.confirmMax = clamp(confirmMax, this.autoMax, 10);
    }

    public double getAutoMax() {
        return autoMax;
    }

    public double getConfirmMax() {
        return confirmMax;
    }

    /**
     * @param instruction {@link McpInvocationSecurityGate#buildInstruction} 产物
     */
    public ScoreResult score(String toolName, Map<String, Object> parameters, String instruction) {
        Map<String, Double> dims = new LinkedHashMap<>();
        double total = 0;

        double toolBase = baseToolRisk(toolName);
        dims.put("toolBase", round1(toolBase));
        total += toolBase;

        String haystack = (instruction == null ? "" : instruction).toLowerCase(Locale.ROOT);
        if (parameters != null) {
            for (Object v : parameters.values()) {
                if (v != null) {
                    haystack += " " + v.toString().toLowerCase(Locale.ROOT);
                }
            }
        }

        double pathRisk = pathSensitivityScore(haystack);
        dims.put("pathSensitivity", round1(pathRisk));
        total += pathRisk;

        double cmdRisk = commandPatternScore(haystack);
        dims.put("commandPattern", round1(cmdRisk));
        total += cmdRisk;

        double privRisk = privilegeContextScore();
        dims.put("runtimePrivilege", round1(privRisk));
        total += privRisk;

        total = clamp(round1(total), 0, 10);
        String explanation = buildExplanation(toolName, total, dims);
        return new ScoreResult(total, Map.copyOf(dims), explanation);
    }

    private static double baseToolRisk(String toolName) {
        if (toolName == null) {
            return 0;
        }
        return switch (toolName) {
            case "DiskTool", "ProcessTool", "SystemLoadTool", "LogAnalysisTool", "NetworkTool" -> 0.5;
            case "ConfigCheckTool", "DiskAnalyzeTool", "DiskInsightTool" -> 1.0;
            case "PrivilegeTool", "OsInsightTool" -> 2.0;
            case "CleanTempTool" -> 4.5;
            case "LogCleanupTool" -> 5.5;
            case "ServiceRestartTool" -> 7.0;
            default -> 2.5;
        };
    }

    private static double pathSensitivityScore(String haystack) {
        double s = 0;
        if (haystack.contains("/etc/shadow") || haystack.contains("/etc/gshadow")) {
            s += 6;
        }
        if (haystack.contains("/boot")) {
            s += 3;
        }
        if (haystack.contains("/etc")) {
            s += 2;
        }
        if (haystack.contains("/root") || haystack.contains("\\.ssh")) {
            s += 2.5;
        }
        if (haystack.contains("/sys") || haystack.contains("/proc")) {
            s += 1.5;
        }
        return Math.min(s, 6);
    }

    private static double commandPatternScore(String haystack) {
        double s = 0;
        if (haystack.contains("rm ") || haystack.contains("rm\t")) {
            s += 5;
        }
        if (haystack.contains("chmod ") || haystack.contains("chown ")) {
            s += 3;
        }
        if (haystack.contains("mkfs") || haystack.contains("dd ") || haystack.contains(":(){")) {
            s += 8;
        }
        if (haystack.contains("curl ") && haystack.contains("| sh")) {
            s += 6;
        }
        return Math.min(s, 8);
    }

    private static double privilegeContextScore() {
        String user = System.getProperty("user.name", "");
        if ("root".equalsIgnoreCase(user)) {
            return 2.0;
        }
        return 0;
    }

    private static String buildExplanation(String toolName, double total, Map<String, Double> dims) {
        return String.format(Locale.ROOT,
                "工具=%s 综合分=%.1f/10（维度: %s）",
                toolName == null ? "?" : toolName,
                total,
                dims.toString());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public record ScoreResult(double total, Map<String, Double> dimensions, String explanation) {
    }
}
