package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import com.award.log.service.AlarmLifecycleService;
import com.award.log.task.AnalysisTaskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiLogAlarmServiceImplCoverageTest {

    private LogAlarmMapper mapper;
    private AlarmConfigService configService;
    private AnalysisTaskManager taskManager;
    private AiLogAlarmServiceImpl service;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        mapper = mock(LogAlarmMapper.class);
        configService = mock(AlarmConfigService.class);
        taskManager = mock(AnalysisTaskManager.class);
        service = new AiLogAlarmServiceImpl();
        ReflectionTestUtils.setField(service, "logAlarmMapper", mapper);
        ReflectionTestUtils.setField(service, "alarmConfigService", configService);
        ReflectionTestUtils.setField(service, "analysisTaskManager", taskManager);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.initialize();
        ReflectionTestUtils.setField(service, "logAnalysisExecutor", executor);
        ReflectionTestUtils.setField(service, "aggregationEnabled", false);
        when(taskManager.canAccessTask(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void pushPolicyCoversDisabledThresholdLevelMissingTaskAndCooldown() {
        LogAlarm info = alarm("task-1", "INFO", "info");
        when(configService.getEffectiveConfig("task-1")).thenReturn(null);
        assertFalse(service.pushAlarm(info));
        assertTrue(info.getPushStatus().contains("CONFIG_NOT_FOUND"));

        TaskAlarmConfig disabled = config("ERROR", false, null, null);
        when(configService.getEffectiveConfig("task-1")).thenReturn(disabled);
        assertFalse(service.pushAlarm(alarm("task-1", "ERROR", "error")));

        TaskAlarmConfig threshold = config("ERROR_LEVEL", true, 3, null);
        when(configService.getEffectiveConfig("task-1")).thenReturn(threshold);
        assertFalse(service.pushAlarm(alarm("task-1", "ERROR", "one error\nnormal")));

        TaskAlarmConfig missingTask = config("ERROR", true, null, null);
        when(configService.getEffectiveConfig(null)).thenReturn(missingTask);
        assertFalse(service.pushAlarm(alarm(null, "ERROR", "error")));

        TaskAlarmConfig allowed = config("ERROR", true, 2, 3_600_000L);
        when(configService.getEffectiveConfig("task-1")).thenReturn(allowed);
        LogAlarm passing = alarm("task-1", "ERROR", "ERROR one\nFATAL two");
        assertTrue(service.pushAlarm(passing));
        assertEquals("RECORDED", passing.getPushStatus());
        LogAlarm cooled = alarm("task-1", "ERROR", "ERROR one\nFATAL two");
        assertFalse(service.pushAlarm(cooled));
        assertTrue(cooled.getPushStatus().contains("COOLDOWN_ACTIVE"));
    }

    @Test
    void historyStatisticsAndUserBranchesUseSafeTaskScope() {
        when(taskManager.canAccessTask("task-1", 7, false)).thenReturn(true);
        when(mapper.selectPageByUserId(anyInt(), anyInt(), any(), isNull(), anyString())).thenReturn(List.of());
        when(mapper.countByUserId(any(), isNull(), anyString())).thenReturn(2L);
        Map<String, Object> page = service.getAlarmHistoryForUser(0, 0, " ", "task-1", 7, false);
        assertEquals(1, page.get("pageNum"));
        assertEquals(2L, page.get("total"));

        when(mapper.selectAlarmStatisticsByUserId(anyInt(), any(), isNull(), anyString()))
                .thenReturn(null);
        when(mapper.selectAlarmTrendByUserId(anyInt(), any(), isNull(), anyString())).thenReturn(List.of());
        when(mapper.selectAlarmLevelDistributionByUserId(anyInt(), any(), isNull(), anyString())).thenReturn(List.of());
        when(mapper.selectAlarmRootCauseStatisticsByUserId(anyInt(), any(), isNull(), anyString())).thenReturn(List.of());
        Map<String, Object> stats = service.getAlarmStatisticsForUser(7, " ", "task-1", 7, false);
        assertEquals(0L, stats.get("totalAlarms"));
        assertEquals(0.0, stats.get("successRate"));
        assertTrue(service.getAlarmTrendForUser(2, " ", "task-1", 7, false).isEmpty());
        assertTrue(service.getAlarmLevelDistributionForUser(2, null, "task-1", 7, false).isEmpty());
        assertTrue(service.getAlarmRootCauseStatisticsForUser(2, null, "task-1", 7, false).isEmpty());
        when(taskManager.canAccessTask("blocked", 7, false)).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.getAlarmHistoryForUser(1, 1, null, "blocked", 7, false));
    }

    @Test
    void retryAndProcessingCoverNoAlarmAndRetryPaths() {
        assertFalse(service.retryPushAlarm(null, 2));
        when(mapper.selectById(1L)).thenReturn(null);
        assertFalse(service.retryPushAlarm(1, 1));

        LogAlarm failed = alarm("task-1", "ERROR", "ERROR happened");
        failed.setPushStatus("FAILED");
        when(mapper.selectById(2L)).thenReturn(failed);
        when(configService.getEffectiveConfig("task-1")).thenReturn(config("ERROR", true, null, 1L));
        assertTrue(service.retryPushAlarm(2, 1));
        assertEquals("RECORDED", failed.getPushStatus());

        when(taskManager.canAccessTask("empty", null, true)).thenReturn(true);
        when(mapper.selectByTaskId("empty")).thenReturn(List.of());
        when(taskManager.getTask("empty")).thenReturn(null);
        service.processAlarmsByTaskId("empty");

        LogAlarm success = alarm("task-1", "ERROR", "x");
        success.setPushStatus("SUCCESS");
        when(mapper.selectByTaskId("task-1")).thenReturn(List.of(success));
        service.processAlarmsByTaskIdForUser("task-1", null, true);
    }

    @Test
    void aiFailureSilenceParsingAndPrivateNormalizersAreCovered() {
        when(mapper.insert(any(LogAlarm.class))).thenReturn(1);
        LogAlarm fallback = service.analyzeLogAndGenerateAlarm("task-1", "ERROR sample");
        assertEquals("FAILED", fallback.getPushStatus());
        assertEquals("ERROR", fallback.getLevel());

        AlarmLifecycleService lifecycle = mock(AlarmLifecycleService.class);
        when(lifecycle.isSilencedNow()).thenReturn(true);
        ReflectionTestUtils.setField(service, "alarmLifecycleService", lifecycle);
        LogAlarm silenced = service.analyzeLogAndGenerateAlarm("task-2", "sample");
        assertEquals("SKIPPED:SILENCED", silenced.getPushStatus());

        ReflectionTestUtils.setField(service, "alarmLifecycleService", null);
        LogAlarm parsed = (LogAlarm) ReflectionTestUtils.invokeMethod(service, "parseAIResponse",
                "task-1", "content", "```json\n{\"level\":\"FATAL\",\"rootCause\":\"root\",\"solution\":\"fix\"}\n```\n");
        assertEquals("FATAL", parsed.getLevel());
        LogAlarm defaults = (LogAlarm) ReflectionTestUtils.invokeMethod(service, "parseAIResponse",
                "task-1", "content", "not-json");
        assertEquals("AI analysis failed", defaults.getRootCause());
        LogAlarm missing = (LogAlarm) ReflectionTestUtils.invokeMethod(service, "parseAIResponse",
                "task-1", "content", "{\"level\":\"\"}");
        assertEquals("AI parsed root cause unavailable", missing.getRootCause());

        assertEquals(3, ((Integer) ReflectionTestUtils.invokeMethod(service, "countErrorEntries",
                "error\nnormal\nFATAL\nException")).intValue());
        assertEquals(0, ((Integer) ReflectionTestUtils.invokeMethod(service, "countErrorEntries", (Object) null)).intValue());
        assertNotNull(ReflectionTestUtils.invokeMethod(service, "generateAlarmFingerprint", parsed));
        assertEquals(4L, (long) (Long) ReflectionTestUtils.invokeMethod(service, "toLong", new BigDecimal("4")));
        assertEquals(5L, (long) (Long) ReflectionTestUtils.invokeMethod(service, "toLong", "5"));
        assertEquals(0L, (long) (Long) ReflectionTestUtils.invokeMethod(service, "toLong", "bad"));
        assertTrue(ReflectionTestUtils.invokeMethod(service, "emptyToNull", " ") == null);
        assertEquals("trimmed", ReflectionTestUtils.invokeMethod(service, "emptyToNull", " trimmed "));
    }

    @Test
    void aggregationAndAccessGuardsCoverBranches() {
        LogAlarm alarm = alarm("task-1", "ERROR", "root cause text");
        ReflectionTestUtils.setField(service, "aggregationEnabled", true);
        ReflectionTestUtils.setField(service, "aggregationDimension", "other");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldAggregate", alarm));
        ReflectionTestUtils.setField(service, "aggregationDimension", "task_level_rootCause");
        when(mapper.selectLatestByFingerprint(anyString(), anyString(), anyString(), anyInt())).thenReturn(null);
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldAggregate", alarm));
        when(mapper.selectLatestByFingerprint(anyString(), anyString(), anyString(), anyInt())).thenReturn(alarm);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldAggregate", alarm));
        assertTrue(ReflectionTestUtils.invokeMethod(service, "emptyToNull", "\t") == null);
    }

    private static LogAlarm alarm(String taskId, String level, String content) {
        LogAlarm alarm = new LogAlarm();
        alarm.setTaskId(taskId);
        alarm.setLevel(level);
        alarm.setLogContent(content);
        alarm.setRootCause("root cause");
        return alarm;
    }

    private static TaskAlarmConfig config(String level, boolean enabled, Integer threshold, Long cooldown) {
        TaskAlarmConfig config = new TaskAlarmConfig();
        config.setAlarmLevel(level);
        config.setEnabled(enabled);
        config.setErrorThreshold(threshold);
        config.setCooldownMs(cooldown);
        return config;
    }
}
