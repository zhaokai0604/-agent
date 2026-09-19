package com.award.log.service;

import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.config.AgentOpsProperties;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.MockedStatic;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsPatrolServiceCoverageTest {

    @Test
    void runPatrolCycleCoversFindingsBaselineDedupAndPersistence(@TempDir Path root) throws Exception {
        StatisticsService statistics = mock(StatisticsService.class);
        AiLogAlarmService alarms = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker novelty = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService security = mock(SecuritySignalService.class);
        OpsPathPolicy policy = pathPolicy(root);
        OpsPatrolService service = new OpsPatrolService(statistics, alarms, novelty, policy, security);

        Files.createDirectories(root.resolve("logs"));
        Path hotspot = root.resolve("logs").resolve("archive.bin");
        try (RandomAccessFile raf = new RandomAccessFile(hotspot.toFile(), "rw")) {
            raf.setLength(300L * 1024L * 1024L);
        }

        ReflectionTestUtils.setField(service, "inspectRootsRaw", root.toString() + ", ," + root.resolve("missing"));
        ReflectionTestUtils.setField(service, "diskWarnPercent", 80.0d);
        ReflectionTestUtils.setField(service, "cpuWarnPercent", 80.0d);
        ReflectionTestUtils.setField(service, "errorAlarmMin", 2);
        ReflectionTestUtils.setField(service, "anomalySpikeFactor", 2.0d);
        ReflectionTestUtils.setField(service, "anomalyMinDelta", 1);
        ReflectionTestUtils.setField(service, "anomalyBaselineEmaAlpha", 0.35d);
        ReflectionTestUtils.setField(service, "dedupWindowMs", 60_000L);
        ReflectionTestUtils.setField(service, "diskHotspotTopN", 2);
        ReflectionTestUtils.setField(service, "diskHotspotMaxDepth", 2);
        ReflectionTestUtils.setField(service, "diskHotspotVisitBudget", 200);
        ReflectionTestUtils.setField(service, "diskHotspotChildLimit", 20);
        ReflectionTestUtils.setField(service, "diskHotspotCacheMs", 60_000L);

        OpsAutoRemediationService auto = mock(OpsAutoRemediationService.class);
        doThrow(new RuntimeException("expected auto stage failure"))
                .when(auto).afterPatrol(anyMap(), anyList());
        ReflectionTestUtils.setField(service, "opsAutoRemediationService", auto);
        PatrolHistoryService history = mock(PatrolHistoryService.class);
        ReflectionTestUtils.setField(service, "patrolHistoryService", history);
        PerformanceWebSocketHandler websocket = mock(PerformanceWebSocketHandler.class);
        doThrow(new RuntimeException("expected push failure"))
                .when(websocket).broadcastJson(any());
        ReflectionTestUtils.setField(service, "performanceWebSocketHandler", websocket);

        when(statistics.getSystemPerformance(null)).thenReturn(Map.of(
                "diskUsage", 91.0, "cpuUsage", 92.0, "memoryUsage", 95.0));
        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 5));
        when(alarms.getAlarmStatistics(1, null, null)).thenReturn(Map.of(
                "totalAlarms", 8,
                "levelDistribution", List.of(
                        Map.of("level", "ERROR", "count", 2),
                        Map.of("level", "FATAL", "count", 1),
                        "bad-row")));
        when(novelty.countFirstSeenInWindow(3_600_000L)).thenReturn(3L);
        when(security.summary()).thenReturn(Map.of("hasThreat", true, "criticalCount", 1));
        when(security.buildThreatSummaryText(anyMap())).thenReturn("threat summary");

        service.runPatrolCycle();
        List<Map<String, Object>> firstFindings = service.getLastFindingsSnapshot();
        assertTrue(firstFindings.stream().anyMatch(f -> "disk".equals(f.get("code"))));
        assertTrue(firstFindings.stream().anyMatch(f -> "alarm_severity".equals(f.get("code"))));
        assertTrue(firstFindings.stream().anyMatch(f -> "disk_hotspot".equals(f.get("code"))));
        assertTrue(firstFindings.stream().anyMatch(f -> "security_signal".equals(f.get("code"))));
        assertEquals(5L, ReflectionTestUtils.getField(service, "lastAnomalyTotal"));
        assertFalse(((List<?>) service.getLastCorrelationSnapshot().get("diskHotspotsTop")).isEmpty());

        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 20));
        service.runPatrolCycle();
        assertEquals(5L, ReflectionTestUtils.getField(service, "lastAnomalyTotal"));
        assertTrue(service.getLastFindingsSnapshot().stream()
                .anyMatch(f -> "log_anomaly".equals(f.get("code"))));
        verify(history, org.mockito.Mockito.times(2)).saveRun(any(), anyList());

        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 1));
        service.runPatrolCycle();
        assertEquals(1L, ReflectionTestUtils.getField(service, "lastAnomalyTotal"));
        assertNotNull(service.getRecentAlerts(500));
        assertTrue(service.getRecentAlerts(1).size() <= 1);
    }

    @Test
    void runPatrolCycleHandlesMissingDependenciesAndEmaUpdate(@TempDir Path root) throws Exception {
        StatisticsService statistics = mock(StatisticsService.class);
        AiLogAlarmService alarms = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker novelty = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService security = mock(SecuritySignalService.class);
        OpsPathPolicy policy = pathPolicy(root);
        OpsPatrolService service = new OpsPatrolService(statistics, alarms, novelty, policy, security);
        ReflectionTestUtils.setField(service, "inspectRootsRaw", root.resolve("file.txt").toString());
        Files.writeString(root.resolve("file.txt"), "not a directory");
        ReflectionTestUtils.setField(service, "diskHotspotCacheMs", 0L);
        ReflectionTestUtils.setField(service, "dedupWindowMs", 0L);
        ReflectionTestUtils.setField(service, "anomalyBaselineEmaAlpha", 2.0d);
        when(statistics.getSystemPerformance(null)).thenThrow(new RuntimeException("metrics down"));
        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of());
        when(alarms.getAlarmStatistics(1, null, null)).thenThrow(new RuntimeException("alarm down"));
        when(novelty.countFirstSeenInWindow(3_600_000L)).thenReturn(0L);
        when(security.summary()).thenReturn(Map.of("hasThreat", false));

        service.runPatrolCycle();
        assertEquals(-1L, service.getLastCorrelationSnapshot().get("anomalyLogsDay1"));
        assertFalse(service.getLastFindingsSnapshot().stream()
                .anyMatch(f -> "security_signal".equals(f.get("code"))));

        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 10));
        service.runPatrolCycle();
        assertEquals(10L, ReflectionTestUtils.getField(service, "lastAnomalyTotal"));
        when(statistics.getAnomalyLogStatistics(1)).thenReturn(Map.of("totalAnomalyLogs", 11));
        service.runPatrolCycle();
        assertNotNull(ReflectionTestUtils.getField(service, "lastAnomalyTotal"));
    }

    @Test
    void unixHotspotWalkerAndBaselineHelpersCoverBoundaries(@TempDir Path root) throws Exception {
        StatisticsService statistics = mock(StatisticsService.class);
        AiLogAlarmService alarms = mock(AiLogAlarmService.class);
        DrainTemplateNoveltyTracker novelty = mock(DrainTemplateNoveltyTracker.class);
        SecuritySignalService security = mock(SecuritySignalService.class);
        OpsPathPolicy policy = pathPolicy(root);
        OpsPatrolService service = new OpsPatrolService(statistics, alarms, novelty, policy, security);
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("payload.log"), "hotspot".repeat(2048));
        ReflectionTestUtils.setField(service, "inspectRootsRaw", root.toString());
        ReflectionTestUtils.setField(service, "diskHotspotMaxDepth", 3);
        ReflectionTestUtils.setField(service, "diskHotspotVisitBudget", 200);
        ReflectionTestUtils.setField(service, "diskHotspotChildLimit", 20);
        ReflectionTestUtils.setField(service, "diskHotspotTopN", 5);
        ReflectionTestUtils.setField(service, "diskHotspotCacheMs", 60_000L);

        try (MockedStatic<com.award.log.util.OsRuntime> os = org.mockito.Mockito.mockStatic(
                com.award.log.util.OsRuntime.class)) {
            os.when(com.award.log.util.OsRuntime::isWindows).thenReturn(false);
            os.when(com.award.log.util.OsRuntime::isUnixLike).thenReturn(true);
            policy.updateEditablePathLists(false, List.of(root.toString()), List.of(root.toString()), List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = ReflectionTestUtils.invokeMethod(service, "scanDiskHotspots");
            assertNotNull(rows);
            assertTrue(rows.stream().anyMatch(row -> child.toString().equals(row.get("path"))));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cached = ReflectionTestUtils.invokeMethod(service, "scanDiskHotspots");
            assertEquals(rows, cached);
            assertTrue((Integer) ReflectionTestUtils.invokeMethod(service, "countZombieProcesses") >= 0);
        }

        Map<String, Object> correlation = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> findings = new java.util.ArrayList<>();
        ReflectionTestUtils.invokeMethod(service, "applyAnomalyBaseline", -1L, correlation, findings, 1L);
        ReflectionTestUtils.invokeMethod(service, "applyAnomalyBaseline", 100L, correlation, findings, 2L);
        ReflectionTestUtils.invokeMethod(service, "applyAnomalyBaseline", 250L, correlation, findings, 3L);
        assertTrue(findings.stream().anyMatch(f -> "log_anomaly".equals(f.get("code"))));
        ReflectionTestUtils.invokeMethod(service, "applyAnomalyBaseline", 20L, correlation, findings, 4L);
        assertEquals("reset_window_shrink", correlation.get("anomalyBaselineAction"));
        @SuppressWarnings("unchecked")
        Map<String, Object> finding = ReflectionTestUtils.invokeMethod(OpsPatrolService.class,
                "finding", "WARN", "disk", "title", "detail");
        assertEquals("disk", finding.get("code"));
    }

    private static OpsPathPolicy pathPolicy(Path root) {
        AgentOpsProperties props = new AgentOpsProperties();
        OpsPathPolicy policy = new OpsPathPolicy(props);
        ReflectionTestUtils.setField(policy, "logCollectorFilePath", "");
        boolean windows = com.award.log.util.OsRuntime.isWindows();
        policy.updateEditablePathLists(windows, List.of(root.toString()), List.of(root.toString()), List.of());
        return policy;
    }
}
