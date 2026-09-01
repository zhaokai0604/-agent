package com.award.log.security.effect;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 将工具名 + 参数归一为 {@link ToolEffect}。
 */
@Component
public class ToolEffectResolver {

    public ToolEffect resolve(String toolName, Map<String, Object> parameters) {
        String tool = toolName == null ? "" : toolName.trim();
        String path = firstString(parameters, "path", "rootPath", "logPath", "targetPath");
        String service = firstString(parameters, "serviceName", "service", "unit");
        String pid = firstString(parameters, "pid", "processId");

        return switch (tool) {
            case "CleanTempTool" -> writePath(EffectAction.DELETE, path, "DELETE_PATH");
            case "DiskOpsTool" -> {
                String op = firstString(parameters, "operation", "op", "action").toLowerCase(Locale.ROOT);
                if (op.isBlank() || op.equals("df") || op.equals("hotspots") || op.equals("analyze")
                        || op.equals("usage") || op.equals("inspect") || op.equals("read")) {
                    yield ToolEffect.observe(path.isBlank() ? "PATH" : "PATH", path.isBlank() ? "local" : path);
                }
                yield writePath(EffectAction.DELETE, path, "DELETE_PATH");
            }
            case "LogCleanupTool" -> writePath(
                    looksLikeTruncate(parameters) ? EffectAction.TRUNCATE : EffectAction.DELETE,
                    path,
                    "DELETE_PATH");
            case "LogOpsTool" -> {
                String op = firstString(parameters, "operation", "op", "action").toLowerCase(Locale.ROOT);
                if (op.isBlank() || op.equals("analyze") || op.equals("read") || op.equals("scan")) {
                    yield ToolEffect.observe(path.isBlank() ? "PATH" : "PATH", path.isBlank() ? "local" : path);
                }
                yield writePath(
                        looksLikeTruncate(parameters) ? EffectAction.TRUNCATE : EffectAction.DELETE,
                        path,
                        "DELETE_PATH");
            }
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" -> new ToolEffect(
                    EffectAction.RESTART, "SERVICE", service, 7, true, "RESTART_SERVICE");
            case "ProcessTool" -> new ToolEffect(
                    EffectAction.KILL, "PROCESS", pid.isBlank() ? firstString(parameters, "name") : pid,
                    6, true, "KILL_PROCESS");
            case "DockerTool" -> new ToolEffect(
                    EffectAction.KILL, "CONTAINER", firstString(parameters, "containerName", "name"),
                    6, true, "KILL_PROCESS");
            case "FirewallTool", "ConfigCheckTool", "ConfigDriftTool" -> new ToolEffect(
                    EffectAction.MUTATE_CONFIG, "CONFIG", path.isBlank() ? service : path,
                    5, true, "MUTATE_CONFIG");
            case "DiskTool", "DiskAnalyzeTool", "SystemLoadTool", "PortHealthTool",
                 "LogAnalysisTool", "SslCertTool", "PrivilegeTool", "K8sTool" ->
                    ToolEffect.observe(path.isBlank() ? "HOST" : "PATH", path.isBlank() ? "local" : path);
            case "RecycleBinTool" -> {
                String op = firstString(parameters, "operation", "op").toLowerCase(Locale.ROOT);
                if ("restore".equals(op)) {
                    yield writePath(EffectAction.DELETE, firstString(parameters, "handle", "path"), "DELETE_PATH");
                }
                yield ToolEffect.observe("RECYCLE", op.isBlank() ? "list" : op);
            }
            default -> inferDefault(tool, path, service, pid);
        };
    }

    private static ToolEffect writePath(EffectAction action, String path, String contractId) {
        return new ToolEffect(action, "PATH", path, action == EffectAction.TRUNCATE ? 5 : 6, true, contractId);
    }

    private static ToolEffect inferDefault(String tool, String path, String service, String pid) {
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.contains("clean") || lower.contains("delete") || lower.contains("remove")) {
            return writePath(EffectAction.DELETE, path, "DELETE_PATH");
        }
        if (lower.contains("restart")) {
            return new ToolEffect(EffectAction.RESTART, "SERVICE", service, 7, true, "RESTART_SERVICE");
        }
        if (lower.contains("kill") || lower.contains("stop")) {
            return new ToolEffect(EffectAction.KILL, "PROCESS", pid, 6, true, "KILL_PROCESS");
        }
        if (!path.isBlank()) {
            return ToolEffect.observe("PATH", path);
        }
        return new ToolEffect(EffectAction.UNKNOWN_WRITE, "UNKNOWN", tool, 4, true, "");
    }

    private static boolean looksLikeTruncate(Map<String, Object> parameters) {
        String op = firstString(parameters, "operation", "op", "action").toLowerCase(Locale.ROOT);
        return op.contains("truncat") || op.contains("裁剪") || op.contains("清空");
    }

    private static String firstString(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object v = parameters.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return "";
    }
}
