package com.award.log.service;

import com.award.log.config.OpsPepProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void restoreConflictPreservesBothVersionsAndHandle() throws Exception {
        Path file = Files.writeString(tempDir.resolve("conflict.txt"), "original");
        String handle = service.moveToRecycleBin(file, "trace");
        Files.writeString(file, "new version");
        assertFalse(service.restore(handle));
        assertEquals("new version", Files.readString(file));
        assertEquals(1, service.listEntries().size());
        Files.delete(file);
        assertTrue(service.restore(handle));
        assertEquals("original", Files.readString(file));
    }

    @Test
    void expiredListAndToolPreviewsNeverMutateFiles() throws Exception {
        Path file = Files.writeString(tempDir.resolve("preview.txt"), "original");
        String handle = service.moveToRecycleBin(file, "trace");
        Path meta = recycleRoot.resolve(handle).resolve("meta.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(meta));
        json.put("expiresAtMs", 1);
        Files.writeString(meta, mapper.writeValueAsString(json));
        var tool = new com.award.log.mcp.tools.RecycleBinTool(service, mapper);
        tool.recycleBinOps("restore", handle, true);
        tool.recycleBinOps("purge", null, true);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(recycleRoot.resolve(handle).resolve("preview.txt")));
        assertEquals(1, service.listEntries().size());
    }

    @Test
    void actualRestoreReturnsEvidenceMatchingRestoreContract() throws Exception {
        Path file=Files.writeString(tempDir.resolve("restore-tool.txt"),"preserved");
        String handle=service.moveToRecycleBin(file,"trace");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tool=new com.award.log.mcp.tools.RecycleBinTool(service,mapper);
        String restored=tool.recycleBinOps("restore",handle,false);
        assertTrue(mapper.readTree(restored).path("success").asBoolean());
        var params=Map.<String,Object>of("operation","restore","handle",handle,"dryRun",false);
        var effect=new com.award.log.security.effect.ToolEffectResolver().resolve("RecycleBinTool",params);
        assertEquals("RESTORE_PATH",effect.evidenceContractId());
        assertTrue(com.award.log.security.effect.EvidenceContractValidator.validate(effect,restored).complete());
        assertEquals("preserved",Files.readString(file));
        assertTrue(mapper.readTree(tool.recycleBinOps("purge",null,false)).path("success").asBoolean());
    }

    @Test
    void globalDryRunBlocksJavaFilesystemMutationsAndRecycleSelf(@org.junit.jupiter.api.io.TempDir Path unused) throws Exception {
        Path source=Files.writeString(tempDir.resolve("dry-run.txt"),"keep");
        var dry=new com.award.log.config.OpsDryRunProperties(); dry.setGlobal(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dryRunProperties",dry);
        assertThrows(IOException.class,()->service.moveToRecycleBin(source,"trace"));
        assertThrows(IOException.class,()->service.restore("abcdefghijkl"));
        assertThrows(IOException.class,service::purgeExpired);
        assertEquals("keep",Files.readString(source));
        dry.setGlobal(false);
        Files.createDirectories(recycleRoot);
        assertThrows(IOException.class,()->service.moveToRecycleBin(recycleRoot,"trace"));
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

    @Test
    void configuredRootsAreScannedAfterServiceRestart() throws Exception {
        Path secondRoot = tempDir.resolve("recycle-second");
        OpsPepProperties pep = new OpsPepProperties();
        pep.setRecycleTtlDays(7);
        OpsRecycleBinService first = new OpsRecycleBinService(pep, recycleRoot.toString(), secondRoot.toString());
        Path source = Files.writeString(tempDir.resolve("restart.txt"), "restart-payload");

        String handle = first.moveToRecycleBin(source, "restart-trace");
        OpsRecycleBinService afterRestart = new OpsRecycleBinService(pep, recycleRoot.toString(), secondRoot.toString());
        assertEquals(1, afterRestart.listEntries().size());
        assertEquals(handle, afterRestart.listEntries().get(0).get("handle"));
        assertTrue(afterRestart.restore(handle));
        assertEquals("restart-payload", Files.readString(source));
    }

    @Test
    void crossVolumeRootIsRejectedWithoutDeletingSourceWhenAvailable() throws Exception {
        FileStore sourceStore = Files.getFileStore(tempDir);
        Optional<Path> otherVolume = StreamSupport.stream(
                        java.nio.file.FileSystems.getDefault().getRootDirectories().spliterator(), false)
                .map(Path::toAbsolutePath)
                .filter(root -> {
                    try {
                        return !Files.getFileStore(root).equals(sourceStore);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .findFirst();
        assumeTrue(otherVolume.isPresent(), "当前环境只有一个文件系统卷");
        Path otherRoot = otherVolume.get().resolve("threshcore-recycle-test-" + java.util.UUID.randomUUID());
        try {
            OpsPepProperties pep = new OpsPepProperties();
            OpsRecycleBinService crossVolume = new OpsRecycleBinService(pep, otherRoot.toString());
            Path source = Files.writeString(tempDir.resolve("cross-volume.txt"), "must-stay");
            IOException failure = assertThrows(IOException.class,
                    () -> crossVolume.moveToRecycleBin(source, "cross-volume"));
            assertTrue(failure.getMessage().contains("跨卷") || failure.getMessage().contains("同一文件系统"));
            assertEquals("must-stay", Files.readString(source));
        } finally {
            if (Files.exists(otherRoot)) {
                try (var paths = Files.walk(otherRoot)) {
                    for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    void listAndPurgeUseBoundedPerRootScans() throws Exception {
        OpsPepProperties pep = new OpsPepProperties();
        pep.setRecycleTtlDays(7);
        OpsRecycleBinService bounded = new OpsRecycleBinService(pep, recycleRoot.toString(), "", 1);
        for (int i = 0; i < 3; i++) {
            bounded.moveToRecycleBin(Files.writeString(tempDir.resolve("bounded-" + i + ".txt"), "x"), "bounded");
        }
        assertTrue(bounded.listEntries().size() <= 1);
    }
}
