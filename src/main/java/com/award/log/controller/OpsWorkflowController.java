package com.award.log.controller;

import com.award.log.agent.awm.FailureInsight;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.agent.awm.LlmWorkflowInductor;
import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowMemoryService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AWM（Agent Workflow Memory）只读/管理接口，便于答辩展示已沉淀套路。
 */
@RestController
@RequestMapping("/api/ops/workflow")
@RequiredArgsConstructor
public class OpsWorkflowController {

    private final WorkflowMemoryService workflowMemoryService;
    private final WorkflowRetriever workflowRetriever;
    private final WorkflowInductionService workflowInductionService;
    private final FailureInsightService failureInsightService;
    private final LlmWorkflowInductor llmWorkflowInductor;

    @GetMapping("/memory")
    public Result<Map<String, Object>> memory(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", workflowMemoryService.isEnabled());
        resp.put("storedCount", workflowMemoryService.countEnabled());
        resp.put("llmInductionAvailable", llmWorkflowInductor.isAvailable());

        List<OpsWorkflow> workflows;
        if (domain == null || domain.isBlank() || "all".equalsIgnoreCase(domain.trim())) {
            workflows = workflowMemoryService.listEnabled();
        } else {
            int cap = Math.min(50, Math.max(1, limit));
            workflows = workflowRetriever.retrieve(
                    domain.trim(),
                    defaultFindingsForDomain(domain),
                    q,
                    cap
            );
        }
        resp.put("domain", domain == null || domain.isBlank() ? "all" : domain.trim());
        resp.put("workflows", workflows.stream().map(OpsWorkflow::toContextMap).toList());
        return Result.success(resp);
    }

    @GetMapping("/failure-insights")
    public Result<Map<String, Object>> failureInsights(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "12") int limit
    ) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", failureInsightService.isEnabled());
        int cap = Math.min(50, Math.max(1, limit));
        if (q != null && !q.isBlank()) {
            resp.putAll(failureInsightService.buildContextMap(q));
        } else {
            resp.put("storedCount", failureInsightService.countStored());
            resp.put("lessons", failureInsightService.listRecent(cap).stream()
                    .map(FailureInsight::toContextMap)
                    .toList());
            resp.put("note", "以下为历史安全拦截教训，仅供参考；不得绕过 OpsTrustPolicy 与安全门");
        }
        return Result.success(resp);
    }

    @PostMapping("/induce-from-audit")
    public Result<Map<String, Object>> induceFromAudit(@RequestParam(defaultValue = "20") int limit) {
        int created = workflowInductionService.induceFromRecentTraces(Math.min(100, Math.max(1, limit)));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("created", created);
        resp.put("storedCount", workflowMemoryService.countEnabled());
        return Result.success(resp);
    }

    private static List<String> defaultFindingsForDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return List.of();
        }
        return switch (domain.trim().toLowerCase(Locale.ROOT)) {
            case "disk" -> List.of("DISK_PRESSURE");
            case "cpu" -> List.of("CPU_PRESSURE");
            case "service" -> List.of("FAILED_SERVICE");
            default -> List.of();
        };
    }
}
