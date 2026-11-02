package com.award.log.security;


/**
 * 对话 / 助手路径下写操作 dry-run 与确认位统一裁决，避免 LLM 漏传 confirmDelete 导致永远只预览。
 */
public final class ChatWriteExecutionPolicy {

    public record ResolvedWrite(boolean dryRun, boolean confirmDelete, boolean removeDirectory, int days) {
    }

    public record ResolvedRestart(boolean dryRun, boolean confirmRestart) {
    }

    public record ResolvedKill(boolean dryRun, boolean confirmKill) {
    }

    public record ResolvedStop(boolean dryRun, boolean confirmStop) {
    }

    public static ResolvedRestart resolveRestart(Boolean dryRun, Boolean confirmRestart) {
        if(Boolean.TRUE.equals(dryRun)) return new ResolvedRestart(true,false);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            return new ResolvedRestart(false, true);
        }
        if (ctx != null && ctx.isChatAgentPath()) {
            return new ResolvedRestart(true, false);
        }
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);
        return new ResolvedRestart(isDryRun, Boolean.TRUE.equals(confirmRestart));
    }

    public static ResolvedKill resolveKill(Boolean dryRun, Boolean confirmKill) {
        if(Boolean.TRUE.equals(dryRun)) return new ResolvedKill(true,false);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            return new ResolvedKill(false, true);
        }
        if (ctx != null && ctx.isChatAgentPath()) {
            return new ResolvedKill(true, false);
        }
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);
        return new ResolvedKill(isDryRun, Boolean.TRUE.equals(confirmKill));
    }

    public static ResolvedStop resolveStop(Boolean dryRun, Boolean confirmStop) {
        if(Boolean.TRUE.equals(dryRun)) return new ResolvedStop(true,false);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            return new ResolvedStop(false, true);
        }
        if (ctx != null && ctx.isChatAgentPath()) {
            return new ResolvedStop(true, false);
        }
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);
        return new ResolvedStop(isDryRun, Boolean.TRUE.equals(confirmStop));
    }

    private ChatWriteExecutionPolicy() {
    }

    public static ResolvedWrite resolve(
            OpsPathPolicy opsPathPolicy,
            String path,
            Integer days,
            Boolean dryRun,
            Boolean confirmDelete,
            Boolean removeDirectory) {

        int resolvedDays = days != null ? days : 7;
        boolean resolvedRemoveDir = Boolean.TRUE.equals(removeDirectory);
        // 路径形态不能擅自把文件清理扩大为整目录操作。
        if(Boolean.TRUE.equals(dryRun)) return new ResolvedWrite(true,false,resolvedRemoveDir,resolvedDays);

        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            if (resolvedRemoveDir) {
                resolvedDays = 0;
            }
            return new ResolvedWrite(false, true, resolvedRemoveDir, resolvedDays);
        }
        if (ctx != null && ctx.isChatAgentPath()) {
            return new ResolvedWrite(true, false, resolvedRemoveDir, resolvedDays);
        }

        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);
        boolean confirmed = Boolean.TRUE.equals(confirmDelete);
        if (resolvedRemoveDir && !isDryRun && confirmed) {
            resolvedDays = 0;
        }
        return new ResolvedWrite(isDryRun, confirmed, resolvedRemoveDir, resolvedDays);
    }
}
