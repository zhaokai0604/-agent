package com.award.log.agent;

import com.award.log.security.effect.VerifyOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RemediationClosureSupportTest {

    @Test
    void verifyToolJsonForDeleteEvidence() {
        String json = "{\"success\":true,\"data\":{\"mode\":\"DELETE\",\"filesDeleted\":2,\"bytesFreed\":100}}";
        Map<String, Object> report = RemediationClosureSupport.verifyToolJson(
                "CleanTempTool",
                json,
                RemediationClosureSupport.writeParams("CleanTempTool", "/tmp", false),
                "tester");
        assertEquals(VerifyOutcome.VERIFIED.name(), report.get("verifyOutcome"));
        assertTrue(RemediationClosureSupport.verifiedOk(report));
    }

    @Test
    void appendClosureStepsWritesVerifyAndRecover() {
        AssistantAuditRecorder recorder = mock(AssistantAuditRecorder.class);
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> report = RemediationClosureSupport.verifyToolJson(
                "CleanTempTool",
                "{\"success\":true,\"data\":{\"mode\":\"DELETE\",\"filesDeleted\":1,\"recycleHandle\":\"h1\"}}",
                RemediationClosureSupport.writeParams("CleanTempTool", "/tmp", false),
                "tester");
        RemediationClosureSupport.appendClosureSteps(recorder, steps, report);
        verify(recorder).addStep(eq(steps), eq("verify"), anyString());
        verify(recorder).addStep(eq(steps), eq("recover"), anyString());
    }
}
