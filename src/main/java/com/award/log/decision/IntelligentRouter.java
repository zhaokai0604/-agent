package com.award.log.decision;

import org.springframework.stereotype.Component;

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

    public DecisionResult decide(DecisionInput input) {
        DecisionResult rule = ruleEngineV2.evaluate(input);
        DecisionResult rf = randomForestDecisionEngine.evaluate(input);
        DecisionResult llm = llmDecisionEngine.evaluate(input);
        return ensembleService.fuse(rule, rf, llm);
    }
}
