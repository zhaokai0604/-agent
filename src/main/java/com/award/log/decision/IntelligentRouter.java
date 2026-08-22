package com.award.log.decision;

import org.springframework.stereotype.Component;

/**
 * 协同决策路由器：依次调用规则 V2、随机森林与 LLM，再交给融合层。
 * {@code skipLlm=true} 时用空 LLM 结果参与融合，避免批量场景放大模型调用。
 */
@Component
public class IntelligentRouter {

    private final RuleEngineV2 ruleEngineV2;
    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final LlmDecisionEngine llmDecisionEngine;
    private final EnsembleService ensembleService;

    public IntelligentRouter(RuleEngineV2 ruleEngineV2,
                             RandomForestDecisionEngine randomForestDecisionEngine,
                             LlmDecisionEngine llmDecisionEngine,
                             EnsembleService ensembleService) {
        this.ruleEngineV2 = ruleEngineV2;
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.llmDecisionEngine = llmDecisionEngine;
        this.ensembleService = ensembleService;
    }

    /** 按输入路由三引擎并融合；批量模式跳过真实 LLM 调用。 */
    public DecisionResult decide(DecisionInput input) {
        DecisionResult rule = ruleEngineV2.evaluate(input);
        DecisionResult rf = randomForestDecisionEngine.evaluate(input);
        if (input != null && input.isSkipLlm()) {
            return ensembleService.fuse(rule, rf, DecisionResult.builder()
                    .engineType(EngineType.LLM)
                    .shouldAlert(false)
                    .confidence(0.0)
                    .featureVersion(rule.getFeatureVersion())
                    .modelVersion("skipped")
                    .reason("批量模式跳过 LLM")
                    .recommendation("")
                    .build());
        }
        DecisionResult llm = llmDecisionEngine.evaluate(input);
        return ensembleService.fuse(rule, rf, llm);
    }
}
