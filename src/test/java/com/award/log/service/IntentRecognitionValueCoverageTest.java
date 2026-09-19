package com.award.log.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntentRecognitionValueCoverageTest {

    @Test
    void recognitionResultAndTimeRangeExposeMutableConversationMetadata() {
        IntentRecognitionService.RecognitionResult result =
                new IntentRecognitionService.RecognitionResult(
                        IntentRecognitionService.Intent.DIAGNOSE_ISSUE, "检查错误");
        assertEquals(IntentRecognitionService.Intent.DIAGNOSE_ISSUE, result.getIntent());
        assertEquals("HIGH", result.getConfidence());
        result.setRefinedQuery("检查数据库连接");
        result.setTimeRange(new IntentRecognitionService.TimeRange("a", "b", "1h"));
        result.setKeywords(List.of("database", "timeout"));
        result.setTargetService("mysql");
        result.setConfidence("MEDIUM");
        assertEquals("检查数据库连接", result.getRefinedQuery());
        assertEquals("mysql", result.getTargetService());
        assertEquals(List.of("database", "timeout"), result.getKeywords());
        assertEquals("a", result.getTimeRange().getStartTime());
        assertEquals("b", result.getTimeRange().getEndTime());
        assertEquals("1h", result.getTimeRange().getDuration());
        assertNotNull(result.toString());
        assertEquals(result, result);
    }
}
