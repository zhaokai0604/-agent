package com.award.log.task;

import com.award.log.common.PageResult;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.service.*;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogTaskDiagnosisServiceTest extends LogDiagnosisFixture {
    AnalysisTaskManager manager;
    AiDiagnosisService model;
    ThreadPoolTaskExecutor executor;
    LogTaskDiagnosisService service;
    @BeforeEach void runtime() {
        manager=mock(AnalysisTaskManager.class);model=mock(AiDiagnosisService.class);
        executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(1);executor.initialize();
        service=new LogTaskDiagnosisService(jobs,manager,model,executor);
        var task=new TaskInfo();
        var sample=new EnhancedLogParseResultEntity("ERROR connection refused");sample.setAnomaly(true);sample.setDesensitizedLog("ERROR connection refused");
        task.setResult(List.of(sample));when(manager.getTask("test")).thenReturn(task);
        when(manager.getTaskDetailsPage("test",1,100,false)).thenReturn(new PageResult<>(List.of(sample),1));
    }
    @AfterEach void shutdown() { service.close();executor.shutdown(); }
    private void terminal(String status) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertEquals(status,service.status("test").status()));
    }
    @Test void successfulModelOutputIsPublishedSeparatelyFromAnalysisAndReport() {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.just("evidence: ","connection refused"));
        service.start("test","success");terminal("SUCCEEDED");
        assertEquals("evidence: connection refused",service.status("test").diagnosis());
        verify(model,times(1)).generateTrackedDiagnosis(anyList());
    }
    @Test void partialOutputFollowedByFailureIsNotPublishedOrRetried() {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.concat(Flux.just("partial"),Flux.error(new IllegalStateException("provider failed"))));
        service.start("test","failure");terminal("FAILED");
        assertNull(service.status("test").diagnosis());
        assertEquals("old untracked text",jdbc.queryForObject("SELECT ai_diagnosis FROM log_analysis_task",String.class));
        verify(model,times(1)).generateTrackedDiagnosis(anyList());
    }
    @Test void explicitCancellationDisposesModelSubscription() {
        AtomicBoolean disposed=new AtomicBoolean();
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.<String>never().doOnCancel(()->disposed.set(true)));
        var job=service.start("test","cancel");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->verify(model).generateTrackedDiagnosis(anyList()));
        service.cancel("test",job.runId());terminal("CANCELLED");
        await().atMost(Duration.ofSeconds(3)).untilTrue(disposed);
    }
    @Test void totalDeadlineStopsSilentProviderWithoutChangingAnalysis() {
        jobs=new LogDiagnosisJobStore(jdbc,tasks,3,1);service=new LogTaskDiagnosisService(jobs,manager,model,executor);
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.never());
        service.start("test","timeout");terminal("TIMED_OUT");
        assertEquals("COMPLETED",tasks.snapshot("test").status());
    }
    @Test void rejectedQueueHasExplicitFailedState() {
        executor.shutdown();service.start("test","rejected");terminal("FAILED");
        assertEquals("QUEUE_REJECTED",service.status("test").errorCode());verifyNoInteractions(model);
    }
    @Test void emptyAndOversizedOutputsNeverBecomeSuccessfulReports() {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.just(" "));
        service.start("test","empty");terminal("FAILED");
        assertEquals("EMPTY_MODEL_REPLY",service.status("test").errorCode());
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.just("x".repeat(65537)));
        service.start("test","oversized");terminal("FAILED");assertNull(service.status("test").diagnosis());
    }
    @Test void unicodeOutputIsBoundedByDatabaseUtf8Bytes() {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.just("诊".repeat(22000)));
        service.start("test","unicode");terminal("FAILED");
        assertEquals("OUTPUT_LIMIT_EXCEEDED",service.status("test").errorCode());
    }
    @Test void shutdownMarksActiveJobInterruptedAndRejectsNewWork() {
        when(model.generateTrackedDiagnosis(anyList())).thenReturn(Flux.never());
        service.start("test","shutdown");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->verify(model).generateTrackedDiagnosis(anyList()));
        service.close();terminal("INTERRUPTED");
        assertThrows(IllegalStateException.class,()->service.start("test","new"));
    }
}
