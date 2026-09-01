package com.award.log.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafeDeleteSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void recycleFailureIsFailClosedAndKeepsOriginalFile() throws Exception {
        OpsRecycleBinService recycleBin = mock(OpsRecycleBinService.class);
        Path file = tempDir.resolve("important.log");
        Files.writeString(file, "keep me");
        when(recycleBin.moveToRecycleBin(file, "trace-1"))
                .thenThrow(new IOException("recycle unavailable"));

        SafeDeleteSupport.DeleteOutcome outcome = new SafeDeleteSupport(recycleBin)
                .deleteFile(file, "trace-1", true);

        assertFalse(outcome.success());
        assertTrue(Files.exists(file));
        assertTrue(outcome.error().startsWith("recycle_failed"));
    }
}
