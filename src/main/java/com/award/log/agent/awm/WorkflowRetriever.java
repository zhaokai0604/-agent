package com.award.log.agent.awm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 按 domain / finding / 用户话术检索 Top-K workflow（AWM M + W 的选择性注入）。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRetriever {

    private final WorkflowMemoryService workflowMemoryService;

    public List<OpsWorkflow> retrieve(String domainTag, List<String> findingKinds, String userMessage, int limit) {
        if (!workflowMemoryService.isEnabled()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, 5));
        List<OpsWorkflow> candidates = domainTag == null
                ? workflowMemoryService.listEnabled()
                : workflowMemoryService.listByDomain(domainTag);

        List<Scored> scored = new ArrayList<>();
        for (OpsWorkflow wf : candidates) {
            double score = score(wf, findingKinds, userMessage);
            if (score > 0) {
                scored.add(new Scored(wf, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<OpsWorkflow> out = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, scored.size()); i++) {
            out.add(scored.get(i).workflow());
        }
        return out;
    }

    public OpsWorkflow bestMatch(String domainTag, List<String> findingKinds, String userMessage) {
        List<OpsWorkflow> list = retrieve(domainTag, findingKinds, userMessage, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public void recordHit(OpsWorkflow workflow) {
        if (workflow != null && workflow.workflowId() != null) {
            workflowMemoryService.recordUtility(workflow.workflowId());
        }
    }

    public List<OpsWorkflow> retrieveForContext(String domainTag, List<String> findingKinds, String userMessage) {
        return retrieve(domainTag, findingKinds, userMessage, 2);
    }

    private double score(OpsWorkflow wf, List<String> findingKinds, String userMessage) {
        double score = 1.0;
        if (findingKinds != null && wf.findingKinds() != null) {
            long overlap = findingKinds.stream()
                    .filter(k -> wf.findingKinds().stream().anyMatch(f -> f.equalsIgnoreCase(k)))
                    .count();
            score += overlap * 3.0;
        }
        if (userMessage != null && !userMessage.isBlank()) {
            String msg = userMessage.toLowerCase(Locale.ROOT);
            if (msg.contains("磁盘") || msg.contains("空间") || msg.contains("disk")) {
                if ("disk".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
            if (msg.contains("清理") || msg.contains("clean")) {
                if (wf.workflowId() != null && wf.workflowId().contains("remediation")) {
                    score += 1.5;
                }
            }
            if (msg.contains("cpu") || msg.contains("负载") || msg.contains("进程")) {
                if ("cpu".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
            if (msg.contains("服务") || msg.contains("systemd") || msg.contains("重启") || msg.contains("failed")) {
                if ("service".equals(wf.domainTag())) {
                    score += 2.0;
                }
            }
        }
        score += Math.min(5, wf.utilityCount()) * 0.2;
        return score;
    }

    private record Scored(OpsWorkflow workflow, double score) {
    }
}
