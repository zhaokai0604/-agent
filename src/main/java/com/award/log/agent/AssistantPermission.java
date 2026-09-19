package com.award.log.agent;

/**
 * 统一助手路由层的权限意图。它描述本轮允许进入的阶段，不等同于实际写权限；
 * 实际写入仍必须由 PEP / HMAC / 能力令牌和工具治理层共同放行。
 */
public enum AssistantPermission {

    /** 只允许观测和分析。 */
    READ_ONLY,

    /** 可以生成预览方案，但不得真实改变主机状态。 */
    PREVIEW_ONLY,

    /** 用户表达了写确认意图，仍需后端能力令牌和策略校验。 */
    WRITE_REQUESTED
}
