package com.award.log.agent;

import com.award.log.service.OpsAutoRemediationService;
import com.award.log.service.OpsPatrolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检自动修复单轨：与 {@link AssistantOrchestrator} / MCP 分发解耦，避免启动期循环依赖。
 */
@Service
@RequiredArgsConstructor
public class OpsPatrolAutomationService {

    private final OpsPatrolService opsPatrolService;
    private final OpsAutoRemediationService opsAutoRemediationService;

    public OpsRunResult run() {
        opsPatrolService.runPatrolCycle();
        Map<String, Object> remediation = opsAutoRemediationService != null
                ? new LinkedHashMap<>(opsAutoRemediationService.getLastSummary())
                : new LinkedHashMap<>();
        Map<String, Object> pending = opsAutoRemediationService != null
                ? new LinkedHashMap<>(opsAutoRemediationService.getPendingProposalView("system"))
                : new LinkedHashMap<>(Map.of("hasPending", false));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("findings", opsPatrolService.getLastFindingsSnapshot());
        report.put("correlation", opsPatrolService.getLastCorrelationSnapshot());
        report.put("remediation", remediation);
        report.put("pending", pending);

        String traceId = remediation.get("traceId") != null ? String.valueOf(remediation.get("traceId")) : null;
        return new OpsRunResult(buildPatrolMarkdown(report, remediation, pending, traceId), traceId, report);
    }

    private String buildPatrolMarkdown(Map<String, Object> report,
                                       Map<String, Object> remediation,
                                       Map<String, Object> pending,
                                       String traceId) {
        StringBuilder md = new StringBuilder("## Patrol Automation\n\n");
        if (traceId != null && !traceId.isBlank()) {
            md.append(OpsReportFormat.traceIdBlock(traceId));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.getOrDefault("findings", List.of());
        md.append("- Findings: **").append(findings.size()).append("**\n");

        Object actions = remediation.get("actions");
        if (actions instanceof List<?> list) {
            md.append("- Remediation actions: **").append(list.size()).append("**\n");
        }

        if (Boolean.TRUE.equals(pending.get("hasPending"))) {
            md.append("- Pending confirmation exists for follow-up actions.\n");
            Object summary = pending.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) {
                md.append("- Pending summary: ").append(summary).append("\n");
            }
        } else if (!remediation.isEmpty()) {
            md.append("- Patrol auto-remediation has completed for this round.\n");
        } else {
            md.append("- Patrol completed with observation only.\n");
        }
        return md.toString();
    }
}
