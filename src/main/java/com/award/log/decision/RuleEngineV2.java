package com.award.log.decision;

import com.award.log.model.RuleDefinition;
import com.award.log.service.RuleRegistryService;
import com.award.log.service.RuleStatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RuleEngineV2 {

    private final RuleRegistryService ruleRegistryService;
    private final RuleStatService ruleStatService;

    @Value("${log.pipeline.decision.rule.high-confidence:0.9}")
    private double highConfidence;

    @Value("${log.pipeline.decision.feature-version:rf-v2}")
    private String featureVersion;

    public RuleEngineV2(RuleRegistryService ruleRegistryService,
                        RuleStatService ruleStatService) {
        this.ruleRegistryService = ruleRegistryService;
        this.ruleStatService = ruleStatService;
    }

    public DecisionResult evaluate(DecisionInput input) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("LEVEL", input.getEvent().getLevel());
        ctx.put("RATE", input.getErrorRate1m());
        ctx.put("COUNT", input.getError1m());
        ctx.put("SUPPRESSED", false);

        boolean hit = false;
        String ruleName = "none";
        for (RuleDefinition rule : ruleRegistryService.list()) {
            boolean matched = ruleRegistryService.testRule(rule.getId(), ctx);
            ruleStatService.record(rule.getId(), rule.getName(), matched);
            if (matched) {
                hit = true;
                ruleName = rule.getName();
                break;
            }
        }
        return DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(hit)
                .confidence(hit ? highConfidence : 0.4)
                .featureVersion(featureVersion)
                .modelVersion("rule-v2")
                .reason("规则引擎V2命中规则: " + ruleName)
                .recommendation("优先执行Runbook并标记告警生命周期")
                .build();
    }
}
