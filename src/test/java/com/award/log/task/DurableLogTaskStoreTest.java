package com.award.log.task;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DurableLogTaskStoreTest {
    @TempDir Path dir;
    JdbcDataSource ds;
    JdbcTemplate jdbc;
    DurableLogTaskStore first;

    @BeforeEach void init() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        first = newStore();
    }

    DurableLogTaskStore newStore() {
        return new DurableLogTaskStore(jdbc, new DataSourceTransactionManager(ds), dir.resolve("inputs").toString(), 30);
    }
    Path stage(String id) throws Exception {
        jdbc.update("INSERT INTO log_analysis_task(task_id,status,create_time,update_time) VALUES(?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id);
        Path upload = Files.writeString(dir.resolve(id+".upload"), "INFO durable input\n");
        first.stage(id,upload.toFile());
        return upload;
    }

    @Test void retainedInputSurvivesExpiredWorkerAndFencesOldExecution() throws Exception {
        Path upload=stage("recover");
        String old=first.claim("recover");
        assertNotNull(old);
        assertNull(first.claim("recover"));
        Files.delete(upload);
        jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id='recover'");
        jdbc.update("UPDATE log_task_runtime SET lease_until=? WHERE task_id='recover'", Timestamp.from(Instant.now().minusSeconds(1)));
        var restarted=newStore();
        restarted.reconcileExpired();
        assertEquals("RECOVERY_REQUIRED",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='recover'",String.class));
        assertTrue(restarted.needsRecovery("recover"));
        Path retry=restarted.prepareRecovery("recover").toPath();
        try {
            assertEquals("INFO durable input\n",Files.readString(retry));
            String fresh=restarted.claim("recover");
            assertNotNull(fresh); assertNotEquals(old,fresh);
            assertEquals(2,restarted.snapshot("recover").attempt());
            try(var ignored=TaskExecutionContext.open("recover",old)) {
                assertFalse(restarted.locked("recover",()->restarted.currentExecution("recover")));
                first.recoveryRequired("recover","stale worker interrupted");
            }
            first.close();
            assertEquals("RUNNING",restarted.snapshot("recover").status());
            try(var ignored=TaskExecutionContext.open("recover",fresh)) {
                assertTrue(restarted.locked("recover",()->restarted.currentExecution("recover")));
                Callable<Boolean> shard=TaskExecutionContext.wrap(()->restarted.locked("recover",()->restarted.currentExecution("recover")));
                var worker=Executors.newSingleThreadExecutor();
                try {assertTrue(worker.submit(shard).get(2,TimeUnit.SECONDS));} finally {worker.shutdownNow();}
            }
        } finally {Files.deleteIfExists(retry);}
    }

    @Test void recoveryRejectsChangedInputAndDoesNotResetTask() throws Exception {
        stage("tamper"); first.claim("tamper"); first.recoveryRequired("tamper","interrupted");
        Files.writeString(dir.resolve("inputs/tamper.log"),"changed");
        assertThrows(IllegalStateException.class,()->newStore().prepareRecovery("tamper"));
        assertEquals("RECOVERY_REQUIRED",first.snapshot("tamper").status());
        assertEquals("RECOVERY_REQUIRED",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='tamper'",String.class));
    }

    @Test @Timeout(10) void deleteWaitsForExportAndLateExportCannotResurrectFiles() throws Exception {
        stage("report");
        jdbc.update("UPDATE log_analysis_task SET status='COMPLETED' WHERE task_id='report'");
        first.finish("report","COMPLETED");
        var second=newStore();
        Path artifact=dir.resolve("report.html");
        CountDownLatch writing=new CountDownLatch(1),finish=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> export=pool.submit(()->first.exportIfCompleted("report",()->{
                writing.countDown();
                try {assertTrue(finish.await(3,TimeUnit.SECONDS));Files.writeString(artifact,"complete report");}
                catch(Exception e){throw new RuntimeException(e);}
            }));
            assertTrue(writing.await(2,TimeUnit.SECONDS));
            var deletion=pool.submit(()->second.locked("report",()->{
                jdbc.update("DELETE FROM log_analysis_task WHERE task_id='report'");
                second.markDeleted("report",()->{try{Files.deleteIfExists(artifact);}catch(Exception e){throw new RuntimeException(e);}});
                return true;
            }));
            assertThrows(TimeoutException.class,()->deletion.get(100,TimeUnit.MILLISECONDS));
            finish.countDown(); assertTrue(export.get(3,TimeUnit.SECONDS)); assertTrue(deletion.get(3,TimeUnit.SECONDS));
            assertFalse(Files.exists(artifact));
            assertFalse(Files.exists(dir.resolve("inputs/report.log")));
            assertEquals("DELETED",first.snapshot("report").status());
            assertFalse(first.exportIfCompleted("report",()->fail("Late export ran")));
        } finally {finish.countDown();pool.shutdownNow();}
    }

    @Test void rolledBackDeletionKeepsDatabaseInputAndReport() throws Exception {
        stage("rollback"); AtomicInteger cleanups=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->first.locked("rollback",()->{
            jdbc.update("DELETE FROM log_analysis_task WHERE task_id='rollback'");
            first.markDeleted("rollback",cleanups::incrementAndGet);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0,cleanups.get());
        assertTrue(Files.exists(dir.resolve("inputs/rollback.log")));
        assertEquals("QUEUED",first.snapshot("rollback").status());
    }

    @Test void heartbeatCannotResurrectExpiredLeaseAndOtherInstanceCannotStealLiveTask() throws Exception {
        stage("live"); String execution=first.claim("live");
        jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id='live'");
        var second=newStore(); second.reconcileExpired();
        assertFalse(second.needsRecovery("live")); assertNull(second.claim("live"));
        first.renewLeases();
        jdbc.update("UPDATE log_task_runtime SET lease_until=? WHERE task_id='live'",Timestamp.from(Instant.now().minusSeconds(1)));
        first.renewLeases();
        assertTrue(first.needsRecovery("live"));
        try(var ignored=TaskExecutionContext.open("live",execution)) {
            assertFalse(first.locked("live",()->first.currentExecution("live")));
        }
    }

    @Test void shutdownAndLegacyTasksHaveExplicitRecoveryState() throws Exception {
        stage("shutdown"); first.claim("shutdown"); first.close();
        assertTrue(newStore().needsRecovery("shutdown")); assertNull(first.claim("shutdown"));
        jdbc.update("INSERT INTO log_analysis_task(task_id,status,create_time,update_time) VALUES('legacy','PAUSED',?,?)",
                Timestamp.from(Instant.now().minusSeconds(120)),Timestamp.from(Instant.now().minusSeconds(120)));
        newStore().reconcileExpired();
        assertEquals("RECOVERY_REQUIRED",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='legacy'",String.class));
        assertFalse(newStore().needsRecovery("legacy"));
        assertThrows(IllegalArgumentException.class,()->first.locked("../escape",()->true));
    }

    @Test void terminalInputRetentionAndReportFailureAreRecorded() throws Exception {
        stage("retention");
        jdbc.update("UPDATE log_analysis_task SET status='COMPLETED' WHERE task_id='retention'");
        first.finish("retention","COMPLETED");
        assertThrows(IllegalStateException.class,()->first.exportIfCompleted("retention",()->{throw new IllegalStateException("disk full");}));
        assertEquals("FAILED",first.snapshot("retention").reportStatus());
        assertTrue(Files.exists(dir.resolve("inputs/retention.log")));
        jdbc.update("UPDATE log_task_runtime SET updated_at=? WHERE task_id='retention'",Timestamp.from(Instant.now().minus(Duration.ofHours(25))));
        first.purgeExpiredInputs();
        assertFalse(Files.exists(dir.resolve("inputs/retention.log")));
        stage("recent");
        first.reconcileExpired();
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='recent'",String.class));
    }

    @Test void queuedPauseAndResumeRemainClaimableOnlyByTheirOwningInstance() throws Exception {
        stage("paused"); assertNull(newStore().claim("paused"));
        jdbc.update("UPDATE log_analysis_task SET status='PAUSED' WHERE task_id='paused'");
        assertNotNull(first.claim("paused"));
        stage("resumed"); jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id='resumed'");
        assertNotNull(first.claim("resumed"));
    }

    @Test void failedReportCanBeRegeneratedByAnotherInstanceWithoutStaleError() throws Exception {
        stage("report-retry");
        jdbc.update("UPDATE log_analysis_task SET status='COMPLETED' WHERE task_id='report-retry'");
        first.finish("report-retry","COMPLETED");
        assertThrows(IllegalStateException.class,()->first.exportIfCompleted("report-retry",()->{
            throw new IllegalStateException("disk full");
        }));
        var restarted=newStore();
        assertEquals("FAILED",restarted.snapshot("report-retry").reportStatus());
        assertNotNull(restarted.snapshot("report-retry").error());
        assertTrue(restarted.exportIfCompleted("report-retry",()->{}));
        assertEquals("READY",restarted.snapshot("report-retry").reportStatus());
        assertNull(restarted.snapshot("report-retry").error());
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='report-retry'",String.class));
    }

    @Test @Timeout(10) void concurrentSuccessfulReportRetryWinsOverEarlierFailure() throws Exception {
        stage("report-race");
        jdbc.update("UPDATE log_analysis_task SET status='COMPLETED' WHERE task_id='report-race'");
        first.finish("report-race","COMPLETED");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> failed=pool.submit(()->first.exportIfCompleted("report-race",()->{
                entered.countDown();
                try { assertTrue(release.await(3,TimeUnit.SECONDS)); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                throw new IllegalStateException("first attempt failed");
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<Boolean> retry=pool.submit(()->newStore().exportIfCompleted("report-race",()->{}));
            assertThrows(TimeoutException.class,()->retry.get(100,TimeUnit.MILLISECONDS));
            release.countDown();
            assertThrows(ExecutionException.class,()->failed.get(3,TimeUnit.SECONDS));
            assertTrue(retry.get(3,TimeUnit.SECONDS));
            assertEquals("READY",first.snapshot("report-race").reportStatus());
            assertNull(first.snapshot("report-race").error());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void duplicateInputMissingBusinessTaskAndInvalidHeartbeatFailExplicitly() throws Exception {
        Path source=stage("guarded");
        assertEquals("NOT_REQUESTED",first.snapshot("guarded").aiStatus());
        assertThrows(IllegalStateException.class,()->first.stage("guarded",source.toFile()));
        jdbc.update("DELETE FROM log_analysis_task WHERE task_id='guarded'");
        assertNull(first.claim("guarded"));
        org.springframework.test.util.ReflectionTestUtils.setField(first,"heartbeatMillis",20_000L);
        assertThrows(IllegalArgumentException.class,first::validateSettings);
        org.springframework.test.util.ReflectionTestUtils.setField(first,"heartbeatMillis",10_000L);
        first.validateSettings();
    }

    @Test void uploadIdempotencyIsScopedToOwnerAndRejectsChangedContent() throws Exception {
        Path upload=stage("upload-original");
        var firstReservation=first.reserveUpload(7,"request-1",upload.toFile(),"upload-original");
        assertTrue(firstReservation.created());
        var replay=first.reserveUpload(7,"request-1",upload.toFile(),"upload-retry");
        assertFalse(replay.created());assertEquals("upload-original",replay.taskId());

        Path changed=Files.writeString(dir.resolve("changed.upload"),"different input\n");
        assertThrows(IllegalArgumentException.class,()->first.reserveUpload(7,"request-1",changed.toFile(),"upload-changed"));
        var otherOwner=first.reserveUpload(8,"request-1",upload.toFile(),"other-owner-task");
        assertTrue(otherOwner.created());assertEquals("other-owner-task",otherOwner.taskId());
    }

    @Test void orphanedUploadReservationCanBeReclaimedAfterInterruptedCreation() throws Exception {
        Path upload=Files.writeString(dir.resolve("orphan.upload"),"retryable input\n");
        assertTrue(first.reserveUpload(7,"orphan-key",upload.toFile(),"missing-task").created());
        jdbc.update("UPDATE log_upload_request SET created_at=? WHERE user_id=7 AND request_key='orphan-key'",
                Timestamp.from(Instant.now().minusSeconds(120)));
        var reclaimed=first.reserveUpload(7,"orphan-key",upload.toFile(),"reclaimed-task");
        assertTrue(reclaimed.created());assertEquals("reclaimed-task",reclaimed.taskId());
    }

    @Test @Timeout(10) void concurrentUploadRetriesShareOnePersistentReservation() throws Exception {
        Path upload=Files.writeString(dir.resolve("concurrent.upload"),"same upload body\n");
        var second=newStore();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Future<DurableLogTaskStore.UploadReservation> a=pool.submit(()->{gate.await();return first.reserveUpload(7,"parallel-key",upload.toFile(),"candidate-a");});
            Future<DurableLogTaskStore.UploadReservation> b=pool.submit(()->{gate.await();return second.reserveUpload(7,"parallel-key",upload.toFile(),"candidate-b");});
            gate.countDown();var left=a.get(5,TimeUnit.SECONDS);var right=b.get(5,TimeUnit.SECONDS);
            assertEquals(left.taskId(),right.taskId());assertEquals(1,(left.created()?1:0)+(right.created()?1:0));
        } finally {gate.countDown();pool.shutdownNow();}
    }
}
