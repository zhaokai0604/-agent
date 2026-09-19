package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.mapper.DecisionFeedbackMapper;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.DecisionFeedback;
import com.award.log.model.DecisionLog;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.ContinuousLearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionFeedbackControllerCoverageTest {

    @Mock private DecisionFeedbackMapper feedbackMapper;
    @Mock private DecisionLogMapper decisionLogMapper;
    @Mock private ContinuousLearningService learningService;
    @Mock private RequestUserResolver userResolver;

    private DecisionFeedbackController controller;
    private final jakarta.servlet.http.HttpServletRequest request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        controller = new DecisionFeedbackController(feedbackMapper, decisionLogMapper, learningService,
                userResolver, new ObjectMapper());
        lenient().when(userResolver.currentUserId(request)).thenReturn(1);
        lenient().when(userResolver.isAdmin(request)).thenReturn(true);
    }

    @Test
    void submitHydratesWindowFeaturesAndMetadataFromDecisionLog() {
        DecisionLog log = new DecisionLog();
        log.setInputJson("{\"errorRate1m\":0.25,\"error1m\":2,\"total1m\":8,"
                + "\"event\":{\"eventTime\":1000,\"ingestTime\":1250,\"content\":\"ERROR disk\",\"level\":\"ERROR\"},"
                + "\"template\":\"disk-template\"}");
        log.setConfidence(0.88);
        when(decisionLogMapper.selectByDecisionId("d1")).thenReturn(log);
        when(feedbackMapper.upsert(any(DecisionFeedback.class))).thenReturn(1);
        DecisionFeedback stored = new DecisionFeedback();
        stored.setId(42L);
        when(feedbackMapper.selectByDecisionId("d1")).thenReturn(stored);

        DecisionFeedback feedback = new DecisionFeedback();
        feedback.setDecisionId("d1");
        Result<Long> result = controller.submitFeedback(feedback, request);

        assertEquals(200, result.getCode());
        assertEquals(42L, result.getData());
        ArgumentCaptor<DecisionFeedback> captor = ArgumentCaptor.forClass(DecisionFeedback.class);
        verify(feedbackMapper).upsert(captor.capture());
        DecisionFeedback saved = captor.getValue();
        assertEquals(0.25, saved.getErrorRate1m());
        assertEquals(250.0, saved.getIntervalMs());
        assertEquals("ERROR disk", saved.getLogContent());
        assertEquals("disk-template", saved.getLogTemplate());
        assertEquals(0.88, saved.getModelConfidence());
        assertFalse(Boolean.TRUE.equals(saved.getIsTrained()));
    }

    @Test
    void submitAndBatchHandleAuthEmptyInputAndPersistenceFailures() {
        when(userResolver.currentUserId(request)).thenReturn(null);
        assertEquals(401, controller.submitFeedback(new DecisionFeedback(), request).getCode());
        assertEquals(401, controller.submitFeedbackBatch(java.util.List.of(new DecisionFeedback()), request).getCode());

        when(userResolver.currentUserId(request)).thenReturn(1);
        assertEquals(400, controller.submitFeedbackBatch(null, request).getCode());
        assertEquals(400, controller.submitFeedbackBatch(java.util.List.of(), request).getCode());
        when(feedbackMapper.upsert(any(DecisionFeedback.class))).thenThrow(new IllegalStateException("db down"));
        assertEquals(500, controller.submitFeedback(new DecisionFeedback(), request).getCode());

        org.mockito.Mockito.reset(feedbackMapper);
        DecisionFeedback good = new DecisionFeedback();
        DecisionFeedback bad = new DecisionFeedback();
        when(feedbackMapper.upsert(good)).thenReturn(1);
        doThrow(new IllegalStateException("bad row")).when(feedbackMapper).upsert(bad);
        Result<Integer> batch = controller.submitFeedbackBatch(java.util.List.of(good, bad), request);
        assertEquals(200, batch.getCode());
        assertEquals(1, batch.getData());
    }

    @Test
    void adminQueriesClampPagingAndFailClosed() {
        when(userResolver.isAdmin(request)).thenReturn(false);
        assertEquals(403, controller.getUntrainedSamples(1, 20, request).getCode());
        assertEquals(403, controller.getUntrainedCount(request).getCode());
        assertEquals(403, controller.triggerManualTraining(request).getCode());
        assertEquals(403, controller.markAsTrained(java.util.List.of(1L), request).getCode());

        when(userResolver.isAdmin(request)).thenReturn(true);
        PageResult<DecisionFeedback> page = new PageResult<>();
        page.setList(java.util.List.of());
        page.setTotal(3);
        when(feedbackMapper.countUntrained()).thenReturn(3);
        when(feedbackMapper.selectUntrainedSamplesPage(200, 0)).thenReturn(java.util.List.of());
        assertEquals(200, controller.getUntrainedSamples(0, 999, request).getCode());
        assertEquals(200, controller.getUntrainedCount(request).getCode());
        when(learningService.triggerManualTraining()).thenReturn(true);
        assertTrue(controller.triggerManualTraining(request).getData());
        assertEquals(400, controller.markAsTrained(null, request).getCode());
        assertEquals(400, controller.markAsTrained(java.util.List.of(), request).getCode());
        when(feedbackMapper.markAsTrained(anyList())).thenReturn(2);
        assertEquals(2, controller.markAsTrained(java.util.List.of(1L, 2L), request).getData());
    }

    @Test
    void adminEndpointsReturnErrorsWhenStorageOrTrainingFails() {
        when(userResolver.isAdmin(request)).thenReturn(true);
        when(feedbackMapper.countUntrained()).thenThrow(new IllegalStateException("db down"));
        assertEquals(500, controller.getUntrainedSamples(1, 20, request).getCode());
        assertEquals(500, controller.getUntrainedCount(request).getCode());
        when(learningService.triggerManualTraining()).thenThrow(new IllegalStateException("trainer down"));
        assertEquals(500, controller.triggerManualTraining(request).getCode());
        when(feedbackMapper.markAsTrained(anyList())).thenThrow(new IllegalStateException("db down"));
        assertEquals(500, controller.markAsTrained(java.util.List.of(1L), request).getCode());
    }
}
