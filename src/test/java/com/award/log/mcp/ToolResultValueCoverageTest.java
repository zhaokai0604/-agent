package com.award.log.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultValueCoverageTest {

    @Test
    void successWarnErrorAndJsonEnvelopeAccessorsAreConsistent() {
        ToolResult<String> success = ToolResult.success("ok", 12L, true);
        assertTrue(success.isSuccess());
        assertEquals(ToolResult.STATUS_SUCCESS, success.getStatus());
        assertTrue(success.getCacheHit());
        assertEquals("ok", success.getData());

        ToolResult<String> warn = ToolResult.warn("degraded", 3L);
        assertTrue(warn.isSuccess());
        assertEquals(ToolResult.STATUS_WARN, warn.getStatus());
        ToolResult<String> error = ToolResult.error("failed", System.currentTimeMillis());
        assertFalse(error.isSuccess());
        assertEquals(ToolResult.STATUS_ERROR, error.getStatus());
        assertTrue(error.getDurationMs() >= 0);

        ToolResult<String> custom = new ToolResult<>(false, null, "e", 1L, false);
        custom.setStatus(null);
        custom.setSuccess(true);
        custom.setData("data");
        assertTrue(custom.isSuccess());
        assertEquals("data", custom.getData());
        assertNotNull(custom.toString());
    }
}
