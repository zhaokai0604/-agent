package com.award.log.decision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 规则决策引擎 V1：按错误级别与 1 分钟窗口错误率判断是否告警。
 * 不读取动态规则库；动态 DSL 规则由 {@link RuleEngineV2} 负责。
 */
@Component
public class RuleDecisionEngine {

    @Value("${log.pipeline.decision.rule.high-confidence:0.9}")
    private double highConfidence;

    @Value("${log.pipeline.decision.feature-version:rf-v1}")
    private String featureVersion;

    /** 错误级别且 1 分钟错误率达阈值则告警。 */
    public DecisionResult evaluate(DecisionInput input) {
        boolean errorLevel = "ERROR".equals(input.getEvent().getLevel()) || "FATAL".equals(input.getEvent().getLevel());
        boolean highRate = input.getErrorRate1m() >= 0.30 && input.getTotal1m() >= 20;
        boolean shouldAlert = errorLevel && highRate;
        return DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(shouldAlert)
                .confidence(shouldAlert ? highConfidence : 0.40)
                .featureVersion(featureVersion)
                .modelVersion("rule-v1")
                .reason("规则判断: 错误级别 + 1min窗口错误率")
                .recommendation("检查最近5分钟同模板错误与实例健康状态")
                .build();
    }
}
