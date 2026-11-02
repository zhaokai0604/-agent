package com.award.log.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.award.log.analyzer.RealTimeLogAnalyzer;
import com.award.log.model.LogDocument;
import com.award.log.service.ElasticsearchService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class LogStreamTimestampTest {
    @Test void parsedEventTimeIsPreservedInsteadOfIngestTime() {
        var handler=new LogStreamWebSocketHandler();
        @SuppressWarnings("unchecked") List<Map<String,Object>> parsed=ReflectionTestUtils.invokeMethod(handler,"parseLogLines",
                List.of("2024-01-01 12:00:00 ERROR service failed"));
        long timestamp=((Number)parsed.get(0).get("timestamp")).longValue();
        assertTrue(timestamp < System.currentTimeMillis()-60_000);
        assertEquals("ERROR",parsed.get(0).get("level"));
    }
    @Test void missingEventTimeFallsBackToCurrentIngestTime() {
        var handler=new LogStreamWebSocketHandler(); long before=System.currentTimeMillis();
        @SuppressWarnings("unchecked") List<Map<String,Object>> parsed=ReflectionTestUtils.invokeMethod(handler,"parseLogLines",
                List.of("ERROR service failed"));
        long timestamp=((Number)parsed.get(0).get("timestamp")).longValue();
        assertTrue(timestamp>=before && timestamp<=System.currentTimeMillis());
    }
    @Test void standardSeverityParserRecognizesBracketedNginxLevels() {
        var handler=new LogStreamWebSocketHandler();
        @SuppressWarnings("unchecked") List<Map<String,Object>> parsed=ReflectionTestUtils.invokeMethod(handler,"parseLogLines",
                List.of("2024-01-01 12:00:00 [error] upstream failure"));
        assertEquals("ERROR",parsed.get(0).get("level"));
    }

    @Test void elasticsearchDocumentKeepsParsedEventTime() {
        var handler=new LogStreamWebSocketHandler();
        long eventTime=Instant.parse("2024-01-01T04:00:00Z").toEpochMilli();
        var document=ReflectionTestUtils.invokeMethod(handler,"convertToLogDocument",
                Map.of("content","2024-01-01 12:00:00 INFO application started","level","INFO","timestamp",eventTime));
        assertEquals(Instant.ofEpochMilli(eventTime).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                ((com.award.log.model.LogDocument)document).getTimestamp());
    }

    @Test void ordinaryInfoTextContainingErrorSubstringIsNotMarkedAnomalous() {
        var handler=new LogStreamWebSocketHandler();
        var document=ReflectionTestUtils.invokeMethod(handler,"convertToLogDocument",
                Map.of("content","INFO cache path=/service/error-recovery warmed","level","INFO","timestamp",1L));
        assertEquals(Boolean.FALSE,((com.award.log.model.LogDocument)document).getAnomaly());
    }

    @Test void realtimeAnalyzerFailureDoesNotDropOtherLogIndexWrites() {
        var handler=new LogStreamWebSocketHandler();
        var analyzer=mock(RealTimeLogAnalyzer.class);
        var elasticsearch=mock(ElasticsearchService.class);
        doThrow(new IllegalStateException("analysis unavailable")).when(analyzer).addLog(anyMap());
        ReflectionTestUtils.setField(handler,"realTimeLogAnalyzer",analyzer);
        ReflectionTestUtils.setField(handler,"elasticsearchService",elasticsearch);

        ReflectionTestUtils.invokeMethod(handler,"processLogBatch",List.of(
                "2024-01-01 12:00:00 ERROR first failure",
                "2024-01-01 12:00:01 INFO second record"));

        verify(elasticsearch,times(2)).indexLog(any(LogDocument.class));
    }
}
