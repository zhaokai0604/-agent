package com.award.log.service.impl;

import com.award.log.mapper.DecisionFeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.nio.file.Path;

@ExtendWith(MockitoExtension.class)
class ContinuousLearningServiceTest {

    @Mock
    private DecisionFeedbackMapper decisionFeedbackMapper;

    private ContinuousLearningService service;

    @BeforeEach
    void setUp() {
        service = new ContinuousLearningService();
        ReflectionTestUtils.setField(service, "decisionFeedbackMapper", decisionFeedbackMapper);
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "sampleThreshold", 500);
    }

    @Test
    void getUntrainedSampleCountShouldReadMapper() {
        when(decisionFeedbackMapper.countUntrained()).thenReturn(12);
        assertEquals(12, service.getUntrainedSampleCount());
    }

    @Test
    void getUntrainedSampleCountShouldReturnZeroOnFailure() {
        when(decisionFeedbackMapper.countUntrained()).thenThrow(new RuntimeException("db down"));
        assertEquals(0, service.getUntrainedSampleCount());
    }

    @Test
    void dailyCollectAndRetrainShouldSkipWhenDisabled() {
        service.dailyCollectAndRetrain();
        verifyNoInteractions(decisionFeedbackMapper);
    }

    @Test
    void triggerManualTrainingShouldInvokeDailyJob() {
        when(decisionFeedbackMapper.countUntrained()).thenReturn(0);
        assertFalse(service.triggerManualTraining());
        verify(decisionFeedbackMapper).countUntrained();
    }

    @Test
    void disabledScheduledRunSkipsButForceStillChecksSamples() {
        when(decisionFeedbackMapper.countUntrained()).thenReturn(2);
        assertFalse(service.runCollectAndRetrain(false));
        verify(decisionFeedbackMapper, never()).countUntrained();
        assertFalse(service.runCollectAndRetrain(true));
        verify(decisionFeedbackMapper).countUntrained();
    }

    @Test
    void enabledRunRespectsThresholdAndEmptySamples() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "sampleThreshold", 3);
        when(decisionFeedbackMapper.countUntrained()).thenReturn(2);
        assertFalse(service.runCollectAndRetrain(false));

        when(decisionFeedbackMapper.countUntrained()).thenReturn(3);
        when(decisionFeedbackMapper.selectUntrainedSamples(5000)).thenReturn(List.of());
        assertFalse(service.runCollectAndRetrain(false));
    }

    @Test
    void enabledRunHandlesMapperAndScriptSetupFailures() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(decisionFeedbackMapper.countUntrained()).thenThrow(new IllegalStateException("db down"));
        assertFalse(service.runCollectAndRetrain(false));

        reset(decisionFeedbackMapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(decisionFeedbackMapper.countUntrained()).thenReturn(1);
        assertFalse(service.runCollectAndRetrain(false));
        verify(decisionFeedbackMapper, never()).markAsTrained(anyList());
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "runTrainScript", Path.of("missing-training-data.jsonl"))));
    }
}
