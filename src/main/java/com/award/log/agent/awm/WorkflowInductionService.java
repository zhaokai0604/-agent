package com.award.log.agent.awm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWM 诱导：MVP 采用 rule-based 从成功轨迹提取工具序列（论文 §B 与 LM 诱导可后续扩展）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInductionService {

    private static final Pattern EXEC_TOOL = Pattern.compile("(CleanTemp|LogCleanup|ServiceRestart|Process|DiskAnalyze|DiskTool)");

    private final WorkflowMemoryService workflowMemoryService;
    private final TrajectoryEvaluator trajectoryEvaluator;
    private final OpsExperienceLoader experienceLoader;
    private final LlmWorkflowInductor llmWorkflowInductor;

    @org.springframework.beans.factory.annotation.Value("${agent.awm.llm-induction-enabled:false}")
    private boolean llmInductionEnabled;

    /**
     * 编排器运行结束后在线尝试：匹配已有 workflow 则累计 utility；否则 rule-based 新建。
     */
    public void afterSuccessfulRun(
            String traceId,
            String userMessage,
            String securityOutcome,
            boolean executionOk,
            List<Map<String, Object>> steps,
            String domainTag,
            List<String> findingKinds
    ) {
        if (!workflowMemoryService.isEnabled()) {
            return;
        }
        OpsExperience exp = new OpsExperience(
                traceId,
                userMessage,
                "ASSISTANT",
                "AssistantOrchestrator",
                securityOutcome,
                executionOk,
                null,
                steps,
                0L,
                null
        );
        if (!trajectoryEvaluator.shouldInduce(exp)) {
            return;
        }

        List<String> toolSeq = extractToolSequenceFromSteps(steps);
        if (toolSeq.size() < 2) {
            return;
        }

        OpsWorkflow matched = findByToolSequence(domainTag, toolSeq);
        if (matched != null) {
            workflowMemoryService.recordUtility(matched.workflowId());
            log.debug("AWM online: 命中已有 workflow {} utility+1", matched.workflowId());
            return;
        }

        if (workflowMemoryService.isDomainFull(domainTag)) {
            log.debug("AWM online: domain {} 已达上限，跳过诱导", domainTag);
            return;
        }

        OpsWorkflow induced = induceWorkflow(exp, domainTag, findingKinds, toolSeq);
        workflowMemoryService.upsert(induced, false);
        log.info("AWM online: 新诱导 workflow {} 来源={} 工具链 {}",
                induced.workflowId(), induced.sourceType(), toolSeq);
    }

    /**
     * 从已落库 trace 异步/批处理诱导（offline 入口）。
     */
    public int induceFromRecentTraces(int limit) {
        if (!workflowMemoryService.isEnabled()) {
            return 0;
        }
        int created = 0;
        for (OpsExperience exp : experienceLoader.loadRecentSuccessful(limit)) {
            if (!trajectoryEvaluator.shouldInduce(exp)) {
                continue;
            }
            String domain = inferDomain(exp);
            List<String> toolSeq = extractToolSequenceFromSteps(exp.steps());
            if (toolSeq.size() < 2 || workflowMemoryService.existsWithToolSequence(domain, toolSeq)) {
                continue;
            }
            if (workflowMemoryService.isDomainFull(domain)) {
                break;
            }
            OpsWorkflow wf = induceWorkflow(exp, domain, List.of(), toolSeq);
            workflowMemoryService.upsert(wf, false);
            created++;
        }
        return created;
    }

    private OpsWorkflow findByToolSequence(String domainTag, List<String> toolSeq) {
        for (OpsWorkflow wf : workflowMemoryService.listByDomain(domainTag)) {
            if (wf.toolSequence().equals(toolSeq)) {
                return wf;
            }
        }
        return null;
    }

    List<String> extractToolSequenceFromSteps(List<Map<String, Object>> steps) {
        List<String> seq = new ArrayList<>();
        if (steps == null) {
            return seq;
        }
        boolean diskPlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("LogCleanup") || d.contains("CleanTemp") || d.contains("DiskAnalyze");
        });
        boolean cpuPlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("SystemLoad") || d.contains("Process") || d.contains("CpuPressure");
        });

        boolean servicePlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("Systemd") || d.contains("systemd") || d.contains("ServiceRestart");
        });

        if (servicePlaybook && !diskPlaybook && !cpuPlaybook) {
            appendUnique(seq, "SystemdTool");
        } else if (cpuPlaybook && !diskPlaybook) {
            appendUnique(seq, "SystemLoadTool");
            if (steps.stream().anyMatch(s -> detail(s).contains("Process"))) {
                appendUnique(seq, "ProcessTool");
            }
        } else if (diskPlaybook) {
            boolean sawPerceive = steps.stream().anyMatch(s -> "perceive".equals(phase(s)));
            if (sawPerceive) {
                appendUnique(seq, "DiskTool");
                appendUnique(seq, "DiskAnalyzeTool");
            }
        }

        for (Map<String, Object> step : steps) {
            String d = detail(step);
            if (d.contains("LogCleanup")) {
                appendUnique(seq, "LogCleanupTool");
            }
            if (d.contains("CleanTemp")) {
                appendUnique(seq, "CleanTempTool");
            }
            if (d.contains("ServiceRestart")) {
                appendUnique(seq, "ServiceRestartTool");
            }
            Matcher m = EXEC_TOOL.matcher(d);
            while (m.find()) {
                appendUnique(seq, normalizeToolName(m.group(1)));
            }
        }
        if (diskPlaybook && steps.stream().anyMatch(s -> "verify".equals(phase(s))) && !seq.isEmpty()) {
            appendUnique(seq, "DiskTool");
        }
        return seq;
    }

    private OpsWorkflow induceWorkflow(
            OpsExperience exp,
            String domainTag,
            List<String> findingKinds,
            List<String> toolSeqFallback
    ) {
        if (llmInductionEnabled && llmWorkflowInductor.isAvailable()) {
            var llm = llmWorkflowInductor.induce(exp, domainTag, findingKinds);
            if (llm.isPresent()) {
                log.info("AWM LLM 归纳成功 trace={} workflow={}", exp.traceId(), llm.get().workflowId());
                return llm.get();
            }
            log.debug("AWM LLM 归纳未产出，回退 rule-based trace={}", exp.traceId());
        }
        return buildFromToolSequence(
                exp.traceId(),
                domainTag,
                findingKinds,
                toolSeqFallback,
                exp.userInput());
    }

    private OpsWorkflow buildFromToolSequence(
            String traceId,
            String domainTag,
            List<String> findingKinds,
            List<String> toolSeq,
            String userMessage
    ) {
        List<OpsWorkflowStep> steps = new ArrayList<>();
        for (String tool : toolSeq) {
            steps.add(OpsWorkflowStep.of(
                    "环境状态见当次感知",
                    defaultReason(tool),
                    tool,
                    defaultArgs(tool)));
        }
        String id = "online-" + domainTag + "-" + UUID.randomUUID().toString().substring(0, 8);
        String title = "在线诱导：" + domainTag + " 处置链";
        String desc = userMessage != null && userMessage.length() > 80
                ? userMessage.substring(0, 80) + "..."
                : (userMessage != null ? userMessage : "由成功轨迹自动归纳");
        return new OpsWorkflow(
                id,
                domainTag,
                findingKinds == null || findingKinds.isEmpty()
                        ? defaultFindingKinds(domainTag)
                        : findingKinds,
                title,
                desc,
                steps,
                "online",
                traceId,
                0,
                true
        );
    }

    private static List<String> defaultFindingKinds(String domainTag) {
        if ("cpu".equals(domainTag)) {
            return List.of("CPU_HIGH");
        }
        if ("service".equals(domainTag)) {
            return List.of("FAILED_SERVICE");
        }
        return List.of("DISK_PRESSURE");
    }

    private String inferDomain(OpsExperience exp) {
        String input = exp.userInput() != null ? exp.userInput().toLowerCase(Locale.ROOT) : "";
        if (input.contains("cpu") || input.contains("负载")) {
            return "cpu";
        }
        if (input.contains("服务") || input.contains("systemd")) {
            return "service";
        }
        return "disk";
    }

    private static void appendUnique(List<String> seq, String tool) {
        if (tool == null || tool.isBlank()) {
            return;
        }
        if (seq.isEmpty() || !seq.get(seq.size() - 1).equals(tool)) {
            seq.add(tool);
        }
    }

    private static String normalizeToolName(String fragment) {
        if (fragment == null) {
            return "";
        }
        return switch (fragment) {
            case "CleanTemp" -> "CleanTempTool";
            case "LogCleanup" -> "LogCleanupTool";
            case "ServiceRestart" -> "ServiceRestartTool";
            case "Process" -> "ProcessTool";
            case "SystemLoad" -> "SystemLoadTool";
            case "Systemd" -> "SystemdTool";
            case "DiskAnalyze" -> "DiskAnalyzeTool";
            case "DiskTool" -> "DiskTool";
            default -> fragment.endsWith("Tool") ? fragment : fragment + "Tool";
        };
    }

    private static String defaultReason(String toolName) {
        return switch (toolName) {
            case "DiskTool" -> "采集或验证分区占用";
            case "SystemLoadTool" -> "采集 CPU/内存/负载指标";
            case "ProcessTool" -> "列出高 CPU/内存进程（只读）";
            case "SystemdTool" -> "列出 failed systemd 单元";
            case "DiskAnalyzeTool" -> "定位热点目录（只读）";
            case "CleanTempTool" -> "清理临时目录（先 dryRun）";
            case "LogCleanupTool" -> "裁剪陈旧日志（先 dryRun）";
            case "ServiceRestartTool" -> "重启失败服务（白名单内）";
            default -> "执行工具 " + toolName;
        };
    }

    private static Map<String, String> defaultArgs(String toolName) {
        return switch (toolName) {
            case "CleanTempTool" -> Map.of("path", "{temp-path}", "days", "{temp-days}", "dryRun", "{dryRun}");
            case "LogCleanupTool" -> Map.of("path", "{log-path}", "days", "{log-days}", "dryRun", "{dryRun}");
            case "DiskAnalyzeTool" -> Map.of("path", "{log-path}", "topN", "12");
            case "ProcessTool" -> Map.of("minCpu", "5.0", "minMem", "5.0");
            case "ServiceRestartTool" -> Map.of("serviceName", "{service-name}", "dryRun", "{dryRun}");
            default -> Map.of();
        };
    }

    private static String phase(Map<String, Object> step) {
        return String.valueOf(step.get("phase")).toLowerCase(Locale.ROOT);
    }

    private static String detail(Map<String, Object> step) {
        Object d = step.get("detail");
        return d == null ? "" : String.valueOf(d);
    }
}
