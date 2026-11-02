package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDiagnosisServiceImplTest {

    @Mock
    private ChatModel chatModel;

    private AiDiagnosisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiDiagnosisServiceImpl();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);
    }

    @Test
    void generateDiagnosisShouldReturnHealthyMessageForEmptyInput() {
        assertTrue(service.generateDiagnosis(List.of()).contains("未在日志中发现明显异常"));
    }

    @Test void trackedDiagnosisPropagatesPartialStreamFailureWithoutRetry() {
        var row = new EnhancedLogParseResultEntity("connection refused");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))))),
                Flux.error(new IllegalStateException("provider failed"))));
        assertThrows(IllegalStateException.class, () -> service.generateTrackedDiagnosis(List.of(row)).collectList().block());
        org.mockito.Mockito.verify(chatModel, org.mockito.Mockito.times(1)).stream(any(Prompt.class));
    }

    @Test
    void generateDiagnosisShouldCallChatModel() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("Connection refused");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");
        row.getAnomalyReasons().add("network");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("root cause: port blocked")))));

        String diagnosis = service.generateDiagnosis(List.of(row));
        assertEquals("root cause: port blocked", diagnosis);
    }

    @Test
    void diagnoseSingleLogShouldHandleValidEntry() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("timeout connecting");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("network issue")))));

        assertEquals("network issue", service.diagnoseSingleLog(row));
    }

    @Test
    void diagnoseSingleLogShouldRejectNullEntry() {
        assertEquals("无效的日志数据。", service.diagnoseSingleLog(null));
    }

    @Test
    void generateDiagnosisShouldFallbackWhenModelReturnsEmptyText() {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("Connection refused");
        row.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");
        row.getAnomalyReasons().add("network");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        String diagnosis = service.generateDiagnosis(List.of(row));
        assertTrue(diagnosis.contains("AI 未返回有效内容"));
    }

    @Test
    void chatStreamShouldRejectBlankUserMessage() {
        String text = service.chatStream("   ").blockFirst();
        assertTrue(text.contains("请输入有效"));
    }

    @Test
    void generateDiagnosisStreamShouldEmitFallbackForEmptyInput() {
        String text = service.generateDiagnosisStream(List.of()).blockFirst();
        assertTrue(text.contains("未在日志中发现明显异常"));
    }

    @Test
    void fullContextAndAllStreamVariantsShouldUseModelAndFallbackOnStreamError() {
        EnhancedLogParseResultEntity error = new EnhancedLogParseResultEntity("ERROR connection refused");
        error.setSeverity(LogSeverityLevel.ERROR_LEVEL);
        error.setLogTime("2024-01-01 10:00:00");
        EnhancedLogParseResultEntity normal = new EnhancedLogParseResultEntity("INFO request completed");
        normal.setSeverity(LogSeverityLevel.INFO_LEVEL);
        normal.setLogTime("2024-01-01 10:01:00");

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("grounded full diagnosis")))));
        assertEquals("grounded full diagnosis", service.generateDiagnosisFromFullResult(List.of(error, normal)));

        ChatResponse streamed = new ChatResponse(List.of(new Generation(new AssistantMessage("streamed"))));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamed));
        assertEquals("streamed", service.generateDiagnosisStream(List.of(error)).blockFirst());
        assertEquals("streamed", service.generateDiagnosisStreamFromFullResult(List.of(error, normal)).blockFirst());
        assertEquals("streamed", service.generateSingleLogDiagnosisStream(error).blockFirst());
        assertEquals("streamed", service.chatStream("请分析这条日志").blockFirst());

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("stream down")));
        assertTrue(service.generateDiagnosisStream(List.of(error)).blockFirst().contains("诊断服务中断"));
        assertTrue(service.generateDiagnosisStreamFromFullResult(List.of(error, normal)).blockFirst().contains("诊断服务中断"));
        assertTrue(service.generateSingleLogDiagnosisStream(error).blockFirst().contains("AI 诊断暂时不可用"));
        assertTrue(service.chatStream("请分析这条日志").blockFirst().contains("服务异常"));
    }

    @Test
    void diagnosisMethodsHandleModelExceptionsAndEmptyFocus() {
        EnhancedLogParseResultEntity normal = new EnhancedLogParseResultEntity("INFO all good");
        normal.setSeverity(LogSeverityLevel.INFO_LEVEL);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider down"));
        assertTrue(service.generateDiagnosis(List.of(normal)).contains("诊断服务暂时不可用"));
        assertTrue(service.generateDiagnosisFromFullResult(List.of(normal)).contains("未在日志中发现明显异常"));
        assertTrue(service.diagnoseSingleLog(normal).contains("诊断失败"));

        when(chatModel.stream(any(Prompt.class))).thenThrow(new IllegalStateException("stream init down"));
        assertTrue(service.generateSingleLogDiagnosisStream(normal).blockFirst().contains("请求失败"));
        assertTrue(service.chatStream("hello").blockFirst().contains("请求失败"));
    }
}
