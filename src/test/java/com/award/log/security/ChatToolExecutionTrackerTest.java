package com.award.log.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolExecutionTrackerTest {

    @AfterEach
    void tearDown() {
        ChatToolExecutionTracker.clear();
    }

    @Test
    void keepsAllPendingConfirmationsAndPreservesLegacyFirstItem() {
        ChatToolExecutionTracker.recordPendingConfirmation(Map.of(
                "confirmationId", "cid-1",
                "toolName", "CleanTempTool",
                "capabilityToken", "cap-1"));
        ChatToolExecutionTracker.recordPendingConfirmation(Map.of(
                "confirmationId", "cid-2",
                "toolName", "ServiceRestartTool",
                "capabilityToken", "cap-2"));

        List<Map<String, Object>> all = ChatToolExecutionTracker.pendingConfirmations();
        assertEquals(2, all.size());
        assertEquals("cid-1", all.get(0).get("confirmationId"));
        assertEquals("cid-2", all.get(1).get("confirmationId"));
        assertEquals("cid-1", ChatToolExecutionTracker.pendingConfirmation().get("confirmationId"));
        assertTrue(ChatToolExecutionTracker.pendingConfirmation().containsKey("capabilityToken"));
    }
}
