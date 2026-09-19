package com.award.log.analyzer;

import com.award.log.service.AiLogAlarmService;
import com.award.log.service.LogAnalysisService;
import com.award.log.model.LogDocument;
import com.award.log.websocket.LogStreamWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealTimeLogAnalyzerCoverageTest {

    @Mock
    private AiLogAlarmService aiLogAlarmService;
    @Mock
    private LogAnalysisService logAnalysisService;
    @Mock
    private LogStreamWebSocketHandler handler;

    private RealTimeLogAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new RealTimeLogAnalyzer();
        ReflectionTestUtils.setField(analyzer, "aiLogAlarmService", aiLogAlarmService);
        ReflectionTestUtils.setField(analyzer, "logAnalysisService", logAnalysisService);
        ReflectionTestUtils.setField(analyzer, "logCleaner", new LogCleaner());
        ReflectionTestUtils.setField(analyzer, "drainParserFactory",
                new DrainParserFactory(new DrainPlusParser(8, 50)));
    }

    @AfterEach
    void tearDown() {
        analyzer.shutdown();
    }

    @Test
    void analyzeLogsShouldParseEnhanceDetectAndAlarm() {
        List<Map<String, Object>> logs = List.of(
                Map.of("content", "2026-09-10 12:00:00 ERROR PID=123 UID=42 trace_id=abc "
                        + "Exception connecting from 192.168.1.20 user=alice@example.com phone=13812345678"),
                Map.of("message", "Sep 10 12:01:00 host sshd[456]: Failed password for user root"),
                Map.of("content", "2026-09-10 12:02:00 Error EventID:1001 PID=9 driver failure"),
                Map.of("content", "2026-09-10 12:03:00 WARN retrying request"),
                Map.of("content", "2026-09-10 12:04:00 INFO request completed"),
                Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(analyzer, "analyzeLogs", logs);

        assertNotNull(result);
        assertEquals(6, result.get("totalLogs"));
        assertEquals(6, result.get("parsedLogsCount"));
        assertTrue((Integer) result.get("anomalyLogs") > 0);
        assertTrue((Double) result.get("anomalyRate") > 0);
        assertTrue((Boolean) result.get("enhancedAnalysis"));
        verify(aiLogAlarmService).analyzeLogAndGenerateAlarm(anyString(), anyString());
    }

    @Test
    void helperMethodsShouldCoverMaskExtractionAndBroadcastBranches() {
        String raw = "connect 192.168.1.20 8.8.8.8 13812345678 alice@example.com "
                + "PID=123 UID=42 trace_id=abc Exception: bad";
        String masked = ReflectionTestUtils.invokeMethod(analyzer, "desensitizeLog", raw);
        assertNotNull(masked);
        assertTrue(masked.contains("192.168.*.*"));
        assertTrue(masked.contains("8.*.*.*"));
        assertTrue(masked.contains("****"));
        assertTrue(masked.contains("***"));
        assertEquals("", ReflectionTestUtils.invokeMethod(analyzer, "desensitizeLog", "  "));

        assertEquals("123", ReflectionTestUtils.invokeMethod(analyzer, "extractPid", "PID=123"));
        assertEquals("456", ReflectionTestUtils.invokeMethod(analyzer, "extractPid", "worker [456]"));
        assertEquals("未提取", ReflectionTestUtils.invokeMethod(analyzer, "extractPid", "none"));
        assertEquals("42", ReflectionTestUtils.invokeMethod(analyzer, "extractUid", "UID:42"));
        assertEquals("alice", ReflectionTestUtils.invokeMethod(analyzer, "extractUid", "user=alice"));
        assertEquals("未提取", ReflectionTestUtils.invokeMethod(analyzer, "extractUid", "none"));
        assertEquals("abc", ReflectionTestUtils.invokeMethod(analyzer, "extractTraceId", "traceid=abc"));
        assertEquals("未提取", ReflectionTestUtils.invokeMethod(analyzer, "extractTraceId", "none"));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(analyzer, "extractStackTrace", "Error: failed"))
                .startsWith("Error"));
        assertEquals("无异常栈", ReflectionTestUtils.invokeMethod(analyzer, "extractStackTrace", "fine"));

        RealTimeLogAnalyzer.MessageBroadcaster broadcaster = new RealTimeLogAnalyzer.MessageBroadcaster();
        broadcaster.registerHandler(handler);
        broadcaster.registerHandler(handler);
        broadcaster.broadcast("event");
        verify(handler).broadcastLogData("event");
        broadcaster.unregisterHandler(handler);
        broadcaster.broadcast("ignored");
        doThrow(new IllegalStateException("disconnected")).when(handler).broadcastLogData("bad");
        broadcaster.registerHandler(handler);
        assertDoesNotThrow(() -> broadcaster.broadcast("bad"));
    }

    @Test
    void lifecycleAndBufferStateShouldBeRestartable() {
        ReflectionTestUtils.setField(analyzer, "bufferThreshold", 100);
        analyzer.init();
        analyzer.startRealTimeAnalysis();
        analyzer.startRealTimeAnalysis();
        analyzer.addLog(Map.of("content", "INFO healthy"));
        analyzer.resetAnalysisState();
        analyzer.stopRealTimeAnalysis();
        analyzer.startRealTimeAnalysis();
        analyzer.stopRealTimeAnalysis();
        assertDoesNotThrow(analyzer::shutdown);
    }

    @Test
    void convertToLogDocumentUsesLevelAndContentFallbacks() {
        LogDocument warn = ReflectionTestUtils.invokeMethod(analyzer, "convertToLogDocument",
                Map.of("level", "WARN", "content", "warning body"));
        assertEquals("WARN", warn.getSeverity());
        assertEquals("warning body", warn.getContent());
        assertEquals("ANALYSIS_STREAM", warn.getProtocol());
        assertNotNull(warn.getId());
        assertNotNull(warn.getTimestamp());

        LogDocument message = ReflectionTestUtils.invokeMethod(analyzer, "convertToLogDocument",
                Map.of("message", "fallback message"));
        assertEquals("INFO", message.getSeverity());
        assertEquals("fallback message", message.getContent());
        LogDocument empty = ReflectionTestUtils.invokeMethod(analyzer, "convertToLogDocument", Map.of());
        assertEquals("INFO", empty.getSeverity());
        assertEquals("无内容", empty.getContent());
    }
}
