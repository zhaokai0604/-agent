package com.award.log.security;

import com.award.log.mcp.WriteToolResultSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 记录对话 ChatClient 本轮实际调用的 MCP 工具结果，用于拦截「未执行却声称已删除」的幻觉回复。
 */
public final class ChatToolExecutionTracker {

    public record ToolInvocation(String toolName, boolean success, String mode) {
    }

    private static final ThreadLocal<List<ToolInvocation>> INVOCATIONS = ThreadLocal.withInitial(ArrayList::new);

    private ChatToolExecutionTracker() {
    }

    public static void record(String toolName, String toolResultJson) {
        if (toolName == null || toolResultJson == null) {
            return;
        }
        boolean success = WriteToolResultSupport.isToolSuccess(toolResultJson);
        String mode = WriteToolResultSupport.extractMode(toolResultJson);
        if ((mode == null || mode.isBlank()) && WriteToolResultSupport.isConfirmedRealWrite(toolResultJson)) {
            mode = "EXECUTED";
        }
        INVOCATIONS.get().add(new ToolInvocation(toolName, success, mode));
    }

    public static List<ToolInvocation> snapshot() {
        return List.copyOf(INVOCATIONS.get());
    }

    public static void clear() {
        INVOCATIONS.remove();
    }

    public static boolean hasSuccessfulDelete() {
        return INVOCATIONS.get().stream()
                .anyMatch(i -> i.success() && WriteToolResultSupport.isRealWriteMode(i.mode()));
    }

    public static String sanitizeUnverifiedExecutionClaims(String content, boolean allowWrite) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (allowWrite && hasSuccessfulDelete()) {
            return content;
        }
        if (!claimsRealExecution(content)) {
            return content;
        }
        return content
                + "\n\n> **系统校正**：本轮未检测到经 MCP 返回 `mode=DELETE` 的成功写操作，上述「已删除/已执行」结论无效。"
                + "请使用 **工具控制台** 填写 `dryRun=false`、`confirmDelete=true`（删目录加 `removeDirectory=true`）后执行，"
                + "或在对话中说「**确认执行**」走 Playbook。\n";
    }

    private static boolean claimsRealExecution(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("已执行删除")
                || lower.contains("真实删除")
                || lower.contains("dryrun=false")
                || lower.contains("execution_accepted")
                || (lower.contains("已删除") && !lower.contains("预览") && !lower.contains("dry-run"));
    }
}
