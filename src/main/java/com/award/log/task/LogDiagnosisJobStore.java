package com.award.log.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** All job transitions take the same business-task lock as deletion and report publication. */
@Service
public class LogDiagnosisJobStore {
    public record Job(String runId, String taskId, String status, int attempt,
                      Instant deadline, String errorCode, String diagnosis) {
        public boolean active() { return Set.of("QUEUED", "RUNNING").contains(status); }
    }
    private final JdbcTemplate jdbc;
    private final DurableLogTaskStore tasks;
    private final int maxAttempts;
    private final int timeoutSeconds;
    private final String instance = UUID.randomUUID().toString();

    public LogDiagnosisJobStore(JdbcTemplate jdbc, DurableLogTaskStore tasks,
            @Value("${log.diagnosis.max-attempts:3}") int maxAttempts,
            @Value("${log.diagnosis.timeout-seconds:120}") int timeoutSeconds) {
        if (maxAttempts < 1 || maxAttempts > 10 || timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalArgumentException("Invalid diagnosis attempt/deadline limits");
        this.jdbc = jdbc; this.tasks = tasks;
        this.maxAttempts = maxAttempts; this.timeoutSeconds = timeoutSeconds;
    }

    private Map<String,Object> business(String id) {
        var rows = jdbc.queryForList("SELECT status,ai_diagnosis FROM log_analysis_task WHERE task_id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Job convert(Map<String,Object> row) {
        return new Job((String) row.get("run_id"), (String) row.get("task_id"), (String) row.get("status"),
                ((Number) row.get("attempt")).intValue(), ((Timestamp) row.get("deadline_at")).toInstant(),
                (String) row.get("error_code"), "SUCCEEDED".equals(row.get("status")) ? (String) row.get("result_text") : null);
    }
    private Job byRun(String runId) {
        var rows = jdbc.queryForList("SELECT * FROM log_diagnosis_job WHERE run_id=?", runId);
        return rows.isEmpty() ? null : convert(rows.get(0));
    }
    private Job latest(String id) {
        var rows = jdbc.queryForList("SELECT * FROM log_diagnosis_job WHERE task_id=? ORDER BY attempt DESC LIMIT 1", id);
        return rows.isEmpty() ? null : convert(rows.get(0));
    }
    private void mirror(String id, String status) {
        jdbc.update("UPDATE log_task_runtime SET ai_status=?,updated_at=? WHERE task_id=? AND runtime_status<>'DELETED'",
                status, Timestamp.from(Instant.now()), id);
    }
    public Job status(String id) {
        return tasks.locked(id, () -> {
            var task = business(id);
            if (task == null) throw new NoSuchElementException("Task not found");
            Job job = latest(id);
            if (job == null) return new Job(null, id, task.get("ai_diagnosis") == null ? "NOT_REQUESTED" : "UNTRACKED", 0, null, null, null);
            if (job.active() && !job.deadline().isAfter(Instant.now())) {
                finish(job, "TIMED_OUT", "DEADLINE_EXCEEDED", null);
                job = latest(id);
            }
            return job;
        });
    }
    public Job attemptStatus(Job attempt) {
        return tasks.locked(attempt.taskId(), () -> {
            if (business(attempt.taskId()) == null) throw new NoSuchElementException("Task not found");
            Job current = byRun(attempt.runId());
            if (current == null || !attempt.taskId().equals(current.taskId())) throw new NoSuchElementException("Diagnosis not found");
            if (current.active() && !current.deadline().isAfter(Instant.now())) {
                finish(current,"TIMED_OUT","DEADLINE_EXCEEDED",null);
                current=byRun(attempt.runId());
            }
            return current;
        });
    }
    public Job begin(String id, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("A valid diagnosis requestId is required");
        return tasks.locked(id, () -> {
            var task = business(id);
            if (task == null) throw new NoSuchElementException("Task not found");
            if (!"COMPLETED".equals(task.get("status"))) throw new IllegalStateException("Analysis is not completed");
            var existing = jdbc.queryForList("SELECT * FROM log_diagnosis_job WHERE task_id=? AND request_id=?", id, requestId);
            if (!existing.isEmpty()) return convert(existing.get(0));
            Job previous = latest(id);
            if (previous != null && previous.active()) {
                if (previous.deadline().isAfter(Instant.now())) return previous;
                finish(previous, "TIMED_OUT", "DEADLINE_EXCEEDED", null);
            }
            int attempt = previous == null ? 1 : previous.attempt() + 1;
            if (attempt > maxAttempts) throw new IllegalStateException("Diagnosis attempt budget exhausted");
            String run = UUID.randomUUID().toString();
            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            jdbc.update("INSERT INTO log_diagnosis_job(run_id,task_id,request_id,instance_id,status,attempt,deadline_at) "
                    + "VALUES(?,?,?,?,'QUEUED',?,?)", run, id, requestId, instance, attempt, Timestamp.from(deadline));
            mirror(id, "QUEUED");
            return byRun(run);
        });
    }
    public boolean claim(Job job) {
        return tasks.locked(job.taskId(), () -> {
            var task = business(job.taskId());
            if (task == null || !"COMPLETED".equals(task.get("status"))) return false;
            int changed = jdbc.update("UPDATE log_diagnosis_job SET status='RUNNING' WHERE run_id=? "
                    + "AND instance_id=? AND status='QUEUED' AND deadline_at>?", job.runId(), instance, Timestamp.from(Instant.now()));
            if (changed > 0) mirror(job.taskId(), "RUNNING");
            return changed > 0;
        });
    }
    public boolean current(Job job) {
        Job current = byRun(job.runId());
        return current != null && current.active() && current.deadline().isAfter(Instant.now());
    }
    public boolean finish(Job job, String status, String error, String text) {
        return tasks.locked(job.taskId(), () -> {
            Job current = byRun(job.runId());
            if (current == null || !job.taskId().equals(current.taskId()) || !current.active()) return false;
            if (!Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "INTERRUPTED").contains(status))
                throw new IllegalArgumentException("Invalid terminal diagnosis state");
            var task = business(job.taskId());
            if (task == null || !"COMPLETED".equals(task.get("status"))) return false;
            if ("SUCCEEDED".equals(status)) {
                if (!"RUNNING".equals(current.status()) || !current.deadline().isAfter(Instant.now())) return false;
                if (text == null || text.isBlank() || text.length() > 65536
                        || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60 * 1024)
                    throw new IllegalArgumentException("Invalid diagnosis output");
                jdbc.update("UPDATE log_analysis_task SET ai_diagnosis=? WHERE task_id=? AND status='COMPLETED'", text, job.taskId());
            }
            jdbc.update("UPDATE log_diagnosis_job SET status=?,error_code=?,finished_at=?,result_text=? WHERE run_id=?", status, error,
                    Timestamp.from(Instant.now()), "SUCCEEDED".equals(status) ? text : null, job.runId());
            mirror(job.taskId(), status);
            return true;
        });
    }
    public Job cancel(String id, String runId) {
        return tasks.locked(id, () -> {
            Job current = latest(id);
            if (current == null || !Objects.equals(current.runId(), runId)) throw new IllegalStateException("Diagnosis run changed");
            finish(current, "CANCELLED", "USER_CANCELLED", null);
            return status(id);
        });
    }
    public void reconcileExpired() {
        var rows = jdbc.queryForList("SELECT * FROM log_diagnosis_job WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=? LIMIT 100",
                Timestamp.from(Instant.now()));
        for (var row : rows) finish(convert(row), "TIMED_OUT", "DEADLINE_EXCEEDED", null);
    }
}
