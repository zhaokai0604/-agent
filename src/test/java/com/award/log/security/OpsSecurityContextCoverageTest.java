package com.award.log.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpsSecurityContextCoverageTest {

    @AfterEach
    void clear() {
        OpsSecurityContext.clear();
    }

    @Test
    void openOverloadsApplySafeDefaultsAndCanBeCleared() {
        OpsSecurityContext.open("t1", "inspect", true);
        assertEquals("t1", OpsSecurityContext.get().getTraceId());
        assertEquals(McpToolSurface.FULL, OpsSecurityContext.get().getToolSurface());
        assertTrue(OpsSecurityContext.get().isSkipPerToolCheck());

        OpsSecurityContext.open("t2", "write", false, null, false, true, true, "alice", null);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        assertEquals("t2", ctx.getTraceId());
        assertEquals(McpToolSurface.FULL, ctx.getToolSurface());
        assertTrue(ctx.isUserConfirmedWrite());
        assertTrue(ctx.isOpenToolPermissions());
        assertEquals("alice", ctx.getRequester());
        assertEquals("", ctx.getSessionId());
        OpsSecurityContext.clear();
        assertNull(OpsSecurityContext.get());
    }

    @Test
    void chatAgentOverloadsBindConfirmationPermissionsRequesterAndSession() {
        OpsSecurityContext.openChatAgent("chat", "clear temp", McpToolSurface.READ_ONLY, false, true, "bob", "s1");
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        assertTrue(ctx.isChatAgentPath());
        assertTrue(ctx.isUserConfirmedWrite());
        assertTrue(ctx.isOpenToolPermissions());
        assertEquals(McpToolSurface.READ_ONLY, ctx.getToolSurface());
        assertEquals("bob", ctx.getRequester());
        assertEquals("s1", ctx.getSessionId());
    }

    @Test
    void ctxBuilderDefaultsAccessorsEqualityAndMutationFormStableContract() {
        OpsSecurityContext.Ctx defaults = OpsSecurityContext.Ctx.builder().build();
        assertEquals(McpToolSurface.FULL, defaults.getToolSurface());
        assertFalse(defaults.isChatAgentPath());
        assertFalse(defaults.isUserConfirmedWrite());
        assertFalse(defaults.isOpenToolPermissions());
        assertEquals("anonymous", defaults.getRequester());
        assertEquals("", defaults.getSessionId());

        OpsSecurityContext.Ctx expected = OpsSecurityContext.Ctx.builder()
                .traceId("trace")
                .userMessage("inspect")
                .skipPerToolCheck(true)
                .toolSurface(McpToolSurface.READ_ONLY)
                .chatAgentPath(true)
                .userConfirmedWrite(true)
                .openToolPermissions(true)
                .requester("alice")
                .sessionId("session")
                .build();
        OpsSecurityContext.Ctx actual = OpsSecurityContext.Ctx.builder()
                .traceId("trace")
                .userMessage("inspect")
                .skipPerToolCheck(true)
                .toolSurface(McpToolSurface.READ_ONLY)
                .chatAgentPath(true)
                .userConfirmedWrite(true)
                .openToolPermissions(true)
                .requester("alice")
                .sessionId("session")
                .build();
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
        assertTrue(expected.toString().contains("trace"));

        actual.setTraceId("trace-2");
        actual.setUserMessage("write");
        actual.setSkipPerToolCheck(false);
        actual.setToolSurface(McpToolSurface.FULL);
        actual.setChatAgentPath(false);
        actual.setUserConfirmedWrite(false);
        actual.setOpenToolPermissions(false);
        actual.setRequester("operator");
        actual.setSessionId("session-2");
        assertEquals("trace-2", actual.getTraceId());
        assertEquals("write", actual.getUserMessage());
        assertFalse(actual.isSkipPerToolCheck());
        assertEquals(McpToolSurface.FULL, actual.getToolSurface());
        assertFalse(actual.isChatAgentPath());
        assertFalse(actual.isUserConfirmedWrite());
        assertFalse(actual.isOpenToolPermissions());
        assertEquals("operator", actual.getRequester());
        assertEquals("session-2", actual.getSessionId());
    }
}
