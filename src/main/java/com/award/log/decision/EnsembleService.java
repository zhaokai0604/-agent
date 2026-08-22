package com.award.log.decision;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 多引擎融合：对规则、随机森林、LLM 三路结果做加权置信度与多数投票告警。
 * 推荐文案取置信度最高的一路；不负责调度各引擎执行。
 */
@Service
public class EnsembleService {

    /** 三路融合：告警按多数票，置信度取加权与赢家中的较大值。 */
    public DecisionResult fuse(DecisionResult rule, DecisionResult rf, DecisionResult llm) {
        double weighted = rule.getConfidence() * 0.4 + rf.getConfidence() * 0.35 + llm.getConfidence() * 0.25;
        int alertCount = (rule.isShouldAlert() ? 1 : 0) + (rf.isShouldAlert() ? 1 : 0) + (llm.isShouldAlert() ? 1 : 0);
        boolean shouldAlert = alertCount >= 2;
        DecisionResult winner = rule;
        if (rf.getConfidence() > winner.getConfidence()) {
            winner = rf;
        }
        if (llm.getConfidence() > winner.getConfidence()) {
            winner = llm;
        }
        return DecisionResult.builder()
                .engineType(winner.getEngineType())
                .shouldAlert(shouldAlert)
                .confidence(Math.max(weighted, winner.getConfidence()))
                .featureVersion(winner.getFeatureVersion())
                .modelVersion(winner.getModelVersion())
                .reason("融合投票结果，详细=" + Map.of("rule", rule.isShouldAlert(), "rf", rf.isShouldAlert(), "llm", llm.isShouldAlert()))
                .recommendation(winner.getRecommendation())
                .build();
    }
}
