package com.award.log.security.effect;

import com.award.log.config.OpsPepProperties;
import com.award.log.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileApprovalManifestTest {
    @TempDir Path root;
    @Test void detectsContentChangeEvenWhenLengthAndTimestampArePreserved() throws Exception {
        Path file=Files.writeString(root.resolve("approved.log"),"AAAA");
        FileTime time=Files.getLastModifiedTime(file);
        var approved=FileApprovalManifest.capture(List.of(file),10,100);
        approved.verifyAll(10,100);
        Files.writeString(file,"BBBB"); Files.setLastModifiedTime(file,time);
        assertThrows(IOException.class,()->approved.verifyAll(10,100));
        assertEquals("BBBB",Files.readString(file));
    }
    @Test void treeDetectsAdditionsAndSelectionCannotExpand() throws Exception {
        Path dir=Files.createDirectory(root.resolve("tree"));
        Files.writeString(dir.resolve("one"),"data");
        var approved=FileApprovalManifest.capture(List.of(dir),10,100);
        assertEquals(2,approved.summary().get("entryCount"));
        Files.writeString(dir.resolve("two"),"new");
        assertThrows(IOException.class,()->approved.verifyAll(10,100));
        assertThrows(IOException.class,()->approved.verifySelection(List.of(dir,root)));
        assertThrows(IOException.class,()->approved.target(root));
        assertThrows(UnsupportedOperationException.class,()->approved.targets().clear());
    }
    @Test void boundedCaptureRejectsOversizeDuplicatesMissingAndLinks() throws Exception {
        Path file=Files.writeString(root.resolve("data"),"12345");
        assertThrows(IOException.class,()->FileApprovalManifest.capture(List.of(file),1,4));
        assertThrows(IOException.class,()->FileApprovalManifest.capture(List.of(file,file),5,100));
        Path tree=Files.createDirectory(root.resolve("tree")); Files.writeString(tree.resolve("child"),"x");
        assertThrows(IOException.class,()->FileApprovalManifest.capture(List.of(tree),1,100));
        assertThrows(IOException.class,()->FileApprovalManifest.capture(List.of(root.resolve("missing")),1,100));
        try { Files.createSymbolicLink(root.resolve("link"),file); }
        catch(IOException | UnsupportedOperationException | SecurityException unsupported) { return; }
        assertThrows(IOException.class,()->FileApprovalManifest.capture(List.of(root.resolve("link")),5,100));
    }
    @Test void scopeRejectsUncheckedAndUnapprovedWritesAndRestoresPreviousScope() throws Exception {
        Path file=Files.writeString(root.resolve("one"),"one");
        var manifest=FileApprovalManifest.capture(List.of(file),10,100);
        assertNull(FileApprovalScope.expected(file));
        try(var scope=FileApprovalScope.execute(manifest,10,100)) {
            assertThrows(IOException.class,()->FileApprovalScope.expected(file));
            FileApprovalScope.candidates(List.of(file)); assertTrue(scope.selected());
            assertNotNull(FileApprovalScope.expected(file));
            assertThrows(IOException.class,()->FileApprovalScope.candidates(List.of(file)));
            try(var inner=FileApprovalScope.capture(10,100)) {
                FileApprovalScope.candidates(List.of()); assertNotNull(inner.manifest());
            }
            assertNotNull(FileApprovalScope.expected(file));
        }
        assertNull(FileApprovalScope.expected(file));
    }
    @Test void replacementBetweenCheckAndMoveIsDetectedAfterStagingAndCompensated() throws Exception {
        Path file=Files.writeString(root.resolve("race.txt"),"approved");
        var manifest=FileApprovalManifest.capture(List.of(file),10,100);
        var recycle=new OpsRecycleBinService(new OpsPepProperties(),root.resolve("recycle").toString()) {
            @Override protected void moveToStaging(Path source,Path dest) throws IOException {
                Files.writeString(source,"replacement"); super.moveToStaging(source,dest);
            }
        };
        IOException failure=assertThrows(IOException.class,()->recycle.moveToRecycleBin(file,"trace",manifest.target(file)));
        assertTrue(failure.getMessage().contains("已移回"));
        assertEquals("replacement",Files.readString(file));
        assertTrue(recycle.listEntries().isEmpty());
    }    @Test void failedCompensationRetainsBothCurrentPathAndRecoveryHandle() throws Exception {
        Path file=Files.writeString(root.resolve("conflict.txt"),"approved");
        var manifest=FileApprovalManifest.capture(List.of(file),10,100);
        var recycle=new OpsRecycleBinService(new OpsPepProperties(),root.resolve("recycle-conflict").toString()) {
            @Override protected void moveToStaging(Path source,Path dest) throws IOException {
                Files.writeString(source,"replacement"); super.moveToStaging(source,dest); Files.writeString(source,"new occupant");
            }
        };
        IOException failure=assertThrows(IOException.class,()->recycle.moveToRecycleBin(file,"trace",manifest.target(file)));
        assertTrue(failure.getMessage().contains("handle="));
        assertEquals("new occupant",Files.readString(file));
        var entries=recycle.listEntries(); assertEquals(1,entries.size());
        assertEquals("replacement",Files.readString(Path.of((String)entries.get(0).get("stagedPath"))));
    }
    @Test void boundExecutionForbidsPermanentDeleteAndTracksPartialFailures() throws Exception {
        Path file=Files.writeString(root.resolve("keep.txt"),"keep");
        var manifest=FileApprovalManifest.capture(List.of(file),10,100);
        try(var scope=FileApprovalScope.execute(manifest,10,100)) {
            FileApprovalScope.candidates(List.of(file));
            var result=new SafeDeleteSupport(null).deleteFile(file,"trace",false);
            assertFalse(result.success()); assertFalse(scope.failure().isBlank()); assertTrue(Files.exists(file));
        }
    }

}
