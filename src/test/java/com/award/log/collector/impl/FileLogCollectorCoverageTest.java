package com.award.log.collector.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLogCollectorCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    void singleFilePollingStartsAtEofAndReadsAppendedLines() throws Exception {
        Path log = tempDir.resolve("application.log");
        Files.writeString(log, "old-line\n", StandardCharsets.UTF_8);
        FileLogCollector collector = new FileLogCollector(log.toString(), "single", ".log", "", 2, 1000);

        try {
            collector.start();
            collector.start();
            waitForInitialPosition(collector, log);

            Files.writeString(log, "new-line-1\nnew-line-2\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            List<String> lines = waitForLines(collector, 2);

            assertEquals(List.of("new-line-1", "new-line-2"), lines);
            assertEquals("single", collector.getName());
            assertTrue(collector.isRunning());
        } finally {
            collector.stop();
            collector.stop();
        }
        assertFalse(collector.isRunning());
    }

    @Test
    void directoryCollectionReadsAllowedExtensionsAndSkipsExcludedDirectories() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path excluded = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("root.log"), "root\n");
        Files.writeString(nested.resolve("child.txt"), "child\n");
        Files.writeString(tempDir.resolve("ignore.md"), "ignore\n");
        Files.writeString(excluded.resolve("compiled.log"), "excluded\n");

        FileLogCollector collector = new FileLogCollector(
                tempDir.toString(), "directory", ".log,.txt", "target", 4, 1000, 100, false);
        List<String> lines;
        try {
            collector.start();
            lines = waitForLines(collector, 2);
        } finally {
            collector.stop();
        }

        assertTrue(lines.contains("root"));
        assertTrue(lines.contains("child"));
        assertFalse(lines.contains("ignore"));
        assertFalse(lines.contains("excluded"));
    }

    @Test
    void eofFalseReadsExistingFileAndTruncationResetsPosition() throws Exception {
        Path log = tempDir.resolve("rotate.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        FileLogCollector collector = new FileLogCollector(log.toString(), "rotate", ".log", "", 2, 1000,
                100, false);

        ReflectionTestUtils.invokeMethod(collector, "collectFromFile", log);
        assertEquals(List.of("before"), collector.collect());

        Files.writeString(log, "x\n", StandardCharsets.UTF_8);
        ReflectionTestUtils.invokeMethod(collector, "collectFromFile", log);
        assertEquals(List.of("x"), collector.collect());
    }

    @Test
    void missingRootCanBeStoppedWithoutLeakingRunningState() throws Exception {
        FileLogCollector collector = new FileLogCollector(tempDir.resolve("missing.log").toString(), "missing");
        collector.start();
        Thread.sleep(100);
        assertTrue(collector.isRunning());
        collector.stop();
        assertFalse(collector.isRunning());
        assertTrue(collector.collect().isEmpty());
    }

    @Test
    void boundedBufferRefusesNewLinesUntilDrained() {
        FileLogCollector collector = new FileLogCollector(tempDir.toString(), "bounded", ".log", "", 2, 1000,
                100, true);
        for (int i = 0; i < 100; i++) {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(collector, "appendBufferedLine", "line-" + i));
        }
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(collector, "appendBufferedLine", "overflow"));
        assertEquals(100, collector.collect().size());
        assertTrue(collector.collect().isEmpty());
    }

    @Test
    void extensionAndDirectoryPolicyHelpersAreCaseInsensitive() {
        FileLogCollector collector = new FileLogCollector(tempDir.toString(), "policy", ".LOG,.TXT", "Target");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(collector, "isLogFile", tempDir.resolve("A.LoG")));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(collector, "isLogFile", tempDir.resolve("A.csv")));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(collector, "isAllowedDirectory", tempDir.resolve("TARGET")));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(collector, "isAllowedDirectory", tempDir.resolve("logs")));
    }

    private static List<String> waitForLines(FileLogCollector collector, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<String> lines = collector.collect();
            if (lines.size() >= expected) {
                return lines;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + expected + " log lines");
    }

    @SuppressWarnings("unchecked")
    private static void waitForInitialPosition(FileLogCollector collector, Path log) throws Exception {
        Map<String, Long> positions = (Map<String, Long>) ReflectionTestUtils
                .getField(collector, "fileReadPositions");
        String key = log.toAbsolutePath().toString();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (positions.containsKey(key)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for initial EOF position");
    }

}
