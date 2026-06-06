package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryEvaluatorTest {

    private final TrajectoryEvaluator evaluator = new TrajectoryEvaluator();

    @Test
    void shouldInduce_acceptsExecuted() {
        OpsExperience exp = new OpsExperience(
                "t1", "清理磁盘", "ASSISTANT", "AssistantOrchestrator",
                "EXECUTED", true, "ok", List.of(), 100L, null);
        assertTrue(evaluator.shouldInduce(exp));
    }

    @Test
    void shouldInduce_rejectsPreviewAndReject() {
        OpsExperience preview = new OpsExperience(
                "t2", "清理", "ASSISTANT", "AssistantOrchestrator",
                "PREVIEW", true, "ok", List.of(), 100L, null);
        assertFalse(evaluator.shouldInduce(preview));

        OpsExperience reject = new OpsExperience(
                "t3", "删库", "ASSISTANT", "AssistantOrchestrator",
                "REJECT_INTENT", false, "blocked", List.of(), 100L, null);
        assertFalse(evaluator.shouldInduce(reject));
    }

    @Test
    void extractToolSequence_fromDiskPlaybookSteps() {
        WorkflowInductionService induction = new WorkflowInductionService(
                null, evaluator, null, new LlmWorkflowInductor(new ObjectMapper()));
        List<Map<String, Object>> steps = List.of(
                Map.of("phase", "perceive", "detail", "disk df output"),
                Map.of("phase", "execute", "detail", "CleanTemp deleted=3"),
                Map.of("phase", "execute", "detail", "LogCleanup deleted=5"),
                Map.of("phase", "verify", "detail", "disk again")
        );
        List<String> seq = induction.extractToolSequenceFromSteps(steps);
        assertTrue(seq.contains("DiskTool"));
        assertTrue(seq.contains("DiskAnalyzeTool"));
        assertTrue(seq.contains("CleanTempTool"));
        assertTrue(seq.contains("LogCleanupTool"));
        assertEquals("DiskTool", seq.get(seq.size() - 1));
    }

    @Test
    void extractToolSequence_cpuPlaybook() {
        WorkflowInductionService induction = new WorkflowInductionService(
                null, evaluator, null, new LlmWorkflowInductor(new ObjectMapper()));
        List<Map<String, Object>> steps = List.of(
                Map.of("phase", "perceive", "detail", "SystemLoad json CpuPressure"),
                Map.of("phase", "perceive", "detail", "Process list top")
        );
        List<String> seq = induction.extractToolSequenceFromSteps(steps);
        assertTrue(seq.contains("SystemLoadTool"));
        assertTrue(seq.contains("ProcessTool"));
        assertFalse(seq.contains("DiskAnalyzeTool"));
    }
}
