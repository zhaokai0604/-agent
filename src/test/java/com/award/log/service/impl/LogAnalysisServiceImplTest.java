package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.util.LogAnalysisArtifactCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogAnalysisServiceImplTest {

    @Mock
    private AnalysisTaskManager taskManager;
    @Mock
    private LogAnalysisArtifactCleaner artifactCleaner;

    private LogAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LogAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "taskManager", taskManager);
        ReflectionTestUtils.setField(service, "artifactCleaner", artifactCleaner);
    }

    @Test
    void pauseAnalysisShouldUpdateTaskManager() {
        when(taskManager.pauseTask("task-1")).thenReturn(true);
        service.pauseAnalysis("task-1");
        verify(taskManager).pauseTask("task-1");
    }

    @Test
    void resumeAnalysisShouldRejectNonPausedTask() {
        assertThrows(IllegalStateException.class, () -> service.resumeAnalysis("task-1"));
    }

    @Test
    void resumeAnalysisShouldResumePausedTask() {
        when(taskManager.pauseTask("task-1")).thenReturn(true);
        when(taskManager.resumeTask("task-1")).thenReturn(true);
        service.pauseAnalysis("task-1");
        service.resumeAnalysis("task-1");
        verify(taskManager).resumeTask("task-1");
    }

    @Test
    void cancelAnalysisShouldCancelTaskAndPurgeArtifacts() {
        when(taskManager.cancelTask("task-2", "任务被用户取消")).thenReturn(true);
        service.cancelAnalysis("task-2");
        verify(taskManager).cancelTask("task-2", "任务被用户取消");
        verify(artifactCleaner).purgeTaskArtifacts("task-2");
    }

    @Test
    void lostCancellationRacePreservesCompletedArtifacts() {
        when(taskManager.cancelTask("completed-race", "任务被用户取消")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.cancelAnalysis("completed-race"));
        verify(artifactCleaner, never()).purgeTaskArtifacts(anyString());
    }

    @Test
    void failedPauseAndResumeDoNotPublishFalseMemoryState() {
        var states = states();
        states.put("pause-race", "COMPLETED");
        assertThrows(IllegalStateException.class, () -> service.pauseAnalysis("pause-race"));
        assertEquals("COMPLETED", states.get("pause-race"));
        states.put("resume-race", "CANCELLED");
        assertThrows(IllegalStateException.class, () -> service.resumeAnalysis("resume-race"));
        assertEquals("CANCELLED", states.get("resume-race"));
    }

    @Test
    void lostFailureRaceDoesNotPurgeCompletedOutput() {
        states().put("fail-race", "COMPLETED");
        ReflectionTestUtils.invokeMethod(service, "failAnalysis", "fail-race", "late failure");
        assertEquals("COMPLETED", states().get("fail-race"));
        verify(artifactCleaner, never()).purgeTaskArtifacts(anyString());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void completionRunnerSurvivesPauseAndCommitsAfterResume() throws Exception {
        var db = new java.util.concurrent.atomic.AtomicReference<>("PROCESSING");
        var paused = new java.util.concurrent.CountDownLatch(1);
        when(taskManager.getDbStatus("finish")).thenAnswer(call -> { paused.countDown(); return db.get(); });
        when(taskManager.pauseTask("finish")).thenAnswer(call -> db.compareAndSet("PROCESSING", "PAUSED"));
        when(taskManager.resumeTask("finish")).thenAnswer(call -> db.compareAndSet("PAUSED", "PROCESSING"));
        when(taskManager.completeTask(eq("finish"), any(), any()))
                .thenAnswer(call -> db.compareAndSet("PROCESSING", "COMPLETED"));
        service.pauseAnalysis("finish");
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var completion = worker.submit(() -> ReflectionTestUtils.<Boolean>invokeMethod(service,
                    "completeWhenActive", "finish", List.of(), new com.award.log.task.TaskInfo.TaskSummary()));
            assertTrue(paused.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(completion.isDone());
            verify(taskManager, never()).completeTask(anyString(), any(), any());
            service.resumeAnalysis("finish");
            assertTrue(completion.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("COMPLETED", db.get());
        } finally { worker.shutdownNow(); }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void databasePauseWinningCompletionRaceKeepsRunnerAvailableForResume() throws Exception {
        var db = new java.util.concurrent.atomic.AtomicReference<>("PROCESSING");
        var paused = new java.util.concurrent.CountDownLatch(1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(taskManager.completeTask(eq("cross-request"), any(), any())).thenAnswer(call -> {
            if (attempts.getAndIncrement() == 0) { db.set("PAUSED"); return false; }
            return db.compareAndSet("PROCESSING", "COMPLETED");
        });
        when(taskManager.getDbStatus("cross-request")).thenAnswer(call -> {
            if ("PAUSED".equals(db.get())) paused.countDown();
            return db.get();
        });
        when(taskManager.resumeTask("cross-request")).thenAnswer(call -> db.compareAndSet("PAUSED", "PROCESSING"));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var completion = worker.submit(() -> ReflectionTestUtils.<Boolean>invokeMethod(service,
                    "completeWhenActive", "cross-request", List.of(), new com.award.log.task.TaskInfo.TaskSummary()));
            assertTrue(paused.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(completion.isDone());
            service.resumeAnalysis("cross-request");
            assertTrue(completion.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void cancellationInterruptsRegisteredShardAndDefersArtifactPurge() {
        when(taskManager.cancelTask("active", "任务被用户取消")).thenReturn(true);
        var shard = new java.util.concurrent.FutureTask<Void>(() -> null);
        @SuppressWarnings("unchecked")
        var futures = (java.util.Map<String, List<java.util.concurrent.FutureTask<Void>>>)
                ReflectionTestUtils.getField(service, "taskShardFutures");
        futures.put("active", List.of(shard));
        @SuppressWarnings("unchecked")
        var threads = (java.util.Map<String, Thread>) ReflectionTestUtils.getField(service, "taskThreadMap");
        Thread registered = new Thread();
        threads.put("active", registered);
        service.cancelAnalysis("active");
        assertTrue(shard.isCancelled());
        assertTrue(registered.isInterrupted());
        verify(artifactCleaner, never()).purgeTaskArtifacts("active");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> states() {
        return (java.util.Map<String, String>) ReflectionTestUtils.getField(service, "taskStatusMap");
    }

    @Test
    void ensureReportArtifactsShouldSkipBlankInput() throws IOException {
        assertDoesNotThrow(() -> service.ensureReportArtifacts(null, List.of()));
        assertDoesNotThrow(() -> service.ensureReportArtifacts("t1", List.of()));
    }

    @Test
    void invalidLogTimestampDoesNotAbortParsingAndUsesUnparsedMarker() {
        assertEquals(0L, LogAnalysisServiceImpl.parseTimestampOrUnparsed("2024-13-45 27:91:99"));
        assertEquals(0L, LogAnalysisServiceImpl.parseTimestampOrUnparsed(null));
        assertTrue(LogAnalysisServiceImpl.parseTimestampOrUnparsed("2024-01-01 12:00:00") > 0L);
    }

    @Test
    void ensureReportArtifactsShouldWriteCsvAndHtml() throws IOException {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("error line");
        row.setSeverity(com.award.log.model.LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");
        row.setAnomaly(true);
        row.setPid("<img src=x onerror=alert(1)>");
        row.setDesensitizedLog("<script>alert(2)</script>");
        row.setAnomalyReasons(java.util.Set.of("<svg onload=alert(3)>"));

        service.ensureReportArtifacts("export-task", List.of(row));

        Path outputDir = Path.of(System.getProperty("user.dir"), "target", "output", "export-task");
        assertTrue(Files.exists(outputDir));
        String html = Files.readString(outputDir.resolve("log_analysis_report.html"));
        assertFalse(html.contains("<img src=x"));
        assertFalse(html.contains("<script>alert(2)</script>"));
        assertFalse(html.contains("<svg onload"));
        assertTrue(html.contains("&lt;script&gt;alert(2)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;img src=x"));
        assertTrue(html.contains("&lt;svg onload"));
        try (var paths = Files.list(outputDir)) {
            assertTrue(paths.findAny().isPresent());
        }
    }

}
