package com.award.log.agent;

import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Agent 运行时统一入口：编排 Playbook，对外暴露单一 run API。
 */
@Service
@RequiredArgsConstructor
public class OpsRuntimeService {

    private final OpsIntentRouter opsIntentRouter;
    private final AutonomousOpsOrchestrator autonomousOpsOrchestrator;
    private final AssistantOrchestrator assistantOrchestrator;

    @Value("${agent.runtime.enabled:true}")
    private boolean enabled;

    public record RunResult(String markdown, String traceId, Map<String, Object> report) {}

    public boolean shouldOrchestrate(String userMessage) {
        return enabled && opsIntentRouter.shouldOrchestrate(userMessage);
    }

    public RunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolve(userMessage);
        return switch (playbook) {
            case FULL_AUTONOMOUS -> {
                AutonomousOpsOrchestrator.RunResult r =
                        autonomousOpsOrchestrator.run(userMessage, surface, intentRisk);
                yield new RunResult(r.markdown(), r.traceId(), r.report());
            }
            case DISK_CLEANUP, CPU_PRESSURE, PATROL_CONTINUATION -> {
                AssistantOrchestrator.RunResult r =
                        assistantOrchestrator.run(userMessage, surface, intentRisk);
                yield new RunResult(r.markdown(), r.traceId(), null);
            }
            case NONE -> new RunResult("未匹配运维 Playbook", null, Map.of());
        };
    }

    public RunResult runScheduledAutonomous(McpToolSurface surface, boolean forceRemediate) {
        AutonomousOpsOrchestrator.RunResult r =
                autonomousOpsOrchestrator.runScheduled(surface, forceRemediate);
        return new RunResult(r.markdown(), r.traceId(), r.report());
    }
}
