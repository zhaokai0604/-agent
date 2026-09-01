package com.award.log.service;

import com.award.log.config.OpsPepProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsRecycleBinServiceTest {

    @TempDir
    Path tempDir;

    private Path recycleRoot;
    private OpsRecycleBinService service;

    @BeforeEach
    void setUp() {
        recycleRoot = tempDir.resolve("recycle");
        OpsPepProperties pep = new OpsPepProperties();
        pep.setRecycleTtlDays(7);
        service = new OpsRecycleBinService(pep, recycleRoot.toString());
    }

    @Test
    void moveRestoreListAndPurge() throws Exception {
        Path sourceDir = tempDir.resolve("data");
        Files.createDirectories(sourceDir);
        Path source = sourceDir.resolve("file.txt");
        Files.writeString(source, "payload");

        String handle = service.moveToRecycleBin(source, "trace-1");
        assertFalse(Files.exists(source));

        List<Map<String, Object>> entries = service.listEntries();
        assertEquals(1, entries.size());
        assertEquals(handle, entries.get(0).get("handle"));
        assertEquals(source.toString(), entries.get(0).get("originalPath"));

        assertTrue(service.restore(handle));
        assertTrue(Files.exists(source));
        assertEquals("payload", Files.readString(source));
        assertTrue(service.listEntries().isEmpty());
    }

    @Test
    void restoreReturnsFalseForMissingHandle() throws Exception {
        assertFalse(service.restore("missing-handle"));
    }

    @Test
    void restoreRejectsPathTraversalHandle() throws Exception {
        assertFalse(service.restore("../outside"));
    }

    @Test
    void moveThrowsWhenSourceMissing() {
        Path missing = tempDir.resolve("nope.txt");
        assertThrows(IOException.class, () -> service.moveToRecycleBin(missing, "t"));
    }

    @Test
    void purgeExpiredRemovesOldEntries() throws Exception {
        Path source = tempDir.resolve("old.txt");
        Files.writeString(source, "old");

        String handle = service.moveToRecycleBin(source, "trace-old");
        Path meta = recycleRoot.resolve(handle).resolve("meta.json");
        String content = Files.readString(meta);
        String expired = content.replaceFirst("\"expiresAtMs\":[0-9]+", "\"expiresAtMs\":1");
        Files.writeString(meta, expired);

        service.purgeExpired();
        assertTrue(service.listEntries().isEmpty());
        assertFalse(Files.exists(recycleRoot.resolve(handle)));
    }
}
