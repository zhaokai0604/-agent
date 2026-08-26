package com.award.log.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailReaderTest {

    @Test
    void readTailLinesReturnsEmptyForInvalidInput(@TempDir Path tempDir) throws Exception {
        assertTrue(LogTailReader.readTailLines(tempDir.resolve("missing.log"), 5, 1024).isEmpty());
        assertTrue(LogTailReader.readTailLines(tempDir, 5, 1024).isEmpty());
        assertTrue(LogTailReader.readTailLines(tempDir.resolve("empty.log"), 5, 1024).isEmpty());
        assertTrue(LogTailReader.readTailLines(tempDir.resolve("empty.log"), 0, 1024).isEmpty());
    }

    @Test
    void readTailLinesReturnsLastLines(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("app.log");
        Files.writeString(log, "line1\nline2\nline3\nline4\nline5\n");

        List<String> tail = LogTailReader.readTailLines(log, 3, 64 * 1024);
        assertEquals(3, tail.size());
        assertEquals("line4", tail.get(0));
        assertEquals("line5", tail.get(1));
        assertEquals("", tail.get(2));
    }

    @Test
    void readTailLinesHandlesLargeFileWithSmallBudget(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("big.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            sb.append("entry-").append(i).append('\n');
        }
        Files.writeString(log, sb.toString());

        List<String> tail = LogTailReader.readTailLines(log, 5, 128);
        assertEquals(5, tail.size());
        assertEquals("entry-197", tail.get(0));
        assertEquals("entry-200", tail.get(3));
        assertEquals("", tail.get(4));
    }

    @Test
    void readTailLinesDecodesUtf8(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("utf8.log");
        Files.writeString(log, "ascii\n中文行\n");

        List<String> tail = LogTailReader.readTailLines(log, 2, 4096);
        assertEquals(2, tail.size());
        assertTrue(tail.stream().anyMatch(s -> s.contains("中文")));
    }
}
