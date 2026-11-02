package com.award.log.security.effect;

import java.util.Locale;
import java.util.Map;

/**
 * 由工具名 + 参数 + {@link ToolEffect} 生成受控处置契约。
 */
public final class RemediationContractFactory {

    private RemediationContractFactory() {
    }

    public static WriteRemediationContract build(
            String toolName,
            Map<String, Object> parameters,
            ToolEffect effect,
            String requester) {
        ToolEffect te = effect != null
                ? effect
                : new ToolEffectResolver().resolve(toolName, parameters);
        ExpectedEffect expected = expectedFor(toolName, parameters, te);
        RecoverSpec recover = recoverFor(toolName, te);
        RemediationApproval approval = RemediationApproval.fromRequesterAndParams(requester, parameters);
        if (approval.riskLevel().isBlank() && te.writeEffect()) {
            approval = new RemediationApproval(
                    approval.operator(),
                    approval.confirmer(),
                    riskFromIrreversibility(te.irreversibility()),
                    approval.requireSecondPerson() || te.irreversibility() >= 7,
                    approval.ticketId());
        }
        return new WriteRemediationContract(
                expected,
                recover,
                approval,
                te.evidenceContractId(),
                te.irreversibility());
    }

    private static ExpectedEffect expectedFor(
            String toolName, Map<String, Object> parameters, ToolEffect effect) {
        if (effect == null || !effect.writeEffect()) {
            return ExpectedEffect.none();
        }
        String tool = toolName == null ? "" : toolName.trim();
        String path = first(parameters, "path", "rootPath", "logPath", "targetPath");
        if (path.isBlank()) {
            path = effect.targetId();
        }
        String service = first(parameters, "serviceName", "service", "unit");
        if (service.isBlank() && "SERVICE".equalsIgnoreCase(effect.targetType())) {
            service = effect.targetId();
        }
        return switch (tool) {
            case "RecycleBinTool" -> new ExpectedEffect("restore".equalsIgnoreCase(first(parameters, "operation", "op"))
                    ? "RESTORE_STATE" : "PURGE_STATE", "EQUALS", "true", "回收站操作完成并返回对应证据");
            case "CleanTempTool", "DiskOpsTool", "LogCleanupTool", "LogOpsTool" ->
                    ExpectedEffect.diskOrPathDecrease(path);
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" ->
                    ExpectedEffect.serviceActive(service);
            default -> switch (effect.action()) {
                case DELETE, TRUNCATE -> ExpectedEffect.diskOrPathDecrease(path);
                case RESTART -> ExpectedEffect.serviceActive(service);
                case OBSERVE -> ExpectedEffect.none();
                default -> new ExpectedEffect(
                        "WRITE_EFFECT",
                        "CHANGED",
                        "",
                        "写操作完成且具备落地证据");
            };
        };
    }

    private static RecoverSpec recoverFor(String toolName, ToolEffect effect) {
        if (effect == null || !effect.writeEffect()) {
            return RecoverSpec.none();
        }
        String tool = toolName == null ? "" : toolName.trim();
        return switch (tool) {
            case "CleanTempTool", "DiskOpsTool", "LogCleanupTool", "LogOpsTool" ->
                    RecoverSpec.recycleBin();
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" ->
                    RecoverSpec.irreversible(
                            "服务重启不可撤销；仅可补偿为再次 systemctl 操作或人工回滚配置，执行前须确认白名单与业务窗口");
            case "RecycleBinTool" -> effect.irreversibility() >= 7
                    ? RecoverSpec.irreversible("过期回收项永久清除后无法恢复") : RecoverSpec.none();
            default -> switch (effect.action()) {
                case DELETE, TRUNCATE -> RecoverSpec.recycleBin();
                case RESTART, KILL -> RecoverSpec.irreversible(
                        "该写动作不可自动撤销，执行前须明确标注不可逆风险");
                default -> effect.irreversibility() >= 7
                        ? RecoverSpec.irreversible("高不可逆分写操作，执行前须确认")
                        : RecoverSpec.recycleBin();
            };
        };
    }

    private static String riskFromIrreversibility(int score) {
        if (score >= 7) {
            return "HIGH";
        }
        if (score >= 4) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String first(Map<String, Object> parameters, String... keys) {
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
