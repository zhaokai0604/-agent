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
        HOLDER.set(Ctx.builder()
                .traceId(traceId)
                .userMessage(userMessage)
                .skipPerToolCheck(skipPerToolCheck)
                .toolSurface(toolSurface != null ? toolSurface : McpToolSurface.FULL)
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
    }
}
