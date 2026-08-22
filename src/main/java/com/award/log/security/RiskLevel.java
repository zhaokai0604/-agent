package com.award.log.security;

/**
 * 意图/指令风险等级：HIGH 拦截，MEDIUM 需确认，LOW 放行。
 * 仅表示意图层结论，不替代工具写门控与治理准入。
 */
public enum RiskLevel {
    HIGH,
    MEDIUM,
    LOW
}