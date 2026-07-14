package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<AutonomousOpsOrchestrator> autonomousOpsOrchestrator;

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
        return runResolved(userMessage, surface, intentRisk, playbook);
    }

    public OpsRunResult runFromContext(
            String userMessage,
            java.util.List<com.award.log.service.impl.UnifiedAssistantService.ChatTurn> history,
            McpToolSurface surface,
            RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolveFromContext(userMessage, history);
        return runResolved(userMessage, surface, intentRisk, playbook);
    }

    private OpsRunResult runResolved(
            String userMessage,
            McpToolSurface surface,
            RiskLevel intentRisk,
            OpsIntentRouter.Playbook playbook) {
        return switch (playbook) {
            case PATROL_AUTOMATION -> patrolAutomationService.run();
            case DISK_CLEANUP, CPU_PRESSURE, PATROL_CONTINUATION -> {
                AssistantOrchestrator.RunResult result = assistantOrchestrator.run(userMessage, surface, intentRisk);
                yield new OpsRunResult(result.markdown(), result.traceId(), result.streamMeta());
            }
            case NONE -> new OpsRunResult("未匹配到可执行的运维剧本。请换成更具体的说法，例如「全面检查本机」「检查系统状态」「扫描磁盘占用」或「CPU 占用过高」。", null, Map.of());
        };
    }

    public OpsRunResult runScheduledAutonomous(McpToolSurface surface, boolean forceRemediate) {
        AutonomousOpsOrchestrator orchestrator = autonomousOpsOrchestrator.getIfAvailable();
        if (orchestrator != null && forceRemediate) {
            AutonomousOpsOrchestrator.RunResult result = orchestrator.runScheduled(surface, true);
            return new OpsRunResult(result.markdown(), result.traceId(), result.report());
        }
        return patrolAutomationService.run();
    }
}
