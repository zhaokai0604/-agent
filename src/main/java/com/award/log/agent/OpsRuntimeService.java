package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Runtime router for patrol automation and playbook orchestration.
 */
@Service
@RequiredArgsConstructor
public class OpsRuntimeService {

    private final OpsIntentRouter opsIntentRouter;
    private final AssistantOrchestrator assistantOrchestrator;
    private final OpsPatrolAutomationService patrolAutomationService;

    @Value("${agent.runtime.enabled:true}")
    private boolean enabled;

    public boolean shouldOrchestrate(String userMessage) {
        return enabled && opsIntentRouter.shouldOrchestrate(userMessage);
    }

    public boolean shouldOrchestrateFromContext(
            String userMessage,
            java.util.List<com.award.log.service.impl.UnifiedAssistantService.ChatTurn> history) {
        return enabled && opsIntentRouter.shouldOrchestrateFromContext(userMessage, history);
    }

    public OpsRunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolve(userMessage);
        return switch (playbook) {
            case PATROL_AUTOMATION -> patrolAutomationService.run();
            case DISK_CLEANUP, CPU_PRESSURE, PATROL_CONTINUATION -> {
                AssistantOrchestrator.RunResult result = assistantOrchestrator.run(userMessage, surface, intentRisk);
                yield new OpsRunResult(result.markdown(), result.traceId(), result.streamMeta());
            }
            case NONE -> new OpsRunResult("No matching playbook.", null, Map.of());
        };
    }

    public OpsRunResult runScheduledAutonomous(McpToolSurface surface, boolean forceRemediate) {
        return patrolAutomationService.run();
    }
}
