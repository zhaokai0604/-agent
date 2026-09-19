package com.award.log.decision;

import com.award.log.model.TraceLog;
import com.award.log.security.HighRiskCommandRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DecisionAndRuleValueCoverageTest {

    @Test
    void decisionResultAndDefaultRuleExposeStableValueContracts() {
        DecisionResult result = DecisionResult.builder()
                .engineType(EngineType.RANDOM_FOREST)
                .shouldAlert(true)
                .confidence(0.92)
                .featureVersion("v2")
                .modelVersion("rf")
                .reason("error rate")
                .recommendation("confirm")
                .build();
        assertEquals(EngineType.RANDOM_FOREST, result.getEngineType());
        assertTrue(result.isShouldAlert());
        assertEquals(0.92, result.getConfidence());
        assertEquals(result, DecisionResult.builder().engineType(EngineType.RANDOM_FOREST)
                .shouldAlert(true).confidence(0.92).featureVersion("v2").modelVersion("rf")
                .reason("error rate").recommendation("confirm").build());
        result.setRecommendation("preview");
        assertEquals("preview", result.getRecommendation());
        assertNotNull(result.toString());

        HighRiskCommandRule rule = HighRiskCommandRule.createDefaultRule();
        assertFalse(rule.isEnabled());
        assertTrue(rule.getRuleExpression().contains("RM"));
        rule.setEnabled(true);
        assertTrue(rule.isEnabled());
        assertNotNull(rule.toString());
    }

    @Test
    void traceLogBuilderAndGeneratedAccessorsRoundTrip() {
        LocalDateTime now = LocalDateTime.now();
        TraceLog trace = TraceLog.builder().traceId("t1").userInput("check")
                .riskLevel("LOW").toolName("DiskTool").resultSummary("ok")
                .durationMs(12).timestamp(now).build();
        assertEquals("t1", trace.getTraceId());
        assertEquals(12, trace.getDurationMs());
        trace.setRiskLevel("MEDIUM");
        assertEquals("MEDIUM", trace.getRiskLevel());
        assertEquals(trace, TraceLog.builder().traceId("t1").userInput("check")
                .riskLevel("MEDIUM").toolName("DiskTool").resultSummary("ok")
                .durationMs(12).timestamp(now).build());
    }
}
