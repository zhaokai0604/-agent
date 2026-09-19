package com.award.log.service.impl;

import com.award.log.analyzer.DrainParser;
import com.award.log.analyzer.DrainParserFactory;
import com.award.log.analyzer.DrainPlusParser;
import com.award.log.analyzer.LogCleaner;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionResult;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;
import com.award.log.analysis.LogFieldPlaceholder;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.util.LogAnalysisArtifactCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogAnalysisServiceImplCoverageTest {

    @Mock private AnalysisTaskManager taskManager;
    @Mock private LogAnalysisArtifactCleaner artifactCleaner;
    @Mock private CollaborativeDecisionService decisionService;

    private LogAnalysisServiceImpl service;
    private DrainParser parser;

    @BeforeEach
    void setUp() {
        service = new LogAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "taskManager", taskManager);
        ReflectionTestUtils.setField(service, "artifactCleaner", artifactCleaner);
        ReflectionTestUtils.setField(service, "collaborativeDecisionService", decisionService);
        ReflectionTestUtils.setField(service, "logCleaner", new LogCleaner());
        DrainParserFactory factory = new DrainParserFactory(new DrainPlusParser(8, 50));
        ReflectionTestUtils.setField(service, "drainParserFactory", factory);
        parser = factory.getPlusParser();
        ReflectionTestUtils.invokeMethod(service, "initAnalysisConcurrencyGate");
    }

    @Test
    void processLinesAndDetectionStagesCoverProtocolAndAnomalyBranches() throws Exception {
        List<String> lines = List.of(
                "信息 2026/1/6 12:56:23 Service Control Manager 事件 ID: 7040 Error",
                "Jan 10 12:00:00 host sshd[123]: Failed password UID=42 user=alice traceid=abc",
                "2026-01-01 10:00:00 ERROR PID=9 Exception connecting from 192.168.1.20",
                "2026-01-01 10:00:01 WARN retrying request",
                "unstructured message without timestamp");
        Object config = newEngineConfig();
        @SuppressWarnings("unchecked")
        List<EnhancedLogParseResultEntity> parsed = ReflectionTestUtils.invokeMethod(
                service, "processLines", lines, parser, config);
        assertEquals(5, parsed.size());
        assertNotNull(parsed.get(0).getProtocol());
        assertNotNull(parsed.get(1).getPid());
        assertEquals("9", parsed.get(2).getPid());
        assertEquals(LogFieldPlaceholder.TIME_UNPARSED, parsed.get(4).getLogTime());

        ReflectionTestUtils.invokeMethod(service, "processLogBatch", lines, parser, config, 2, "t-1");
        ReflectionTestUtils.invokeMethod(service, "detectTemporalAnomaly", parsed, config, "t-1");
        ReflectionTestUtils.invokeMethod(service, "detectKeywordAnomaly", parsed, "t-1");
        ReflectionTestUtils.invokeMethod(service, "detectFrequencyAnomaly", parsed, config, "t-1");
        ReflectionTestUtils.invokeMethod(service, "calcComprehensiveAnomalyScore", parsed, "t-1");
        assertTrue(parsed.stream().allMatch(r -> r.getAnomalyScore() >= 0));
        verify(taskManager, atLeastOnce()).updateProgress(any(String.class), anyInt(), any(String.class));
    }

    @Test
    void intelligentRouterEvaluatesHighLowAndNegativeDecisions() throws Exception {
        EnhancedLogParseResultEntity high = result("t-high", LogSeverityLevel.ERROR_LEVEL, true, 0.8);
        EnhancedLogParseResultEntity low = result("t-low", LogSeverityLevel.FATAL_LEVEL, true, 0.7);
        EnhancedLogParseResultEntity ordinary = result("t-ordinary", LogSeverityLevel.INFO_LEVEL, false, 0.1);
        when(decisionService.decide(any())).thenReturn(
                DecisionResult.builder().shouldAlert(true).confidence(0.9).reason("high confidence").build(),
                DecisionResult.builder().shouldAlert(true).confidence(0.4).reason("low confidence").build(),
                DecisionResult.builder().shouldAlert(false).confidence(0.2).reason("keep heuristic").build());

        ReflectionTestUtils.invokeMethod(service, "evaluateAnomaliesWithIntelligentRouter",
                new ArrayList<>(List.of(high, low, ordinary)), "task-router");
        verify(decisionService, atLeastOnce()).decide(any());
        assertTrue(high.isAnomaly());
        assertTrue(low.isAnomaly());
        assertTrue(high.getAnomalyReasons().stream().anyMatch(s -> s.contains("智能决策告警")));

        @SuppressWarnings("unchecked")
        Map<String, String> states = (Map<String, String>) ReflectionTestUtils.getField(service, "taskStatusMap");
        states.put("cancelled", "CANCELLED");
        ReflectionTestUtils.invokeMethod(service, "checkCancelStatus", "cancelled");
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        states.put("paused", "PAUSED");
        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "waitWhilePaused", "paused"));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    void reportEscapingDedupAndShardUtilitiesHandleBoundaries(@TempDir Path tempDir) throws Exception {
        Object config = newEngineConfig();
        ReflectionTestUtils.setField(config, "outputDir", tempDir.resolve("report").toString());
        EnhancedLogParseResultEntity row = result("template", LogSeverityLevel.ERROR_LEVEL, true, 0.7);
        row.setDesensitizedLog("a,b\"c\nline");
        ReflectionTestUtils.invokeMethod(service, "exportCsvResult", List.of(row), config);
        ReflectionTestUtils.invokeMethod(service, "generateHtmlReport", List.of(row), config);
        Path output = Path.of((String) ReflectionTestUtils.getField(config, "outputDir"));
        assertTrue(Files.exists(output.resolve("log_analysis_result.csv")));
        assertTrue(Files.exists(output.resolve("log_analysis_report.html")));

        Path file = Files.writeString(tempDir.resolve("app.log"), "line");
        String key = ReflectionTestUtils.invokeMethod(service, "buildFileDedupKey", file.toFile());
        assertTrue(key.contains(file.toFile().getAbsolutePath()));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "desensitizeLog", " "));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "desensitizeLog",
                "192.168.1.1 8.8.8.8 13812345678 a@example.com")).contains("***"));

        Class<?> shard = Class.forName(LogAnalysisServiceImpl.class.getName() + "$LogShardUtil");
        Method split = shard.getDeclaredMethod("splitLogToShards", java.io.InputStream.class, int.class, int.class);
        split.setAccessible(true);
        Object outcome = split.invoke(null,
                new ByteArrayInputStream("a\n\n b\nc\nd".getBytes(StandardCharsets.UTF_8)), 2, 3);
        Method readLines = outcome.getClass().getDeclaredMethod("readLines");
        Method truncated = outcome.getClass().getDeclaredMethod("truncated");
        readLines.setAccessible(true);
        truncated.setAccessible(true);
        assertEquals(3, ((Number) readLines.invoke(outcome)).intValue());
        assertTrue((Boolean) truncated.invoke(outcome));
    }

    @Test
    void staticMemoryAdjustersAndStateGuardsAreCallable() throws Exception {
        Class<?> monitor = Class.forName(LogAnalysisServiceImpl.class.getName() + "$MemoryMonitor");
        Method check = monitor.getDeclaredMethod("checkMemoryUsage");
        Method adjustBatch = monitor.getDeclaredMethod("adjustBatchSize", int.class, int.class, double.class);
        Method critical = monitor.getDeclaredMethod("isMemoryCritical");
        Method adjustShard = monitor.getDeclaredMethod("adjustShardSize", int.class);
        for (Method method : List.of(check, adjustBatch, critical, adjustShard)) {
            method.setAccessible(true);
        }
        double usage = (Double) check.invoke(null);
        assertTrue(usage >= 0 && usage <= 1.0);
        assertTrue((Integer) adjustBatch.invoke(null, 100, 10, 0.8) >= 10);
        assertTrue((Integer) adjustShard.invoke(null, 1000) >= 500);
        assertNotNull(critical.invoke(null));

        assertDoesNotThrow(() -> service.cancelAnalysis("task-cancel"));
        when(taskManager.isTerminalDbStatus("done")).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.pauseAnalysis("done"));
        assertThrows(IllegalStateException.class, () -> service.cancelAnalysis("done"));
    }

    private EnhancedLogParseResultEntity result(String template, LogSeverityLevel severity,
                                                 boolean anomaly, double score) {
        EnhancedLogParseResultEntity result = new EnhancedLogParseResultEntity("log");
        result.setTemplateId(template);
        result.setSeverity(severity);
        result.setAnomaly(anomaly);
        result.setAnomalyScore(score);
        result.setLogTime("2026-01-01 10:00:00");
        result.setTimestamp(System.currentTimeMillis());
        return result;
    }

    private Object newEngineConfig() throws Exception {
        for (Class<?> nested : LogAnalysisServiceImpl.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("EngineConfig")) {
                Constructor<?> constructor = nested.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
        throw new IllegalStateException("EngineConfig not found");
    }
}
