package com.award.log.service;

import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.LogDiagnosisJobStore;
import com.award.log.task.LogDiagnosisJobStore.Job;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only diagnosis has its own bounded queue, total deadline, attempts and cancellation. */
@Service
public class LogTaskDiagnosisService {
    private static class OutputLimitException extends IllegalStateException {}
    private record Active(Job job, FutureTask<Void> future) {}
    private final LogDiagnosisJobStore store;
    private final AnalysisTaskManager tasks;
    private final AiDiagnosisService model;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String,Active> active = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public LogTaskDiagnosisService(LogDiagnosisJobStore store, AnalysisTaskManager tasks, AiDiagnosisService model,
            @Qualifier("logDiagnosisExecutor") ThreadPoolTaskExecutor executor) {
        this.store=store; this.tasks=tasks; this.model=model; this.executor=executor;
    }
    public Job status(String id) { return store.status(id); }
    public Job start(String id, String requestId) {
        if (closing) throw new IllegalStateException("Diagnosis runtime is closing");
        Job job = store.begin(id, requestId);
        if (!"QUEUED".equals(job.status())) return job;
        FutureTask<Void> future = new FutureTask<>(() -> { run(job); return null; });
        Active previous = active.putIfAbsent(job.runId(), new Active(job, future));
        if (previous != null) return store.attemptStatus(job);
        if (closing) {
            active.remove(job.runId());future.cancel(true);
            store.finish(job,"INTERRUPTED","RUNTIME_SHUTDOWN",null);
            return store.attemptStatus(job);
        }
        try { executor.execute(future); }
        catch (RuntimeException rejected) {
            active.remove(job.runId()); future.cancel(true);
            store.finish(job,"FAILED","QUEUE_REJECTED",null);
        }
        return store.attemptStatus(job);
    }
    private void run(Job job) {
        try {
            if (closing) { store.finish(job,"INTERRUPTED","RUNTIME_SHUTDOWN",null); return; }
            if (!store.claim(job)) return;
            // Bounded database samples; never load an entire multi-GB task for a model prompt.
            var task = tasks.getTask(job.taskId());
            if (task == null) return;
            List<EnhancedLogParseResultEntity> samples = new ArrayList<>();
            if (task.getResult() != null) samples.addAll(AiDiagnosisContextBuilder.selectFocusLines(task.getResult()));
            var page = tasks.getTaskDetailsPage(job.taskId(),1,100,false);
            if (samples.isEmpty()) samples.addAll(AiDiagnosisContextBuilder.selectFocusLines(page.getList()));
            samples.addAll(AiDiagnosisContextBuilder.selectBaselineLines(page.getList()));
            if (samples.isEmpty()) {
                store.finish(job,"FAILED","NO_DIAGNOSIS_EVIDENCE",null); return;
            }
            Duration remaining = Duration.between(Instant.now(), job.deadline());
            if (remaining.isNegative() || remaining.isZero()) { store.finish(job,"TIMED_OUT","DEADLINE_EXCEEDED",null); return; }
            StringBuilder response = new StringBuilder();
            var stream = model.generateTrackedDiagnosis(samples);
            if (stream == null) throw new IllegalStateException("No model stream");
            stream.doOnNext(chunk -> {
                if (chunk != null) {
                    if (response.length() + chunk.length() > 65536) throw new OutputLimitException();
                    response.append(chunk);
                    if (response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60 * 1024)
                        throw new OutputLimitException();
                }
            }).then().block(remaining);
            if (response.toString().isBlank()) { store.finish(job,"FAILED","EMPTY_MODEL_REPLY",null); return; }
            store.finish(job,"SUCCEEDED",null,response.toString());
        } catch (RuntimeException error) {
            String state = closing || Thread.currentThread().isInterrupted() ? "INTERRUPTED"
                    : !job.deadline().isAfter(Instant.now()) ? "TIMED_OUT" : "FAILED";
            store.finish(job,state,"INTERRUPTED".equals(state)?"RUNTIME_SHUTDOWN"
                    : "TIMED_OUT".equals(state)?"DEADLINE_EXCEEDED"
                    : error instanceof OutputLimitException ? "OUTPUT_LIMIT_EXCEEDED" : "MODEL_CALL_FAILED",null);
        } finally { active.remove(job.runId()); }
    }
    public Job cancel(String id, String runId) {
        Job job = store.cancel(id, runId);
        Active runner = active.remove(runId);
        if (runner != null) runner.future().cancel(true);
        return job;
    }
    /** Legacy synchronous endpoint waits for the same job, never calls a model directly. */
    public String await(String id, String requestId) {
        Job job = start(id, requestId);
        while (job.active()) {
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Diagnosis wait interrupted"); }
            job = store.attemptStatus(job);
        }
        if (!"SUCCEEDED".equals(job.status())) throw new IllegalStateException("Diagnosis failed: " + job.errorCode());
        return job.diagnosis();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { store.reconcileExpired(); }
    @Scheduled(fixedDelayString="${log.diagnosis.maintenance-ms:5000}")
    public void reconcile() {
        if (closing) return;
        store.reconcileExpired();
        active.forEach((run, runner) -> {
            if (!store.current(runner.job())) { runner.future().cancel(true); active.remove(run, runner); }
        });
    }
    @PreDestroy public void close() {
        closing=true;
        active.values().forEach(runner -> {
            store.finish(runner.job(),"INTERRUPTED","RUNTIME_SHUTDOWN",null);
            runner.future().cancel(true);
        });
        active.clear();
    }
}
