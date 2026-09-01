package com.award.log.mcp;

import com.award.log.security.effect.RemediationContractFactory;
import com.award.log.security.effect.RemediationVerifyEvaluator;
import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.security.effect.VerifyOutcome;
import com.award.log.security.effect.WriteRemediationContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteToolResultUnwrapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unwrapDoubleWrappedToolResultEnvelope() throws Exception {
        String inner = "{\"mode\":\"DELETE\",\"filesDeleted\":2,\"bytesFreed\":100}";
        String toolResult = "{\"success\":true,\"durationMs\":12,\"data\":"
                + MAPPER.writeValueAsString(inner) + "}";
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", toolResult);

        Map<String, Object> params = Map.of("dryRun", false, "confirmDelete", true);
        ToolEffect effect = new ToolEffectResolver().resolve("CleanTempTool", params);
        WriteRemediationContract contract = RemediationContractFactory.build(
                "CleanTempTool", params, effect, "u");
        Map<String, Object> report = RemediationVerifyEvaluator.evaluate(
                "CleanTempTool", exec, "{}", params, contract, effect);
        assertEquals(VerifyOutcome.VERIFIED.name(), report.get("verifyOutcome"),
                "double-wrapped ToolResult must verify as VERIFIED, got " + report);
        assertTrue(Boolean.TRUE.equals(report.get("verified")));
    }
}
