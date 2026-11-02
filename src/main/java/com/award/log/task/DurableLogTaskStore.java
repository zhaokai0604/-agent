package com.award.log.task;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.award.log.util.LogAnalysisArtifactCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

/** Durable input, execution leases, fencing and cross-instance artifact serialization. */
@Service
@Slf4j
public class DurableLogTaskStore {
    public record UploadReservation(String taskId, boolean created) {}
    public record Snapshot(String phase, String status, String reportStatus, String aiStatus,
                           int attempt, String error, boolean recoverable) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path inputRoot;
    private final Duration lease;
    private final String instance = UUID.randomUUID().toString();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    @Value("${log.analysis.input-retention-hours:24}")
    private long inputRetentionHours = 24;
    @Value("${log.analysis.heartbeat-ms:10000}")
    private long heartbeatMillis = 10000;
    @Autowired
    private LogAnalysisArtifactCleaner artifactCleaner;

    public DurableLogTaskStore(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Value("${log.analysis.input-dir:./data/log-inputs}") String inputDir,
            @Value("${log.analysis.lease-seconds:90}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.inputRoot = Path.of(inputDir).toAbsolutePath().normalize();
        if (leaseSeconds < 15) throw new IllegalArgumentException("Task lease must be at least 15 seconds");
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    @jakarta.annotation.PostConstruct
    public void validateSettings() {
        if (heartbeatMillis <= 0 || heartbeatMillis * 2 >= lease.toMillis() || inputRetentionHours < 1) {
            throw new IllegalArgumentException("Heartbeat must be shorter than half the lease; retention must be positive");
        }
    }

    private void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid task id");
        }
    }

    /** Always lock the business task before its runtime row to keep a single lock order. */
    public <T> T locked(String id, Supplier<T> action) {
        validId(id);
        return tx.execute(ignored -> {
            jdbc.queryForList("SELECT task_id FROM log_analysis_task WHERE task_id=? FOR UPDATE", id);
            jdbc.queryForList("SELECT task_id FROM log_task_runtime WHERE task_id=? FOR UPDATE", id);
            return action.get();
        });
    }

    private String taskStatus(String id) {
        var rows = jdbc.queryForList("SELECT status FROM log_analysis_task WHERE task_id=?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> row(String id) {
        var rows = jdbc.queryForList("SELECT * FROM log_task_runtime WHERE task_id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Timestamp now() { return Timestamp.from(Instant.now()); }
    private Timestamp deadline() { return Timestamp.from(Instant.now().plus(lease)); }

    /** Persist request-key ownership before task creation; retries can reclaim an orphaned reservation. */
    public UploadReservation reserveUpload(int userId, String requestKey, File upload, String candidateTaskId) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid upload idempotency key");
        validId(candidateTaskId);
        final String contentHash;
        try { contentHash = hash(upload.toPath()); }
        catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint uploaded input", e); }
        try {
            return tx.execute(status -> reserveUploadLocked(userId, requestKey, contentHash, candidateTaskId));
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return tx.execute(status -> reserveUploadLocked(userId, requestKey, contentHash, candidateTaskId));
        }
    }

    private UploadReservation reserveUploadLocked(int userId, String requestKey, String contentHash, String candidateTaskId) {
        var rows = jdbc.queryForList("SELECT content_sha256,task_id FROM log_upload_request WHERE user_id=? AND request_key=? FOR UPDATE",
                userId, requestKey);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO log_upload_request(user_id,request_key,content_sha256,task_id) VALUES(?,?,?,?)",
                    userId, requestKey, contentHash, candidateTaskId);
            return new UploadReservation(candidateTaskId, true);
        }
        var existing = rows.get(0);
        if (!contentHash.equals(existing.get("content_sha256")))
            throw new IllegalArgumentException("Idempotency key was already used for different file content");
        String taskId = (String) existing.get("task_id");
        if (taskStatus(taskId) == null) {
            Timestamp reservedAt = (Timestamp) existing.get("created_at");
            if (reservedAt != null && reservedAt.toInstant().isAfter(Instant.now().minus(lease)))
                return new UploadReservation(taskId, false);
            jdbc.update("UPDATE log_upload_request SET task_id=?,created_at=? WHERE user_id=? AND request_key=?",
                    candidateTaskId, now(), userId, requestKey);
            return new UploadReservation(candidateTaskId, true);
        }
        return new UploadReservation(taskId, false);
    }

