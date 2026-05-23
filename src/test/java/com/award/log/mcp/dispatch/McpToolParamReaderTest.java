package com.award.log.mcp.dispatch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolParamReaderTest {

    @Test
    void previewConfirmDoesNotEscalate() {
        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", true);
        assertFalse(McpToolParamReader.shouldEscalateToRealWrite(params, "清理临时文件"));
    }

    @Test
    void explicitConfirmDeleteEscalates() {
        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", true);
        params.put("confirmDelete", true);
        assertTrue(McpToolParamReader.shouldEscalateToRealWrite(params, null));
    }

    @Test
    void userUtteranceRealDeleteEscalates() {
        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", true);
        assertTrue(McpToolParamReader.shouldEscalateToRealWrite(params, "真实删除 /tmp 下文件"));
    }
}
