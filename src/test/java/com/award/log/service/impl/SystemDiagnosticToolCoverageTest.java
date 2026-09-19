package com.award.log.service.impl;

import com.award.log.model.LogAnalysisDetail;
import com.award.log.service.LogQueryFallbackService;
import com.award.log.service.StatisticsService;
import com.award.log.service.AiTool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemDiagnosticToolCoverageTest {

    @Mock private LogQueryFallbackService fallbackService;
    @Mock private StatisticsService statisticsService;

    private SystemDiagnosticTool tool;

    @BeforeEach
    void setUp() {
        tool = new SystemDiagnosticTool();
        ReflectionTestUtils.setField(tool, "logQueryFallbackService", fallbackService);
        ReflectionTestUtils.setField(tool, "statisticsService", statisticsService);
    }

    @Test
    void executesAllDiagnosticSectionsWithFallbackEvidence() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", 92, "memoryUsage", 90, "diskUsage", 88));
        when(fallbackService.queryRecent(anyInt(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()))
                .thenReturn(details());

        ToolResult result = tool.execute(Map.of("timeRange", "过去7天", "focusArea", "all"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("实时性能"));
        assertTrue(result.getContent().contains("FATAL 级别错误"));
        assertTrue(result.getContent().contains("异常日志"));
        assertTrue(result.getContent().contains("潜在问题"));
        assertEquals("all", result.getData().get("focusArea"));
    }

    @Test
    void emptyAndFocusedSectionsProduceNormalResult() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", "10", "memoryUsage", "20", "diskUsage", 30));
        when(fallbackService.queryRecent(anyInt(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()))
                .thenReturn(List.of());

        ToolResult performance = tool.execute(Map.of("timeRange", "30分钟", "focusArea", "performance"));
        assertTrue(performance.isSuccess());
        assertTrue(performance.getContent().contains("正常范围"));
        assertTrue(!performance.getContent().contains("错误日志分析"));

        ToolResult errors = tool.execute(Map.of("timeRange", "今天", "focusArea", "errors"));
        assertTrue(errors.isSuccess());
        assertTrue(errors.getContent().contains("未检测到"));

        ToolResult anomaly = tool.execute(Map.of("timeRange", "昨天", "focusArea", "anomaly"));
        assertTrue(anomaly.isSuccess());
        assertTrue(anomaly.getContent().contains("未检测到异常"));
    }

    @Test
    void degradesWhenDependenciesFailAndPrivateParsersCoverTimeWindows() throws Exception {
        when(statisticsService.getSystemPerformance(null)).thenThrow(new IllegalStateException("metrics down"));
        when(fallbackService.queryRecent(anyInt(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        ToolResult result = tool.execute(Map.of("timeRange", "未知范围", "focusArea", "all"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("采集失败") || result.getContent().contains("分析失败"));
        assertTrue(!tool.execute(null).isSuccess());

        Method parse = SystemDiagnosticTool.class.getDeclaredMethod("parseTimeRange", String.class);
        Method estimate = SystemDiagnosticTool.class.getDeclaredMethod("estimateWindowDays", String.class);
        Method severity = SystemDiagnosticTool.class.getDeclaredMethod("extractSeverity", String.class);
        Method errorType = SystemDiagnosticTool.class.getDeclaredMethod("extractErrorType", String.class);
        Method truncate = SystemDiagnosticTool.class.getDeclaredMethod("truncate", String.class, int.class);
        for (Method method : List.of(parse, estimate, severity, errorType, truncate)) method.setAccessible(true);
        assertNotNull(parse.invoke(tool, "2小时"));
        assertNotNull(parse.invoke(tool, "过去3天"));
        assertNotNull(parse.invoke(tool, "1周"));
        assertNotNull(parse.invoke(tool, "1月"));
        assertNotNull(parse.invoke(tool, ""));
        assertEquals(30, estimate.invoke(tool, "1月"));
        assertEquals(7, estimate.invoke(tool, "1周"));
        assertEquals(3, estimate.invoke(tool, "过去3天"));
        assertEquals("UNKNOWN", severity.invoke(tool, new Object[]{null}));
        assertEquals("权限拒绝", errorType.invoke(tool, "Permission denied"));
        assertEquals("未知错误", errorType.invoke(tool, new Object[]{null}));
        assertTrue(String.valueOf(truncate.invoke(tool, "x".repeat(40), 10)).endsWith("..."));
        assertEquals("", truncate.invoke(tool, new Object[]{null, 10}));
    }

    private static List<LogAnalysisDetail> details() {
        List<LogAnalysisDetail> rows = new ArrayList<>();
        String[] fatal = {"OutOfMemory fatal", "NullPointerException", "Connection refused", "Timeout"};
        for (String content : fatal) rows.add(detail("FATAL", content, true, 0.95));
        String[] errors = {"FileNotFound", "Permission denied", "SQLException", "IOException",
                "Connection refused", "Timeout", "普通错误", "普通错误2"};
        for (String content : errors) rows.add(detail("ERROR", content + " " + "z".repeat(120), false, 0.4));
        return rows;
    }

    private static LogAnalysisDetail detail(String severity, String content, boolean anomaly, double score) {
        LogAnalysisDetail row = new LogAnalysisDetail();
        row.setSeverity(severity);
        row.setDesensitizedLog(content);
        row.setIsAnomaly(anomaly);
        row.setAnomalyScore(score);
        return row;
    }
}
