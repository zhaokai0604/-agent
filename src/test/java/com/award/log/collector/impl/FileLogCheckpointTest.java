package com.award.log.collector.impl;

import com.award.log.collector.CollectedLogBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileLogCheckpointTest {
    @TempDir Path dir;
    Path checkpoint() { return dir.resolve("checkpoints"); }
    FileLogCollector collector(Path source, boolean eof) {
        return new FileLogCollector(source.toString(), "test", ".log", "", 3, 1000, 100, eof, checkpoint());
    }
    void read(FileLogCollector collector, Path source) {
        ReflectionTestUtils.invokeMethod(collector, "collectFromFile", source);
    }
    Path savedCursor() throws Exception {
        try (var files = Files.walk(checkpoint())) {
            return files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
    }

    @Test void acknowledgedCompleteLinesResumeButPartialUtf8IsReread() throws Exception {
        Path source = Files.writeString(dir.resolve("app.log"), "已确认\r\n半行");
        var first = collector(source, false);
        read(first, source);
        var batch = first.collectBatch();
        assertEquals(List.of("已确认"), batch.lines());
        batch.acknowledge().run(); first.stop();
        Files.writeString(source, "续写\n", StandardOpenOption.APPEND);
        var second = collector(source, true);
        try {
            read(second, source);
            assertEquals(List.of("半行续写"), second.collect());
            assertTrue((Boolean)second.diagnostics().get("checkpointEnabled"));
        } finally { second.stop(); }
    }

    @Test void drainingAndReadingDoNotAcknowledgeUnconfirmedData() throws Exception {
        Path source = Files.writeString(dir.resolve("unacked.log"), "待交付\n");
        var first = collector(source, false);
        read(first, source); assertEquals(List.of("待交付"), first.collect()); first.stop();
        var second = collector(source, true);
        try { read(second, source); assertEquals(List.of("待交付"), second.collect()); }
        finally { second.stop(); }
    }

    @Test void acknowledgementIsBoundToDrainedBatchNotLaterReads() throws Exception {
        Path source = Files.writeString(dir.resolve("batch.log"), "第一批\n");
        var first = collector(source, false);
        read(first, source); var batch = first.collectBatch();
        Files.writeString(source, "第二批\n", StandardOpenOption.APPEND);
        read(first, source); batch.acknowledge().run(); first.stop();
        var second = collector(source, false);
        try { read(second, source); assertEquals(List.of("第二批"), second.collect()); }
        finally { second.stop(); }
    }

    @Test void backpressureDoesNotCheckpointUnpublishedLine() throws Exception {
        Path source = Files.writeString(dir.resolve("pressure.log"), "line\n".repeat(103));
        var first = collector(source, false);
        read(first, source); var batch = first.collectBatch();
        assertEquals(100, batch.lines().size()); batch.acknowledge().run(); first.stop();
        var second = collector(source, true);
        try { read(second, source); assertEquals(3, second.collect().size()); }
        finally { second.stop(); }
    }

    @Test void crAtCheckpointDoesNotPublishAnExtraEmptyLineAfterRestart() throws Exception {
        Path source = Files.writeString(dir.resolve("cr.log"), "first\r");
        var first = collector(source, false);
        read(first, source); first.collectBatch().acknowledge().run(); first.stop();
        Files.writeString(source, "\nsecond\r\n", StandardOpenOption.APPEND);
        var second = collector(source, true);
        try { read(second, source); assertEquals(List.of("second"), second.collect()); }
        finally { second.stop(); }
    }

    @Test void fileReplacementAndTruncationInvalidateSavedCursor() throws Exception {
        Path source = Files.writeString(dir.resolve("rotate.log"), "old contents\n");
        var first = collector(source, false);
        read(first, source); first.collectBatch().acknowledge().run(); first.stop();
        Files.move(source, dir.resolve("old.data"));
        Files.writeString(source, "replacement longer than original contents\n");
        var second = collector(source, true);
        try {
            read(second, source); assertEquals(List.of("replacement longer than original contents"), second.collect());
            Files.writeString(source, "x\n"); read(second, source); assertEquals(List.of("x"), second.collect());
        } finally { second.stop(); }
    }

    @Test void corruptCheckpointStopsStartupInsteadOfSilentlySeedingEof() throws Exception {
        Path source = Files.writeString(dir.resolve("bad.log"), "evidence\n");
        var first = collector(source, false); read(first, source); first.stop();
        Files.writeString(savedCursor(), "not json");
        var second = collector(source, true);
        try {
            assertThrows(IllegalStateException.class, second::start);
            assertFalse(second.isRunning());
            assertEquals("FILE_CHECKPOINT_INVALID", second.diagnostics().get("checkpointError"));
        } finally { second.stop(); }
    }

    @Test void sameRootCannotHaveTwoCheckpointOwners() throws Exception {
        Path source = Files.writeString(dir.resolve("locked.log"), "data\n");
        var first = collector(source, false);
        try { assertThrows(IllegalStateException.class, () -> collector(source, false)); }
        finally { first.stop(); }
        var second = collector(source, false); second.stop(); second.stop();
    }

    @Test void firstEofSeedSurvivesRestartWithoutSkippingUnacknowledgedAppend() throws Exception {
        Path source = Files.writeString(dir.resolve("eof.log"), "old\r");
        var first = collector(source, true);
        first.start(); first.stop();
        Files.writeString(source, "\nnew\n", StandardOpenOption.APPEND);
        var second = collector(source, true);
        try { read(second, source); assertEquals(List.of("new"), second.collect()); }
        finally { second.stop(); }
    }

    @Test void directoryIdentityIsStableAcrossCollectorNamesAndDirectoryRestart() throws Exception {
        Path root = Files.createDirectory(dir.resolve("logs"));
        Path source = Files.writeString(root.resolve("a.log"), "first\n");
        var first = collector(root, false); read(first, source);
        first.collectBatch().acknowledge().run(); first.stop();
        Files.writeString(source, "after restart\n", StandardOpenOption.APPEND);
        var second = new FileLogCollector(root.toString(), "renamed", ".log", "", 3, 1000, 100, true, checkpoint());
        try {
            second.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            var actual = new java.util.ArrayList<String>();
            while (actual.isEmpty() && System.nanoTime() < deadline) { actual.addAll(second.collect()); Thread.sleep(20); }
            assertEquals(List.of("after restart"), actual);
        } finally { second.stop(); }
    }

    @Test void checkpointContainsOffsetsAndHashesButNoLogBodyAndRejectsInvalidOffset() throws Exception {
        Path source = Files.writeString(dir.resolve("private.log"), "private log content\n");
        var first = collector(source, false); read(first, source); first.collectBatch().acknowledge().run(); first.stop();
        Path saved = savedCursor(); String json = Files.readString(saved);
        assertFalse(json.contains("private log content"));
        Files.writeString(saved, json.replaceFirst("\"position\":\\d+", "\"position\":-1"));
        var second = collector(source, false);
        try { assertThrows(IllegalStateException.class, () -> read(second, source)); }
        finally { second.stop(); }
    }

    @Test void failedCheckpointWriteDoesNotPretendAcknowledgementSucceeded() throws Exception {
        Path source = Files.writeString(dir.resolve("closed.log"), "first\n");
        var first = collector(source, false); read(first, source);
        CollectedLogBatch batch = first.collectBatch(); first.stop();
        assertThrows(IllegalStateException.class, batch.acknowledge()::run);
        assertEquals("FILE_CHECKPOINT_WRITE_FAILED", first.diagnostics().get("checkpointError"));
        var second = collector(source, false);
        try { read(second, source); assertEquals(List.of("first"), second.collect()); }
        finally { second.stop(); }
    }

    @Test void abruptJvmTerminationReplaysBufferedDataAndReleasesOwnership() throws Exception {
        Path source = Files.writeString(dir.resolve("process.log"), "已确认\n");
        Path output = dir.resolve("worker-output.txt");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(executable))) executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(executable, "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CrashWorker.class.getName(), source.toString(), checkpoint().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            awaitMarker(process, output, "COMMITTED");
            Files.writeString(source, "尚未交付\n", StandardOpenOption.APPEND);
            awaitMarker(process, output, "BUFFERED");
            process.destroyForcibly();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            // Windows may still be releasing native handles immediately after waitFor returns.
            FileLogCollector restarted = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (restarted == null) {
                try { restarted = collector(source, true); }
                catch (IllegalStateException releasePending) {
                    if (System.nanoTime() >= deadline) throw releasePending;
                    Thread.sleep(25);
                }
            }
            try { read(restarted, source); assertEquals(List.of("尚未交付"), restarted.collect()); }
            finally { restarted.stop(); }
        } finally {
            process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void awaitMarker(Process process, Path output, String marker) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (Files.exists(output) && Files.readString(output).contains(marker)) return;
            Thread.sleep(25);
        }
        fail("Child collector did not reach " + marker + ": " + Files.readString(output));
    }

    public static class CrashWorker {
        public static void main(String[] args) throws Exception {
            var source = Path.of(args[0]);
            var collector = new FileLogCollector(source.toString(), "child", ".log", "", 2, 1000, 100,
                    false, Path.of(args[1]));
            collector.start();
            while (((Number)collector.diagnostics().get("bufferedLines")).intValue() == 0) Thread.sleep(10);
            collector.collectBatch().acknowledge().run();
            System.out.println("COMMITTED"); System.out.flush();
            while (((Number)collector.diagnostics().get("bufferedLines")).intValue() == 0) Thread.sleep(10);
            System.out.println("BUFFERED"); System.out.flush();
            Thread.sleep(60_000);
            collector.stop();
        }
    }
}
