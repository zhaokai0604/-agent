package com.award.log.task;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisDetail;
import com.award.log.model.LogAnalysisTask;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import com.award.log.util.LogAnalysisArtifactCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskManagerCoverageTest {

    @Mock private LogAnalysisTaskMapper taskMapper;
    @Mock private LogAnalysisDetailMapper detailMapper;
    @Mock private LogAnalysisArtifactCleaner artifactCleaner;

    private AnalysisTaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new AnalysisTaskManager();
        ReflectionTestUtils.setField(manager, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(manager, "detailMapper", detailMapper);
        ReflectionTestUtils.setField(manager, "artifactCleaner", artifactCleaner);
        ReflectionTestUtils.setField(manager, "detailBatchSize", 100);
        ReflectionTestUtils.setField(manager, "reportAnomalyLimit", 20);
        ReflectionTestUtils.setField(manager, "exportReloadLimit", 1000);
    }

    @Test
    void pagesTasksForAdminAndUserAndInitializesAccessChecks() {
        LogAnalysisTask task = task("t1", 7, "a.log", "PENDING");
        when(taskMapper.countWithFilter(any(), any(), any(), any())).thenReturn(1L);
        when(taskMapper.selectPageWithFilter(anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(List.of(task));
        when(taskMapper.countWithFilterByUserId(anyInt(), any(), any(), any(), any())).thenReturn(1L);
        when(taskMapper.selectPageWithFilterByUserId(anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(List.of(task));

        assertEquals(1L, manager.getTasksPage(0, 0, " a.log ", " PENDING ", "2026-02-03", "2026-01-01").getTotal());
        assertEquals(1, manager.getTasksPageForUser(7, false, 1, 10, null, null, null, null).getList().size());
        assertTrue(manager.getTasksPageForUser(null, false, 1, 10, null, null, null, null).getList().isEmpty());
        assertEquals(1, manager.getTasksPageForUser(7, true, 1, 10, null, null, null, null).getList().size());

        when(taskMapper.selectById("t1")).thenReturn(task);
        when(taskMapper.selectByIdAndUserId("t1", 7)).thenReturn(task);
        when(taskMapper.selectAll()).thenReturn(List.of(task));
        when(taskMapper.selectById("missing")).thenReturn(null);
        assertEquals("t1", manager.initTask("t1", "a.log").getTaskId());
        assertEquals("t1", manager.getTask("t1").getTaskId());
        assertNull(manager.getTask("missing"));
        assertEquals("t1", manager.getTaskForUser("t1", 7, false).getTaskId());
        assertNull(manager.getTaskForUser("t1", null, false));
        assertEquals(1, manager.getAllTasks().size());
        assertTrue(manager.canAccessTask("t1", 7, false));
        assertTrue(manager.canAccessTask("t1", 7, true));
        assertFalse(manager.canAccessTask("missing", null, false));
    }

    @Test
    void progressesCompletesAndPersistsDetailsInBatches() {
        LogAnalysisTask active = task("t2", 1, "b.log", "PROCESSING");
        when(taskMapper.selectById("t2")).thenReturn(active);
        manager.updateProgress("t2", 55, "parsing");
        verify(taskMapper).updateById(any(LogAnalysisTask.class));

        LogAnalysisTask terminal = task("done", 1, "b.log", "COMPLETED");
        when(taskMapper.selectById("done")).thenReturn(terminal);
        manager.updateProgress("done", 99, "ignored");

        TaskInfo.TaskSummary summary = new TaskInfo.TaskSummary();
        summary.setTotalLogs(101);
        summary.setAnomalyCount(4);
        summary.setAnomalyRate(0.04);
        summary.setCostTime(123);
        summary.setLineCapApplied(true);
        summary.setLinesSkipped(8);
        manager.completeTask("t2", entities(101), summary);
        verify(detailMapper, org.mockito.Mockito.times(2)).batchInsert(any(List.class));

        manager.completeTask("empty", List.of(), null);
        manager.completeTask("null", null, null);
        manager.updateAiDiagnosis("t2", "diagnosis");
    }

    @Test
    void completedTaskBuildsSampleSummaryDistributionAndDetailsPages() {
        LogAnalysisTask completed = task("t3", 3, "c.log", "COMPLETED");
        completed.setProgress(100);
        completed.setCurrentStep("Analysis completed (truncated, skipped 12 lines)");
        completed.setTotalLogs(30);
        completed.setAnomalyCount(3);
        completed.setAnomalyRate(0.1);
        completed.setCostTime(88L);
        LogAnalysisDetail d = detail("t3", "ERROR_LEVEL", "APPLICATION_LOG", true);
        when(taskMapper.selectById("t3")).thenReturn(completed);
        when(detailMapper.selectAnomaliesByTaskId("t3", 20)).thenReturn(List.of(d));
        when(detailMapper.countByTaskId("t3")).thenReturn(3L);
        when(detailMapper.selectSeverityDistributionByTaskId("t3")).thenReturn(List.of(
                Map.of("severity", "ERROR", "cnt", 2),
                Map.of("SEVERITY", "WARN", "CNT", 1),
                new LinkedHashMap<>()));

        TaskInfo info = manager.getTask("t3");
        assertEquals(1, info.getResult().size());
        assertEquals(3L, info.getSummary().getDetailTotal());
        assertTrue(info.getSummary().isResultTruncated());
        assertTrue(info.getSummary().isLineCapApplied());
        assertEquals(12, info.getSummary().getLinesSkipped());
        assertEquals(2, info.getSummary().getSeverityCounts().get("ERROR").intValue());

        when(detailMapper.countAnomaliesByTaskId("t3")).thenReturn(1L);
        when(detailMapper.selectByTaskIdPage("t3", 0, 200, true)).thenReturn(List.of(d));
        assertEquals(1, manager.getTaskDetailsPage("t3", 0, 999, true).getList().size());
        when(detailMapper.selectByTaskIdPage("t3", 0, 1000, false)).thenReturn(List.of(d));
        assertEquals(1, manager.loadDetailsForExport("t3").size());
    }

    @Test
    void lifecycleGuardsDeleteAndUserAuthorizationAreFailClosed() {
        LogAnalysisTask active = task("active", 1, "a", "PROCESSING");
        LogAnalysisTask failed = task("failed", 1, "a", "FAILED");
        when(taskMapper.selectById("active")).thenReturn(active);
        when(taskMapper.selectById("failed")).thenReturn(failed);
        manager.failTask("active", "bad");
        manager.cancelTask("active", null);
        manager.pauseTask("active");
        manager.resumeTask("active");
        manager.failTask("failed", "ignored");
        manager.cancelTask("failed", "ignored");
        manager.pauseTask("failed");
        manager.resumeTask("failed");
        assertTrue(manager.isTerminalDbStatus("failed"));
        assertEquals("FAILED", manager.getDbStatus("failed"));
        when(taskMapper.selectById("missing")).thenReturn(null);
        assertFalse(manager.isTerminalDbStatus("missing"));
        assertNull(manager.getDbStatus("missing"));

        when(taskMapper.deleteById("ok")).thenReturn(1);
        assertTrue(manager.deleteTask("ok"));
        when(taskMapper.deleteById("none")).thenReturn(0);
        assertFalse(manager.deleteTask("none"));
        doThrow(new IllegalStateException("db down")).when(detailMapper).deleteByTaskId("bad");
        assertThrows(RuntimeException.class, () -> manager.deleteTask("bad"));

        when(taskMapper.selectByIdAndUserId("ok", 1)).thenReturn(active);
        when(taskMapper.deleteById("ok")).thenReturn(1);
        assertTrue(manager.deleteTaskForUser("ok", 1, false));
        assertFalse(manager.deleteTaskForUser("no", 1, false));
        assertFalse(manager.deleteTaskForUser("no", null, false));
    }

    private static LogAnalysisTask task(String id, int user, String file, String status) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(id);
        task.setUserId(user);
        task.setFileName(file);
        task.setStatus(status);
        task.setProgress(20);
        task.setCurrentStep("step");
        task.setCreateTime(LocalDateTime.now());
        return task;
    }

    private static List<EnhancedLogParseResultEntity> entities(int count) {
        List<EnhancedLogParseResultEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("line-" + i);
            row.setLogTime("2026-01-01 00:00:00");
            row.setSeverity(i % 2 == 0 ? LogSeverityLevel.ERROR_LEVEL : LogSeverityLevel.INFO_LEVEL);
            row.setProtocol(LogProtocolType.APPLICATION_LOG);
            row.setPid(String.valueOf(i));
            row.setTemplateId("T" + (i % 3));
            row.setAnomaly(i % 3 == 0);
            row.setAnomalyScore(0.5);
            row.getAnomalyReasons().add("reason");
            rows.add(row);
        }
        return rows;
    }

    private static LogAnalysisDetail detail(String taskId, String severity, String protocol, boolean anomaly) {
        LogAnalysisDetail d = new LogAnalysisDetail();
        d.setTaskId(taskId);
        d.setDesensitizedLog("error line");
        d.setSeverity(severity);
        d.setProtocol(protocol);
        d.setPid("9");
        d.setIsAnomaly(anomaly);
        d.setAnomalyScore(0.9);
        d.setAnomalyReasons("keyword,frequency");
        d.setTemplateId("T1");
        d.setLogTime("2026-01-01 00:00:00");
        return d;
    }
}
