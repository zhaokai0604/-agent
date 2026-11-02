package com.award.log.security;

import com.award.log.security.OpsSecurityContext.Ctx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWriteExecutionPolicyTest {

    @AfterEach
    void tearDown() {
        OpsSecurityContext.clear();
    }

    @Test
    void httpConfirmedWriteForcesRealDelete() {
        OpsSecurityContext.open("t", "confirm", true, McpToolSurface.FULL, false, true);
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                null, "/tmp/award-extract", 7, false, false, true);
        assertFalse(write.dryRun());
        assertTrue(write.confirmDelete());
        assertTrue(write.removeDirectory());
        assertTrue(write.days() == 0);
    }

    @Test
    void chatUnconfirmedStaysDryRun() {
        OpsSecurityContext.openChatAgent("t", "preview", McpToolSurface.FULL, false);
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                null, "/tmp", 7, null, null, false);
        assertTrue(write.dryRun());
        assertFalse(write.confirmDelete());
    }    @Test void explicitPreviewWinsOverConfirmedContextForEveryWriteKind() {
        OpsSecurityContext.openChatAgent("t","preview",McpToolSurface.FULL,true);
        assertTrue(ChatWriteExecutionPolicy.resolve(null,"/tmp/child",7,true,true,true).dryRun());
        assertTrue(ChatWriteExecutionPolicy.resolveRestart(true,true).dryRun());
        assertTrue(ChatWriteExecutionPolicy.resolveKill(true,true).dryRun());
        assertTrue(ChatWriteExecutionPolicy.resolveStop(true,true).dryRun());
    }
    @Test void fileCleanupCannotBecomeDirectoryRemovalImplicitly() {
        var policy=new OpsPathPolicy(new com.award.log.config.AgentOpsProperties());
        policy.updateEditablePathLists(com.award.log.util.OsRuntime.isWindows(),java.util.List.of("C:/Temp"),java.util.List.of("C:/Temp"),java.util.List.of());
        assertFalse(ChatWriteExecutionPolicy.resolve(policy,"C:/Temp/child",7,false,true,false).removeDirectory());
        assertFalse(ChatWriteExecutionPolicy.resolve(policy,"C:/Temp/child",7,false,true,null).removeDirectory());
    }

}
