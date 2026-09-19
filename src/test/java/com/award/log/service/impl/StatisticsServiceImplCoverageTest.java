package com.award.log.service.impl;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatisticsServiceImplCoverageTest {

    private LogAnalysisTaskMapper taskMapper;
    private LogAnalysisDetailMapper detailMapper;
    private StatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(LogAnalysisTaskMapper.class);
        detailMapper = mock(LogAnalysisDetailMapper.class);
        service = new StatisticsServiceImpl();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "detailMapper", detailMapper);
        ReflectionTestUtils.setField(StatisticsServiceImpl.class, "oshiAvailable", Boolean.FALSE);
        ReflectionTestUtils.setField(service, "cachedPerformance", null);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Integer, Object> cache =
                (java.util.concurrent.ConcurrentHashMap<Integer, Object>) ReflectionTestUtils
                        .getField(service, "cachedAnomalyStats");
        cache.clear();
    }

    @Test
    void summaryNormalizesDaysAggregatesMixedRowsAndHandlesRedisErrors() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis unavailable"));
        doThrow(new RuntimeException("redis write unavailable"))
                .when(ops).set(anyString(), any(), anyLong(), any());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        when(taskMapper.countSinceDays(1)).thenReturn(0L);
        when(detailMapper.countSinceDays(1)).thenReturn(0L);
        when(taskMapper.getTaskTrend(-4)).thenReturn(List.of());
        when(detailMapper.selectAnomalySeverityDistribution(-4)).thenReturn(List.of(
                Map.of("count", 2), Map.of("COUNT", 3), Map.of("count", "bad")));

        Map<String, Object> result = service.getRecentLogSummary(-4);
        assertEquals(1, result.get("summaryDays"));
        assertEquals(5L, result.get("anomalyLogs"));
        assertEquals(0.0, result.get("anomalyRate"));
    }

    @Test
    void taskStatsDefensivelyMapsRowsAndUsesRedisCache() {
        when(taskMapper.getTaskStatusCount()).thenReturn(java.util.Arrays.asList(
                null,
                Map.of("status", "DONE", "count", 3),
                Map.of("STATUS", "RUNNING", "COUNT", 2),
                Map.of("status", "BROKEN", "count", "n"),
                Map.of("count", 4)));
        when(taskMapper.getTaskCompletionStatistics()).thenReturn(List.of(Map.of("done", 1)));
        when(taskMapper.getAverageProcessingTime()).thenReturn(null);
        Map<String, Object> first = service.getTaskStatusStatistics();
        assertEquals(2, ((Map<?, ?>) first.get("statusCount")).size());

        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("statistics:task_status")).thenReturn(Map.of("cached", true));
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        assertEquals(true, service.getTaskStatusStatistics().get("cached"));
    }

    @Test
    void performanceFallbackAndNetworkRateCoverCacheAndCounterEdges() {
        Map<String, Object> first = service.getSystemPerformance(null);
        assertNotNull(first.get("cpuUsage"));
        assertEquals("mxbean-proc", first.get("metricsSource"));
        Map<String, Object> cached = service.getSystemPerformance(" ");
        assertNotNull(cached.get("memoryUsage"));

        double firstRate = (double) ReflectionTestUtils.invokeMethod(service, "calculateNetworkUsage",
                "test0", 100L, 100L, 1_000L, 1_000L);
        double sameTime = (double) ReflectionTestUtils.invokeMethod(service, "calculateNetworkUsage",
                "test0", 200L, 200L, 1_000L, 1_000L);
        double backwards = (double) ReflectionTestUtils.invokeMethod(service, "calculateNetworkUsage",
                "test0", 300L, 300L, 900L, 1_000L);
        double reset = (double) ReflectionTestUtils.invokeMethod(service, "calculateNetworkUsage",
                "test0", 50L, 50L, 2_000L, 1_000L);
        double positive = (double) ReflectionTestUtils.invokeMethod(service, "calculateNetworkUsage",
                "test0", 2_000L, 2_000L, 3_000L, 1L);
        assertEquals(0.0, firstRate);
        assertEquals(0.0, sameTime);
        assertEquals(0.0, backwards);
        assertEquals(0.0, reset);
        assertTrue(positive >= 0.0 && positive <= 100.0);
        assertEquals(12_500_000L, (long) (Long) ReflectionTestUtils.invokeMethod(
                service, "normalizeBandwidthBytesPerSecond", 100_000_000L));
        assertTrue((Long) ReflectionTestUtils.invokeMethod(service, "normalizeBandwidthBytesPerSecond", 0L) > 0);
    }

    @Test
    void procReadersAndFallbackNetworkSelectionCoverBoundaryInputs() {
        long[] memory = ReflectionTestUtils.invokeMethod(StatisticsServiceImpl.class, "readLinuxMemInfo");
        assertNotNull(memory);
        assertEquals(2, memory.length);
        assertEquals(1234L, ((Number) ReflectionTestUtils.invokeMethod(
                StatisticsServiceImpl.class, "parseProcMemKb", "MemTotal: 1234 kB")).longValue());
        assertEquals(0L, ((Number) ReflectionTestUtils.invokeMethod(
                StatisticsServiceImpl.class, "parseProcMemKb", "not-a-memory-line")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, long[]> interfaces = ReflectionTestUtils.invokeMethod(
                StatisticsServiceImpl.class, "readProcNetDevBytes");
        assertNotNull(interfaces);
        Map<String, Object> performance = new java.util.LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service, "applyNetworkFallbackMetrics", performance, "missing-interface");
        assertTrue(performance.containsKey("networkInterfaces"));
        assertTrue(performance.containsKey("networkUsage"));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(
                StatisticsServiceImpl.class, "useOshiMetrics"));
    }

    @Test
    void anomalyStatsNormalizesWindowAndCachesResult() {
        when(detailMapper.selectAnomalySeverityDistribution(1)).thenReturn(List.of(
                Map.of("count", 2), Map.of("severity", "INFO")));
        Map<String, Object> first = service.getAnomalyLogStatistics(0);
        Map<String, Object> second = service.getAnomalyLogStatistics(0);
        assertEquals(1, first.get("days"));
        assertEquals(2L, first.get("totalAnomalyLogs"));
        assertEquals(first.get("totalAnomalyLogs"), second.get("totalAnomalyLogs"));
        org.mockito.Mockito.verify(detailMapper, org.mockito.Mockito.times(1))
                .selectAnomalySeverityDistribution(1);
    }
}
