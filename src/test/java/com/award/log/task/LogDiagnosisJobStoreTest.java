package com.award.log.task;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class LogDiagnosisFixture {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    DurableLogTaskStore tasks;
    LogDiagnosisJobStore jobs;
    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        tasks = new DurableLogTaskStore(jdbc,new DataSourceTransactionManager(ds),dir.toString(),90);
        jobs = new LogDiagnosisJobStore(jdbc,tasks,3,120);
        jdbc.update("INSERT INTO log_analysis_task(task_id,file_name,status,ai_diagnosis) VALUES('test','sample.log','COMPLETED','old untracked text')");
        jdbc.update("INSERT INTO log_task_runtime(task_id,phase,runtime_status,report_status) VALUES('test','COMPLETED','COMPLETED','READY')");
    }
}

class LogDiagnosisJobStoreTest extends LogDiagnosisFixture {
    @Test void requestIdempotencyActiveDedupAndAttemptBudgetAreDurable() {
        var first = jobs.begin("test","request1");
        assertEquals(first.runId(),jobs.begin("test","request1").runId());
        assertEquals(first.runId(),jobs.begin("test","request2").runId());
        assertTrue(jobs.claim(first)); assertFalse(jobs.claim(first));
        assertTrue(jobs.finish(first,"FAILED","MODEL_CALL_FAILED",null));
        assertEquals(first.runId(),jobs.begin("test","request1").runId());
        var second = jobs.begin("test","request2");
        assertEquals(2,second.attempt()); jobs.finish(second,"FAILED","MODEL_CALL_FAILED",null);
        var third = jobs.begin("test","request3");
        jobs.finish(third,"CANCELLED","USER_CANCELLED",null);
        assertThrows(IllegalStateException.class,()->jobs.begin("test","request4"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM log_diagnosis_job",Integer.class));
    }
    @Test void cancelledAndSupersededOutputCannotReplaceNewSuccess() {
        var first = jobs.begin("test","first"); jobs.claim(first);
        jobs.cancel("test",first.runId());
        var second = jobs.begin("test","second"); jobs.claim(second);
        assertThrows(IllegalStateException.class,()->jobs.cancel("test",first.runId()));
        assertFalse(jobs.finish(first,"SUCCEEDED",null,"late text"));
        assertTrue(jobs.finish(second,"SUCCEEDED",null,"new verified transport result"));
        assertEquals("new verified transport result",jobs.status("test").diagnosis());
        assertEquals("SUCCEEDED",tasks.snapshot("test").aiStatus());
        assertEquals("READY",tasks.snapshot("test").reportStatus());
        assertEquals("COMPLETED",tasks.snapshot("test").status());
    }
    @Test void requestReplayKeepsItsOriginalResultAfterAnotherRunSucceeds() {
        var first=jobs.begin("test","first");jobs.claim(first);jobs.finish(first,"SUCCEEDED",null,"first result");
        var second=jobs.begin("test","second");jobs.claim(second);jobs.finish(second,"SUCCEEDED",null,"second result");
        assertEquals("first result",jobs.begin("test","first").diagnosis());
        assertEquals("first result",jobs.attemptStatus(first).diagnosis());
        assertEquals("second result",jobs.status("test").diagnosis());
    }
    @Test void expiredAttemptCannotCommitAndCanBeRetriedExplicitly() {
        var job = jobs.begin("test","expired"); jobs.claim(job);
        jdbc.update("UPDATE log_diagnosis_job SET deadline_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        assertFalse(jobs.finish(job,"SUCCEEDED",null,"too late"));
        jobs.reconcileExpired();
        assertEquals("TIMED_OUT",jobs.status("test").status());
        assertEquals("DEADLINE_EXCEEDED",jobs.status("test").errorCode());
        assertNull(jobs.status("test").diagnosis());
        assertEquals(2,jobs.begin("test","retry").attempt());
    }
    @Test void deleteCancelsAttemptAndLateReplyCannotResurrectTask() {
        var job = jobs.begin("test","deleted"); jobs.claim(job);
        tasks.locked("test",()-> {
            jdbc.update("DELETE FROM log_analysis_task WHERE task_id='test'");
            tasks.markDeleted("test",()->{}); return null;
        });
        assertFalse(jobs.finish(job,"SUCCEEDED",null,"late"));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM log_diagnosis_job",String.class));
        assertEquals("TASK_DELETED",jdbc.queryForObject("SELECT error_code FROM log_diagnosis_job",String.class));
        assertThrows(NoSuchElementException.class,()->jobs.status("test"));
    }
    @Test void deletingTaskAlsoClearsPersistedSuccessfulAttemptText() {
        var job=jobs.begin("test","success");jobs.claim(job);jobs.finish(job,"SUCCEEDED",null,"private diagnosis");
        tasks.locked("test",()->{jdbc.update("DELETE FROM log_analysis_task WHERE task_id='test'");tasks.markDeleted("test",()->{});return null;});
        assertNull(jdbc.queryForObject("SELECT result_text FROM log_diagnosis_job",String.class));
        assertEquals("CANCELLED",tasks.snapshot("test").aiStatus());
    }
    @Test @Timeout(10) void simultaneousStartsUseOneAttemptAcrossInstances() throws Exception {
        var another = new LogDiagnosisJobStore(jdbc,tasks,3,120);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first=pool.submit(()->{gate.await();return jobs.begin("test","one");});
            var second=pool.submit(()->{gate.await();return another.begin("test","two");});
            gate.countDown();
            assertEquals(first.get().runId(),second.get().runId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM log_diagnosis_job",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidOrUnfinishedTasksCannotCallModelAndOldTextIsUntracked() {
        assertEquals("UNTRACKED",jobs.status("test").status());
        assertNull(jobs.status("test").diagnosis());
        assertThrows(IllegalArgumentException.class,()->jobs.begin("test",null));
        jdbc.update("UPDATE log_analysis_task SET status='PROCESSING'");
        assertThrows(IllegalStateException.class,()->jobs.begin("test","request"));
        assertThrows(NoSuchElementException.class,()->jobs.begin("missing","request"));
    }
}
