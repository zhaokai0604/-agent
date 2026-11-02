package com.award.log.service.impl;

import com.award.log.model.LogAnalysisDetail;
import com.award.log.model.LogDocument;
import com.award.log.service.AiTool;
import com.award.log.service.LogQueryFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogQueryToolTest {

    @Mock
    private LogQueryFallbackService logQueryFallbackService;
    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    private LogQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new LogQueryTool();
        ReflectionTestUtils.setField(tool, "logQueryFallbackService", logQueryFallbackService);
        ReflectionTestUtils.setField(tool, "elasticsearchOperations", null);
    }

    @Test
    void metadataShouldExposeQuerySchema() {
        assertEquals("query_logs", tool.getName());
        assertTrue(tool.getFunctionSchema().containsKey("parameters"));
    }

    @Test
    void executeShouldUseMysqlFallbackWhenEsUnavailable() {
        LogAnalysisDetail row = new LogAnalysisDetail();
        row.setDesensitizedLog("NullPointerException");
        row.setSeverity("ERROR");
        when(logQueryFallbackService.queryRecent(anyInt(), any(), any(), isNull(), anyInt()))
                .thenReturn(List.of(row));

        AiTool.ToolResult result = tool.execute(Map.of(
                "timeRange", "最近1小时",
                "severity", "ERROR",
                "keywords", "NullPointer"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("查询结果"));
    }

    @Test
    void executeShouldReturnEmptyMessageWhenNoRowsFound() {
        when(logQueryFallbackService.queryRecent(anyInt(), any(), any(), isNull(), anyInt()))
                .thenReturn(List.of());

        AiTool.ToolResult result = tool.execute(Map.of("timeRange", "今天"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("没有找到日志记录"));
    }

    @Test
    void executeShouldRenderEsResultsAndClampInvalidLimit() {
        ReflectionTestUtils.setField(tool, "elasticsearchOperations", elasticsearchOperations);
        @SuppressWarnings("unchecked")
        SearchHits<LogDocument> hits = org.mockito.Mockito.mock(SearchHits.class);
        @SuppressWarnings("unchecked")
        SearchHit<LogDocument> hit = org.mockito.Mockito.mock(SearchHit.class);
        LogDocument doc = new LogDocument();
        doc.setContent("ERROR very long content ".repeat(10));
        doc.setSeverity("ERROR");
        doc.setProtocol("HTTP");
        doc.setSource("app");
        doc.setAnomaly(true);
        when(hit.getContent()).thenReturn(doc);
        when(hits.getSearchHits()).thenReturn(java.util.Collections.nCopies(12, hit));
        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class))).thenReturn(hits);

        AiTool.ToolResult result = tool.execute(Map.of(
                "timeRange", "过去2天", "severity", " ERROR ", "keywords", "timeout",
                "service", "HTTP", "limit", "bad"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("还有 2 条日志未显示"));
        assertEquals("elasticsearch", result.getData().get("dataSource"));
    }

    @Test
    void executeAndTimeRangeParsingHandleNullsAndEsFailure() {
        ReflectionTestUtils.setField(tool, "elasticsearchOperations", elasticsearchOperations);
        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenThrow(new IllegalStateException("es down"));
        when(logQueryFallbackService.queryRecent(anyInt(), any(), any(), isNull(), anyInt()))
                .thenReturn(List.of());
        assertFalse(tool.execute(null).isSuccess());
        assertTrue(tool.execute(Map.of("timeRange", "1周", "limit", -1)).isSuccess());
        assertTrue(tool.execute(Map.of("timeRange", "1月", "limit", "x")).isSuccess());
        assertTrue(tool.execute(Map.of("timeRange", "未知")).isSuccess());
        for (String value : List.of("30分钟", "2小时", "今天", "昨天", "过去3天", "最近1小时")) {
            assertNotNull(ReflectionTestUtils.invokeMethod(tool, "parseTimeRange", value));
        }
        assertNotNull(ReflectionTestUtils.invokeMethod(tool, "parseFallbackTimestamp", "2024/01/01 10:00:00"));
        assertNotNull(ReflectionTestUtils.invokeMethod(tool, "parseFallbackTimestamp", "2024-01-01T10:00:00.123"));
        assertNotNull(ReflectionTestUtils.invokeMethod(tool, "parseFallbackTimestamp", "1700000000"));
        assertNull(ReflectionTestUtils.invokeMethod(tool, "parseFallbackTimestamp", "bad"));
    }
}
