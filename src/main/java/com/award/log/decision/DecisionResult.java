package com.award.log.decision;

import lombok.Builder;
import lombok.Data;

/**
 * 单次决策输出：引擎类型、是否告警、置信度及可审计的原因/建议。
 * 由规则、随机森林、LLM 或融合层产出，不绑定具体存储模型。
 */
@Data
@Builder
public class DecisionResult {
    private EngineType engineType;
    private boolean shouldAlert;
    private double confidence;
    private String featureVersion;
    private String modelVersion;
    private String reason;
    private String recommendation;
}
