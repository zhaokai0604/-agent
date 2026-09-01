package com.award.log.agent.audit;

import java.util.List;

/**
 * 运维闭环相位常量。
 * <p>赛题六步（receive→…→verify）保持兼容；产品主链另增 approval / recover / audit。
 */
public final class OpsAuditPhases {

    public static final String RECEIVE = "receive";
    public static final String PERCEIVE = "perceive";
    public static final String REASON = "reason";
    public static final String SECURITY = "security";
    public static final String EXECUTE = "execute";
    public static final String VERIFY = "verify";

    /** 受控处置主链扩展相位 */
    public static final String PLAN = "plan";
    public static final String PREVIEW = "preview";
    public static final String APPROVAL = "approval";
    public static final String RECOVER = "recover";
    public static final String AUDIT = "audit";

    public static final String RECEIVE_CN = "接收";
    public static final String PERCEIVE_CN = "感知";
    public static final String REASON_CN = "推理";
    public static final String SECURITY_CN = "安全校验";
    public static final String EXECUTE_CN = "执行";
    public static final String VERIFY_CN = "验证";
    public static final String PLAN_CN = "计划";
    public static final String PREVIEW_CN = "预览";
    public static final String APPROVAL_CN = "确认";
    public static final String RECOVER_CN = "恢复";
    public static final String AUDIT_CN = "审计";

    private OpsAuditPhases() {
    }

    /**
     * 产品主链：Plan → Preview → Approval → Execute → Verify → Recover → Audit。
     */
    public static List<String> remediationChain() {
        return List.of(PLAN, PREVIEW, APPROVAL, EXECUTE, VERIFY, RECOVER, AUDIT);
    }

    public static List<String> remediationChainCn() {
        return List.of(PLAN_CN, PREVIEW_CN, APPROVAL_CN, EXECUTE_CN, VERIFY_CN, RECOVER_CN, AUDIT_CN);
    }

    /** 按 1–6 步返回中文标题（赛题兼容），超出范围回退「步骤N」。 */
    public static String titleCn(int stepIndex) {
        return switch (stepIndex) {
            case 1 -> RECEIVE_CN;
            case 2 -> PERCEIVE_CN;
            case 3 -> REASON_CN;
            case 4 -> SECURITY_CN;
            case 5 -> EXECUTE_CN;
            case 6 -> VERIFY_CN;
            default -> "步骤" + stepIndex;
        };
    }

    public static String remediationTitleCn(String phase) {
        if (phase == null) {
            return "";
        }
        return switch (phase.trim().toLowerCase()) {
            case PLAN -> PLAN_CN;
            case PREVIEW -> PREVIEW_CN;
            case APPROVAL -> APPROVAL_CN;
            case EXECUTE -> EXECUTE_CN;
            case VERIFY -> VERIFY_CN;
            case RECOVER -> RECOVER_CN;
            case AUDIT -> AUDIT_CN;
            case RECEIVE -> RECEIVE_CN;
            case PERCEIVE -> PERCEIVE_CN;
            case REASON -> REASON_CN;
            case SECURITY -> SECURITY_CN;
            default -> phase;
        };
    }
}