    /** Persist a private copy before handing the upload to an asynchronous queue. */
    public void stage(String id, File upload) {
        locked(id, () -> {
            if (!"PENDING".equals(taskStatus(id))) throw new IllegalStateException("Task cannot accept input");
            if (row(id) != null) throw new IllegalStateException("Task input is already staged");
            Path copy = inputRoot.resolve(id + ".log");
            boolean created = false;
            try {
                Files.createDirectories(inputRoot);
                if (Files.isSymbolicLink(inputRoot) || !inputRoot.toRealPath().equals(inputRoot)) {
                    throw new IOException("Input storage must not traverse symbolic links");
                }
                Files.copy(upload.toPath(), copy); // CREATE_NEW; never overwrite another task's input.
                created = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) try { Files.deleteIfExists(copy); } catch (IOException ignored) {}
                    }
                });
                String hash = hash(copy);
                jdbc.update("INSERT INTO log_task_runtime(task_id,instance_id,phase,runtime_status,heartbeat_at,lease_until,input_path,input_sha256,ai_status) "
                        + "VALUES(?,?,'QUEUED','QUEUED',?,?,?,?,'NOT_REQUESTED')", id, instance, now(), deadline(), copy.toString(), hash);
                afterCommit(() -> queued.add(id));
                return null;
            } catch (Exception e) {
                if (created) try { Files.deleteIfExists(copy); } catch (IOException ignored) {}
                throw new IllegalStateException("Cannot persist uploaded input", e);
            }
        });
    }

    public String claim(String id) {
        if (closing) return null;
        return locked(id, () -> {
            if (closing) return null;
            Map<String,Object> runtime = row(id);
            if (runtime == null || !"QUEUED".equals(runtime.get("runtime_status"))) return null;
            if (!instance.equals(runtime.get("instance_id"))) return null;
            String status = taskStatus(id);
            if (status == null || !Set.of("PENDING", "PAUSED", "PROCESSING").contains(status)) return null;
            String execution = UUID.randomUUID().toString();
            jdbc.update("UPDATE log_task_runtime SET execution_id=?,instance_id=?,phase='READING',runtime_status='RUNNING',"
                    + "attempt=attempt+1,heartbeat_at=?,lease_until=?,updated_at=? WHERE task_id=?",
                    execution, instance, now(), deadline(), now(), id);
            afterCommit(() -> { active.put(id, execution); queued.remove(id); });
            return execution;
        });
    }

    public File retainedInputForExecution(String id) {
        return locked(id, () -> {
            if (!currentExecution(id)) throw new IllegalStateException("Execution lease is no longer current");
            var r = row(id);
            try {
                Path input = inputRoot.resolve(id + ".log");
                if (r == null || !input.toString().equals(r.get("input_path"))
                        || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || !input.toRealPath().equals(input)
                        || !Objects.equals(r.get("input_sha256"), hash(input))) throw new IOException("Retained input is not intact");
                return input.toFile();
            } catch (Exception e) { throw new IllegalStateException("Cannot read retained input", e); }
        });
    }

    public boolean isRetainedInput(File file) {
        return file != null && inputRoot.equals(file.toPath().toAbsolutePath().normalize().getParent());
    }

    /** Must run within the same transaction as the subsequent task mutation. */
    public boolean currentExecution(String id) {
        var token = TaskExecutionContext.current();
        if (token == null) return true; // Explicit user controls are not execution workers.
        if (!id.equals(token.taskId())) return false;
        jdbc.queryForList("SELECT task_id FROM log_analysis_task WHERE task_id=? FOR UPDATE", id);
        var rows = jdbc.queryForList("SELECT execution_id,runtime_status,lease_until FROM log_task_runtime WHERE task_id=? FOR UPDATE", id);
        if (rows.isEmpty()) return false;
        var runtime = rows.get(0);
        return token.executionId().equals(runtime.get("execution_id"))
                && "RUNNING".equals(runtime.get("runtime_status"))
                && runtime.get("lease_until") instanceof Timestamp until && until.toInstant().isAfter(Instant.now());
    }

    public void progress(String id, String phase) {
        if (!currentExecution(id)) return;
        jdbc.update("UPDATE log_task_runtime SET phase=?,updated_at=? WHERE task_id=? AND runtime_status='RUNNING'",
                phase == null ? "PROCESSING" : phase.substring(0, Math.min(64, phase.length())), now(), id);
    }

    public void finish(String id, String status) {
        jdbc.update("UPDATE log_task_runtime SET runtime_status=?,phase=?,lease_until=NULL,updated_at=? WHERE task_id=? AND runtime_status<>'DELETED'",
                status, status, now(), id);
    }

    public void release(String id, String execution) { active.remove(id, execution); }

    public Snapshot snapshot(String id) {
        var r = row(id);
        if (r == null) return null;
        String aiStatus = (String) r.get("ai_status");
        return new Snapshot((String) r.get("phase"), (String) r.get("runtime_status"),
                (String) r.get("report_status"), aiStatus, ((Number)r.get("attempt")).intValue(),
                (String)r.get("last_error"), "RECOVERY_REQUIRED".equals(r.get("runtime_status")) && r.get("input_path") != null);
    }

    public boolean needsRecovery(String id) {
        var r = snapshot(id); return r != null && r.recoverable();
    }

    /** User-authorized restart of read-only analysis from its retained input, never write replay. */
    public File prepareRecovery(String id) {
        return locked(id, () -> {
            var r = row(id);
            if (r == null || !"RECOVERY_REQUIRED".equals(r.get("runtime_status"))
                    || !"RECOVERY_REQUIRED".equals(taskStatus(id))) throw new IllegalStateException("Task is not recoverable");
            Path temp = null;
            try {
                Path input = Path.of((String)r.get("input_path")).toAbsolutePath().normalize();
                if (!input.equals(inputRoot.resolve(id + ".log")) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                        || !input.toRealPath().equals(input)) throw new IOException("Stored input path is not safe");
                temp = Files.createTempFile(id + "_recovery_", ".log");
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                if (!Objects.equals(r.get("input_sha256"), hash(temp))) throw new IOException("Stored input checksum changed");
                jdbc.update("UPDATE log_analysis_task SET status='PENDING',progress=0,error_msg=NULL,current_step='等待恢复分析',update_time=? WHERE task_id=?", now(), id);
                jdbc.update("UPDATE log_task_runtime SET execution_id=NULL,instance_id=?,phase='QUEUED',runtime_status='QUEUED',"
                        + "heartbeat_at=?,lease_until=?,last_error=NULL,updated_at=? WHERE task_id=?", instance, now(), deadline(), now(), id);
                Path retry = temp;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) try { Files.deleteIfExists(retry); } catch (IOException ignored) {}
                    }
                });
                afterCommit(() -> queued.add(id));
                return temp.toFile();
            } catch (Exception e) {
                if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                throw new IllegalStateException("Cannot recover retained input", e);
            }
        });
    }

    public void recoveryRequired(String id, String reason) {
        locked(id, () -> {
            if (!currentExecution(id)) return null;
            if (jdbc.update("UPDATE log_analysis_task SET status='RECOVERY_REQUIRED',current_step='中断待恢复',error_msg=?,update_time=? "
                    + "WHERE task_id=? AND status IN ('PENDING','PROCESSING','PAUSED')", reason, now(), id) > 0) {
                jdbc.update("UPDATE log_task_runtime SET runtime_status='RECOVERY_REQUIRED',phase='INTERRUPTED',lease_until=NULL,last_error=?,updated_at=? WHERE task_id=?",
                        reason, now(), id);
                afterCommit(() -> queued.remove(id));
            }
            return null;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcileExpired(); purgeExpiredInputs(); }

    @Scheduled(fixedDelayString="${log.analysis.heartbeat-ms:10000}")
    public void renewLeases() {
        if (closing) return;
        active.forEach((id, execution) -> { if (jdbc.update("UPDATE log_task_runtime SET heartbeat_at=?,lease_until=? "
                + "WHERE task_id=? AND execution_id=? AND instance_id=? AND runtime_status='RUNNING' AND lease_until>?",
                now(), deadline(), id, execution, instance, now()) == 0) active.remove(id, execution); });
        queued.forEach(id -> { if (jdbc.update("UPDATE log_task_runtime SET heartbeat_at=?,lease_until=? "
                + "WHERE task_id=? AND instance_id=? AND runtime_status='QUEUED' AND lease_until>?", now(), deadline(), id, instance, now()) == 0) queued.remove(id); });
        reconcileExpired();
        purgeExpiredInputs();
    }

    public void reconcileExpired() {
        var ids = jdbc.queryForList("SELECT task_id FROM log_task_runtime WHERE runtime_status IN ('RUNNING','QUEUED') AND lease_until<=? ORDER BY lease_until LIMIT 100", String.class, now());
        for (String id : ids) locked(id, () -> {
            var r = row(id);
            if (r != null && r.get("lease_until") instanceof Timestamp until && !until.toInstant().isAfter(Instant.now())
                    && Set.of("RUNNING","QUEUED").contains(r.get("runtime_status"))) {
                recoveryRequired(id, "执行心跳已过期，请核对保留的日志后恢复分析");
            }
            return null;
        });
        // Legacy tasks have no retained input: make the interruption explicit, require re-upload.
        jdbc.update("UPDATE log_analysis_task SET status='RECOVERY_REQUIRED',error_msg='历史任务已中断，未保留输入，请重新上传日志',current_step='缺少恢复输入' "
                + "WHERE status IN ('PENDING','PROCESSING','PAUSED') AND COALESCE(update_time,create_time)<? "
                + "AND NOT EXISTS(SELECT 1 FROM log_task_runtime r WHERE r.task_id=log_analysis_task.task_id)", Timestamp.from(Instant.now().minus(lease)));
    }

    public boolean exportIfCompleted(String id, Runnable exporter) {
        record ExportOutcome(boolean exported, RuntimeException failure) {}
        ExportOutcome outcome = locked(id, () -> {
            if (!"COMPLETED".equals(taskStatus(id))) return new ExportOutcome(false, null);
            var runtime = row(id);
            if (runtime != null && "DELETED".equals(runtime.get("runtime_status"))) return new ExportOutcome(false, null);
            jdbc.update("UPDATE log_task_runtime SET report_status='GENERATING',updated_at=? WHERE task_id=?", now(), id);
            try {
                exporter.run();
            } catch (RuntimeException failure) {
                // Commit failure under the same row lock; a later successful export must win.
                jdbc.update("UPDATE log_task_runtime SET report_status='FAILED',last_error='Report generation failed',updated_at=? WHERE task_id=?", now(), id);
                return new ExportOutcome(false, failure);
            }
            jdbc.update("UPDATE log_task_runtime SET report_status='READY',last_error=CASE WHEN last_error='Report generation failed' THEN NULL ELSE last_error END,updated_at=? WHERE task_id=?", now(), id);
            return new ExportOutcome(true, null);
        });
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.exported();
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    public void markDeleted(String id, Runnable cleanArtifacts) {
        jdbc.update("UPDATE log_diagnosis_job SET status='CANCELLED',error_code='TASK_DELETED',finished_at=? "
                + "WHERE task_id=? AND status IN ('QUEUED','RUNNING')", now(), id);
        jdbc.update("UPDATE log_diagnosis_job SET result_text=NULL WHERE task_id=?", id);
        jdbc.update("UPDATE log_task_runtime SET runtime_status='DELETED',phase='DELETED',ai_status='CANCELLED',deleted_at=?,lease_until=NULL,last_error='Cleanup pending',updated_at=? WHERE task_id=?", now(), now(), id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    cleanupAfterDelete(id, cleanArtifacts);
                }
            });
        } else { cleanupAfterDelete(id, cleanArtifacts); }
    }

    private void cleanupAfterDelete(String id, Runnable cleanArtifacts) {
        // No JDBC in an afterCommit callback: the old connection is still bound and pool may be full.
        queued.remove(id);
        try { cleanArtifacts.run(); Files.deleteIfExists(inputRoot.resolve(id + ".log")); }
        catch (Exception e) { log.warn("Task cleanup remains pending taskId={}", id, e); }
    }

    private void removeInput(String id) {
        try {
            Files.deleteIfExists(inputRoot.resolve(id + ".log"));
            jdbc.update("UPDATE log_task_runtime SET input_path=NULL,input_sha256=NULL WHERE task_id=?", id);
        }
        catch (IOException e) { jdbc.update("UPDATE log_task_runtime SET last_error=? WHERE task_id=?", "Input cleanup pending", id); }
    }

    public void purgeExpiredInputs() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofHours(Math.max(1, inputRetentionHours))));
        var ids = jdbc.queryForList("SELECT task_id FROM log_task_runtime WHERE "
                + "(runtime_status='DELETED' AND (input_path IS NOT NULL OR last_error IS NOT NULL)) OR (input_path IS NOT NULL "
                + "AND runtime_status IN ('COMPLETED','FAILED','CANCELLED') AND updated_at<?) ORDER BY updated_at LIMIT 100", String.class, cutoff);
        for (String id : ids) locked(id, () -> {
            var r = row(id);
            if (r != null && ("DELETED".equals(r.get("runtime_status"))
                    || (Set.of("COMPLETED","FAILED","CANCELLED").contains(r.get("runtime_status"))
                    && r.get("updated_at") instanceof Timestamp updated && updated.before(cutoff)))) {
                if ("DELETED".equals(r.get("runtime_status")) && artifactCleaner != null) {
                    artifactCleaner.purgeTaskArtifacts(id);
                    if (Files.exists(artifactCleaner.outputDirForTask(id))) {
                        jdbc.update("UPDATE log_task_runtime SET updated_at=? WHERE task_id=?", now(), id);
                        return null;
                    }
                }
                removeInput(id);
                if ("DELETED".equals(r.get("runtime_status")) && !Files.exists(inputRoot.resolve(id + ".log"))) {
                    jdbc.update("UPDATE log_task_runtime SET last_error=NULL WHERE task_id=?", id);
                }
            }
            return null;
        });
    }

    @PreDestroy public void close() {
        closing = true;
        Set<String> ids = new HashSet<>(active.keySet()); ids.addAll(queued);
        ids.forEach(id -> locked(id, () -> {
            var r = row(id);
            if (r != null && instance.equals(r.get("instance_id"))) {
                recoveryRequired(id, "应用已停止，可核对保留的日志后恢复分析");
            }
            return null;
        }));
        active.clear(); queued.clear();
    }

    private static String hash(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] data = new byte[64*1024]; int n;
            while ((n=in.read(data))!=-1) digest.update(data,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
