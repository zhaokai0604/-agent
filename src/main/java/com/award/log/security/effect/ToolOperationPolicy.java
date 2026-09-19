package com.award.log.security.effect;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具的最小权限目录：权限不能只看 Bean 名称，混合工具必须继续看 operation。
 *
 * <p>这里的只读集合是“纯只读”集合；不在集合中的已知工具一律按可写工具处理。
 * 这是一条保守的安全默认值，不能替代工具自身的 dry-run/confirm 参数校验。</p>
 */
public final class ToolOperationPolicy {

    private static final Set<String> READ_ONLY_ONLY_TOOLS = Set.of(
            "DiskTool", "DiskInsightTool", "DiskAnalyzeTool", "SystemLoadTool",
            "LogAnalysisTool", "NetworkTool", "ConfigCheckTool", "ConfigDriftTool",
            "PrivilegeTool", "OsInsightTool", "PortHealthTool", "FirewallTool",
            "CronJobTool", "SslCertTool", "K8sTool", "OpsAnalysisSkillTool");

    private static final Set<String> KNOWN_WRITE_CAPABLE_TOOLS = Set.of(
            "CleanTempTool", "LogCleanupTool", "ServiceRestartTool", "DiskOpsTool",
            "LogOpsTool", "ServiceOpsTool", "ContainerOpsTool", "ProcessOpsTool",
            "ProcessTool", "DockerTool", "SystemdTool", "RecycleBinTool",
            "AutonomousOpsTool");

    private ToolOperationPolicy() {
    }

    public static boolean isReadOnlyOnlyTool(String toolName) {
        return toolName != null && READ_ONLY_ONLY_TOOLS.contains(toolName.trim());
    }

    /** 未知工具不在这里放行；未知工具仍由工具目录/安全网关拒绝。 */
    public static boolean isWriteCapableTool(String toolName) {
        return toolName != null && KNOWN_WRITE_CAPABLE_TOOLS.contains(toolName.trim());
    }

    /** 计划只有工具名没有参数时，按该工具网关的默认 operation 计算。 */
    public static boolean isPlannedWriteTool(String toolName) {
        return isWriteOperation(toolName, Map.of());
    }

    public static Set<String> readOnlyOnlyTools() {
        return READ_ONLY_ONLY_TOOLS;
    }

    public static Set<String> writeCapableTools() {
        return KNOWN_WRITE_CAPABLE_TOOLS;
    }

    /**
     * 判断当前调用是否会触及写操作。纯只读工具始终为 false；混合工具根据 operation 判断。
     * operation 缺失时按各工具真实网关默认值推断，无法安全判断时返回 true。
     */
    public static boolean isWriteOperation(String toolName, Map<String, Object> parameters) {
        String tool = normalize(toolName);
        if (tool.isBlank() || isReadOnlyOnlyTool(tool)) {
            return false;
        }
        if (!isWriteCapableTool(tool)) {
            return true;
        }

        String operation = first(parameters, "operation", "op", "action");
        String op = operation.toLowerCase(Locale.ROOT);
        return switch (tool) {
            case "DiskOpsTool" -> !op.isBlank()
                    && !isOneOf(op, "df", "hotspots", "analyze", "usage", "inspect", "read");
            case "LogOpsTool" -> !op.isBlank() && !isOneOf(op, "analyze", "read", "scan");
            case "ServiceOpsTool", "SystemdTool" -> !op.isBlank()
                    && !isOneOf(op, "failed", "status", "get", "list", "inspect", "read");
            case "ContainerOpsTool", "DockerTool" -> isContainerWrite(op, parameters);
            case "ProcessOpsTool", "ProcessTool" -> isProcessWrite(op, parameters);
            case "RecycleBinTool" -> isOneOf(op, "restore", "purge", "delete", "clean", "cleanup")
                    || (op.isBlank() && hasAny(parameters, "handle"));
            default -> true;
        };
    }

    /** 给前端/审计 DTO 使用的 operation 权限表，统一返回 READ_ONLY/WRITE_REQUESTED。 */
    public static Map<String, String> operationPermissions(String toolName, List<String> operations) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (operations != null) {
            for (String operation : operations) {
                if (operation == null || operation.isBlank()) {
                    continue;
                }
                result.put(operation, isWriteOperation(toolName, Map.of("operation", operation))
                        ? "WRITE_REQUESTED" : "READ_ONLY");
            }
        }
        result.putIfAbsent("default", isWriteOperation(toolName, Map.of())
                ? "WRITE_REQUESTED" : "READ_ONLY");
        return Map.copyOf(result);
    }

    private static boolean isContainerWrite(String op, Map<String, Object> parameters) {
        if (!op.isBlank()) {
            return !isOneOf(op, "list", "ps", "inspect", "status", "read");
        }
        return hasWriteConfirmation(parameters);
    }

    private static boolean isProcessWrite(String op, Map<String, Object> parameters) {
        if (!op.isBlank()) {
            return !isOneOf(op, "list", "ps", "inspect", "status", "read");
        }
        // ProcessTool's gateway defaults to list. A PID plus write flags is the
        // only safe signal that a direct callback intended terminateProcess.
        return hasWriteConfirmation(parameters);
    }

    private static boolean hasWriteConfirmation(Map<String, Object> parameters) {
        if (parameters == null) {
            return false;
        }
        return isTrueAny(parameters, "confirmDelete", "confirmRestart", "confirmKill", "confirmStop")
                || Boolean.FALSE.equals(booleanValue(parameters.get("dryRun")));
    }

    private static boolean hasAny(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (parameters.containsKey(key) && parameters.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrueAny(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (Boolean.TRUE.equals(booleanValue(parameters.get(key)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String first(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value != null && !String.valueOf(value).isBlank()
                    && !"null".equalsIgnoreCase(String.valueOf(value).trim())) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
