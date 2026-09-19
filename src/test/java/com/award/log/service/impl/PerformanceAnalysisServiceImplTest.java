package com.award.log.service.impl;

import com.award.log.model.PerformanceData;
import com.award.log.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceAnalysisServiceImplTest {

    @Mock
    private StatisticsService statisticsService;

    private PerformanceAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PerformanceAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "statisticsService", statisticsService);
    }

    @Test
    void recordPerformanceDataShouldAssignIdAndTimestamp() {
        PerformanceData data = new PerformanceData();
        data.setDataType("CPU");
        data.setValue(42.0);
        assertTrue(service.recordPerformanceData(data));
        assertNotNull(data.getId());
        assertNotNull(data.getCollectTime());
    }

    @Test
    void batchRecordPerformanceDataShouldCountSuccesses() {
        PerformanceData first = new PerformanceData();
        first.setDataType("CPU");
        first.setValue(10.0);
        PerformanceData second = new PerformanceData();
        second.setDataType("MEMORY");
        second.setValue(20.0);
        assertEquals(2, service.batchRecordPerformanceData(List.of(first, second)));
    }

    @Test
    void getPerformanceTrendShouldAggregateByInterval() {
        LocalDateTime now = LocalDateTime.now();
        PerformanceData point = new PerformanceData();
        point.setDataType("CPU");
        point.setValue(50.0);
        point.setCollectTime(now.minusMinutes(5));
        service.recordPerformanceData(point);

        List<Map<String, Object>> trend = service.getPerformanceTrend(
                "CPU", now.minusMinutes(10), now.plusMinutes(1), 5);
        assertFalse(trend.isEmpty());
        assertTrue(trend.get(0).containsKey("value"));
    }

    @Test
    void getPerformanceStatisticsShouldReturnAggregates() {
        LocalDateTime now = LocalDateTime.now();
        PerformanceData point = new PerformanceData();
        point.setDataType("DISK");
        point.setValue(70.0);
        point.setCollectTime(now);
        service.recordPerformanceData(point);

        Map<String, Object> stats = service.getPerformanceStatistics("DISK", now.minusHours(1), now.plusMinutes(1));
        assertEquals("DISK", stats.get("dataType"));
        assertNotNull(stats.get("count"));
        assertTrue(((Number) stats.get("count")).longValue() >= 1);
    }

    @Test
    void baselineMultiDimensionCleanupAndCollectionShouldCoverOperationalBranches() {
        LocalDateTime now = LocalDateTime.now();
        PerformanceData old = new PerformanceData();
        old.setDataType("CPU");
        old.setValue(20.0);
        old.setCollectTime(now.minusDays(2));
        PerformanceData recent = new PerformanceData();
        recent.setDataType("CPU");
        recent.setValue(40.0);
        recent.setCollectTime(now.minusMinutes(1));
        assertTrue(service.recordPerformanceData(old));
        assertTrue(service.recordPerformanceData(recent));

        Map<String, Object> baseline = service.getPerformanceBaseline("CPU", 7);
        assertEquals(40.0, ((Number) baseline.get("maximum")).doubleValue(), 0.001);
        assertTrue(baseline.containsKey("standardDeviation"));

        Map<String, Object> dimensions = service.getMultiDimensionPerformanceData(
                now.minusHours(1), now.plusMinutes(1), 5);
        assertTrue(dimensions.containsKey("cpuTrend"));
        assertEquals(5, dimensions.get("interval"));

        assertEquals(1, service.cleanupExpiredPerformanceData(now.minusDays(1)));
        assertTrue(service.getPerformanceStatistics("CPU", now.minusHours(1), now.plusMinutes(1)).containsKey("count"));

        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "cpuUsage", 11.0, "memoryUsage", 22.0, "diskUsage", 33.0,
                "networkUsage", "n/a", "cpuLoad", 0.5));
        service.collectSystemPerformanceData();
        assertFalse(service.getPerformanceStatistics("NETWORK", now.minusMinutes(1), now.plusMinutes(1)).isEmpty());
        assertFalse(service.getPerformanceStatistics("LOAD", now.minusMinutes(1), now.plusMinutes(1)).isEmpty());
    }

    @Test
    void collectionFailureAndEmptyQueriesShouldFailClosed() {
        when(statisticsService.getSystemPerformance(null)).thenThrow(new IllegalStateException("metrics down"));
        service.collectSystemPerformanceData();
        LocalDateTime now = LocalDateTime.now();
        assertTrue(service.getPerformanceStatistics("MISSING", now.minusMinutes(1), now.plusMinutes(1)).isEmpty());
        assertTrue(service.getPerformanceBaseline("MISSING", 1).isEmpty());
        assertTrue(service.getPerformanceTrend("MISSING", now.minusMinutes(1), now.plusMinutes(1), 5).isEmpty());
    }
}
