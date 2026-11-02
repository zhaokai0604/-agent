package com.award.log.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolWriteGuardTest {

    @Test
    void emptyParametersAreNotMutation() {
        assertFalse(ChatToolWriteGuard.requestsRealMutation("CleanTempTool", null));
        assertFalse(ChatToolWriteGuard.requestsRealMutation("CleanTempTool", Map.of()));
    }

    @Test
    void forceRemediateRequestsMutation() {
        Map<String, Object> params = new HashMap<>();
        params.put("forceRemediate", true);
        assertTrue(ChatToolWriteGuard.requestsRealMutation("CleanTempTool", params));
        params.put("forceRemediate", "yes");
        assertTrue(ChatToolWriteGuard.requestsRealMutation("CleanTempTool", params));
    }

    @Test
    void confirmFlagsRequestMutation() {
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("confirmDelete", true)));
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("confirmRestart", "1")));
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("confirmKill", "yes")));
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("confirmStop", true)));
    }

    @Test
    void dryRunFalseRequestsMutation() {
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("dryRun", false)));
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("dryRun", "0")));
    }

    @Test
    void writeOperationWithoutDryRunRequestsMutation() {
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("operation", "clean_temp")));
        assertTrue(ChatToolWriteGuard.requestsRealMutation("t", Map.of("operation", "DELETE")));
        assertFalse(ChatToolWriteGuard.requestsRealMutation("t", Map.of("operation", "clean", "dryRun", true)));
    }

    @Test
    void readOnlyParametersAreSafe() {
        assertFalse(ChatToolWriteGuard.requestsRealMutation("DiskTool", Map.of("dryRun", true)));
        assertFalse(ChatToolWriteGuard.requestsRealMutation("ServiceOpsTool", Map.of("operation", "status")));
        assertFalse(ChatToolWriteGuard.requestsRealMutation("DiskTool", Map.of("operation", "status")));
    }

    @Test
    void blockMessageContainsToolName() {
        String msg = ChatToolWriteGuard.blockMessage("CleanTempTool");
        assertTrue(msg.contains("CleanTempTool"));
        assertTrue(msg.contains("工具箱"));
    }
}
