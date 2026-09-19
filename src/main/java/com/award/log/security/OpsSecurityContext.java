package com.award.log.security;

import lombok.Builder;
import lombok.Data;

/**
 * 线程内运维安全上下文：用于 AI 多步调 Tool 时做与 HTTP 层一致的安全校验。
 */
public final class OpsSecurityContext {

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private OpsSecurityContext() {
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck) {
        open(traceId, userMessage, skipPerToolCheck, McpToolSurface.FULL);
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck, McpToolSurface toolSurface) {
        open(traceId, userMessage, skipPerToolCheck, toolSurface, false, false, false);
    }

    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface) {
        openChatAgent(traceId, userMessage, toolSurface, false);
    }

    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface,
                                     boolean userConfirmedWrite) {
        openChatAgent(traceId, userMessage, toolSurface, userConfirmedWrite, false);
    }

    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface,
                                     boolean userConfirmedWrite, boolean openToolPermissions) {
        openChatAgent(traceId, userMessage, toolSurface, userConfirmedWrite, openToolPermissions, "anonymous");
    }

    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface,
                                     boolean userConfirmedWrite, boolean openToolPermissions,
                                     String requester) {
        openChatAgent(traceId, userMessage, toolSurface, userConfirmedWrite,
                openToolPermissions, requester, "");
    }

    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface,
                                     boolean userConfirmedWrite, boolean openToolPermissions,
                                     String requester, String sessionId) {
        boolean effectiveWrite = userConfirmedWrite || openToolPermissions;
        open(traceId, userMessage, false, toolSurface, true, effectiveWrite,
                openToolPermissions, requester, sessionId);
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck,
                            McpToolSurface toolSurface, boolean chatAgentPath, boolean userConfirmedWrite) {
        open(traceId, userMessage, skipPerToolCheck, toolSurface, chatAgentPath, userConfirmedWrite, false);
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck,
                            McpToolSurface toolSurface, boolean chatAgentPath, boolean userConfirmedWrite,
                            boolean openToolPermissions) {
        open(traceId, userMessage, skipPerToolCheck, toolSurface, chatAgentPath,
                userConfirmedWrite, openToolPermissions, "anonymous");
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck,
                            McpToolSurface toolSurface, boolean chatAgentPath, boolean userConfirmedWrite,
                            boolean openToolPermissions, String requester) {
        open(traceId, userMessage, skipPerToolCheck, toolSurface, chatAgentPath,
                userConfirmedWrite, openToolPermissions, requester, "");
    }

    public static void open(String traceId, String userMessage, boolean skipPerToolCheck,
                            McpToolSurface toolSurface, boolean chatAgentPath, boolean userConfirmedWrite,
                            boolean openToolPermissions, String requester, String sessionId) {
        HOLDER.set(Ctx.builder()
                .traceId(traceId)
                .userMessage(userMessage)
                .skipPerToolCheck(skipPerToolCheck)
                .toolSurface(toolSurface != null ? toolSurface : McpToolSurface.FULL)
                .chatAgentPath(chatAgentPath)
                .userConfirmedWrite(userConfirmedWrite || openToolPermissions)
                .openToolPermissions(openToolPermissions)
                .requester(requester)
                .sessionId(sessionId == null ? "" : sessionId)
                .build());
    }

    public static Ctx get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    @Data
    @Builder
    public static class Ctx {
        private String traceId;
        private String userMessage;
        private boolean skipPerToolCheck;
        /** 工具面：AI 多步调用路径下用于收缩写类工具 */
        @Builder.Default
        private McpToolSurface toolSurface = McpToolSurface.FULL;
        /** 来自统一助手对话 ChatClient，写操作须走工具箱确认 */
        @Builder.Default
        private boolean chatAgentPath = false;
        /** HTTP MCP 二次确认通过后为 true（预留） */
        @Builder.Default
        private boolean userConfirmedWrite = false;
        /** 对话开放工具权限：挂载全量工具并跳过确认门，仍走注入/治理等硬护栏 */
        @Builder.Default
        private boolean openToolPermissions = false;
        @Builder.Default
        private String requester = "anonymous";
        /** 前端 Agent 会话标识，用于把预览、确认和审计链路绑定到同一会话。 */
        @Builder.Default
        private String sessionId = "";
    }
}
