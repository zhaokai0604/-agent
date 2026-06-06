package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.config.SystemConfigRuntimeState;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.tools.*;
import com.award.log.security.McpToolSurface;
import com.award.log.security.RiskLevel;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一自动运维编排：串联全部 MCP 工具，完成感知 → 诊断 →（策略内）修复 → 验证。
 * <p>
 * 与 {@link AssistantOrchestrator} 的关系：本类覆盖「全栈自主巡检」；后者保留磁盘/CPU 等单场景快捷闭环。
 */
@Slf4j
@Service
public class AutonomousOpsOrchestrator {

    private final SystemLoadTool systemLoadTool;
    private final DiskTool diskTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final ProcessTool processTool;
    private final SystemdTool systemdTool;
    private final NetworkTool networkTool;
    private final PortHealthTool portHealthTool;
    private final DockerTool dockerTool;
    private final FirewallTool firewallTool;
    private final CronJobTool cronJobTool;
    private final LogAnalysisTool logAnalysisTool;
    private final OsInsightTool osInsightTool;
    private final CleanTempTool cleanTempTool;
    private final LogCleanupTool logCleanupTool;
    private final ServiceRestartTool serviceRestartTool;
    private final OpsRemediationGate opsRemediationGate;
    private final ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    private final AssistantAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final ExecutorService perceiveExecutor;
    private final OpsIntentRouter opsIntentRouter;

    private final OpsPerceptionCache opsPerceptionCache;
    private final RemediationEffectEvaluator remediationEffectEvaluator;
    private final WorkflowRetriever workflowRetriever;
    private final WorkflowInductionService workflowInductionService;

    @Value("${agent.autonomous.enabled:true}")
    private boolean enabled;

    @Value("${agent.autonomous.disk-pressure-percent:80}")
    private double diskPressurePercent;

    @Value("${agent.autonomous.cpu-warn-percent:85}")
    private double cpuWarnPercent;

    @Value("${agent.autonomous.mem-warn-percent:88}")
    private double memWarnPercent;

    @Value("${agent.autonomous.log-clean-days:30}")
    private int logCleanDays;

    @Value("${agent.autonomous.temp-clean-days:7}")
    private int tempCleanDays;

    @Value("${agent.autonomous.health-check-host:127.0.0.1}")
    private String healthCheckHost;

    private String healthCheckPortsRaw;

    private String pingTarget = SystemConfigRuntimeState.DEFAULT_PING_TARGET;

    public AutonomousOpsOrchestrator(
            SystemLoadTool systemLoadTool,
            DiskTool diskTool,
            DiskAnalyzeTool diskAnalyzeTool,
            ProcessTool processTool,
            SystemdTool systemdTool,
            NetworkTool networkTool,
            PortHealthTool portHealthTool,
            DockerTool dockerTool,
            FirewallTool firewallTool,
            CronJobTool cronJobTool,
            LogAnalysisTool logAnalysisTool,
            OsInsightTool osInsightTool,
            CleanTempTool cleanTempTool,
            LogCleanupTool logCleanupTool,
            ServiceRestartTool serviceRestartTool,
            OpsRemediationGate opsRemediationGate,
            ServiceRestartCandidateResolver serviceRestartCandidateResolver,
            AssistantAuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            OpsIntentRouter opsIntentRouter,
            OpsPerceptionCache opsPerceptionCache,
            RemediationEffectEvaluator remediationEffectEvaluator,
            WorkflowRetriever workflowRetriever,
            WorkflowInductionService workflowInductionService,
            SystemConfigRuntimeState systemConfigRuntimeState) {
        this.systemLoadTool = systemLoadTool;
        this.diskTool = diskTool;
        this.diskAnalyzeTool = diskAnalyzeTool;
        this.processTool = processTool;
        this.systemdTool = systemdTool;
        this.networkTool = networkTool;
        this.portHealthTool = portHealthTool;
        this.dockerTool = dockerTool;
        this.firewallTool = firewallTool;
        this.cronJobTool = cronJobTool;
        this.logAnalysisTool = logAnalysisTool;
        this.osInsightTool = osInsightTool;
        this.cleanTempTool = cleanTempTool;
        this.logCleanupTool = logCleanupTool;
        this.serviceRestartTool = serviceRestartTool;
        this.opsRemediationGate = opsRemediationGate;
        this.serviceRestartCandidateResolver = serviceRestartCandidateResolver;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.opsIntentRouter = opsIntentRouter;
        this.opsPerceptionCache = opsPerceptionCache;
        this.remediationEffectEvaluator = remediationEffectEvaluator;
        this.workflowRetriever = workflowRetriever;
        this.workflowInductionService = workflowInductionService;
        this.healthCheckPortsRaw = systemConfigRuntimeState.healthCheckPortsCsv();
        this.pingTarget = systemConfigRuntimeState.getPingTarget();
        this.perceiveExecutor = Executors.newFixedThreadPool(
                Math.min(12, Runtime.getRuntime().availableProcessors() + 2),
                r -> {
                    Thread t = new Thread(r, "autonomous-ops-perceive");
                    t.setDaemon(true);
                    return t;
                });
    }

    public record RunResult(String markdown, String traceId, Map<String, Object> report) {}

    public boolean supports(String userMessage) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return opsIntentRouter.resolve(userMessage) == OpsIntentRouter.Playbook.FULL_AUTONOMOUS;
    }

    public void applyHotConfig(String healthCheckPortsRaw, String pingTarget) {
        if (healthCheckPortsRaw != null && !healthCheckPortsRaw.isBlank()) {
            this.healthCheckPortsRaw = healthCheckPortsRaw;
        }
        if (pingTarget != null && !pingTarget.isBlank()) {
            this.pingTarget = pingTarget;
        }
    }

    /**
     * 统一感知 API：只读采集 + 规则诊断（带 TTL 缓存）。
     */
    public Map<String, Object> buildPerceptionView(boolean useCache, McpToolSurface surface) {
        long now = System.currentTimeMillis();
        if (useCache) {
            Map<String, Object> cached = opsPerceptionCache.getIfFresh();
            if (cached != null) {
                return cached;
            }
        }
        try {
            List<Map<String, Object>> steps = auditRecorder.newSteps();
            PerceptionSnapshot perception = perceiveAll(steps);
            List<Finding> findings = diagnose(perception);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("readOnly", surface == McpToolSurface.READ_ONLY);
            view.put("snapshot", perception.toMap());
            view.put("findings", findings.stream().map(Finding::toMap).toList());
            view.put("cachedAtMs", now);
            opsPerceptionCache.put(Map.copyOf(view));
            return view;
        } catch (Exception e) {
            log.warn("感知快照采集失败: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "cachedAtMs", now);
        }
    }

    public RunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        boolean force = opsIntentRouter.forceRemediate(userMessage);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            force = true;
        }
        return runInternal(userMessage, surface, intentRisk, force);
    }

    /** 供 REST / 巡检回调：无用户话术时也可触发全栈闭环 */
    public RunResult runScheduled(McpToolSurface surface, boolean forceRemediate) {
        return runInternal("[scheduled-autonomous-ops]", surface, RiskLevel.LOW, forceRemediate);
    }

    private RunResult runInternal(String userMessage, McpToolSurface surface, RiskLevel intentRisk,
                                  boolean forceRemediate) {
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("traceId", traceId);
        report.put("readOnlySurface", surface == McpToolSurface.READ_ONLY);

        StringBuilder md = new StringBuilder();
        md.append("> **traceId:** `").append(traceId).append("`\n\n");
        md.append("## 自主运维闭环（全工具融合）\n\n");
        md.append("流程：**感知** → **诊断** → **处置** → **验证**\n\n");

        if (surface == McpToolSurface.READ_ONLY) {
            forceRemediate = false;
            md.append("> 当前为 **只读工具面**，仅执行观测与诊断，跳过写操作。\n\n");
        }

        try {
            auditRecorder.addCot(steps, 2, "感知", "并行调用全部只读 MCP 工具");
            PerceptionSnapshot perception = perceiveAll(steps);
            report.put("perception", perception.toMap());

            md.append("### 1. 环境感知\n\n");
            md.append(perception.toMarkdownSummary()).append("\n");

            auditRecorder.addCot(steps, 3, "推理", "规则引擎聚合 findings");
            List<Finding> findings = diagnose(perception);
            report.put("findings", findings.stream().map(Finding::toMap).collect(Collectors.toList()));
            md.append("### 2. 问题诊断\n\n");
            if (findings.isEmpty()) {
                md.append("- **未发现需处置项**，主机指标在正常阈值内。\n\n");
            } else {
                for (Finding f : findings) {
                    md.append("- **").append(f.severity()).append("** · ")
                            .append(f.title()).append(" — ").append(f.detail()).append("\n");
                }
                md.append("\n");
            }

            appendAwmGuidance(md, steps, findings, userMessage);

            boolean anyRemediated = false;
            Map<String, Object> metricsBeforeRemediation = null;
            RemediationSummary remediationSummary = null;
            if (!findings.isEmpty() && surface != McpToolSurface.READ_ONLY) {
                metricsBeforeRemediation = remediationEffectEvaluator.captureMetrics();
                auditRecorder.addCot(steps, 4, "安全校验", "治理准入 + OpsTrustPolicy 风险分档");
                auditRecorder.addCot(steps, 5, "执行", "按策略执行预览或自动修复");
                remediationSummary = remediate(findings, forceRemediate, steps, perception);
                report.put("remediation", remediationSummary.toMap());
                anyRemediated = remediationSummary.executedCount() > 0;
                if (anyRemediated) {
                    opsPerceptionCache.invalidate();
                }
                md.append("### 3. 自动处置\n\n");
                md.append(remediationSummary.toMarkdown()).append("\n");
            } else if (!findings.isEmpty()) {
                md.append("### 3. 自动处置\n\n");
                md.append("- 已跳过（只读面或策略限制）。可说 **「执行修复」** 或切换完整工具面后重试。\n\n");
            } else {
                md.append("### 3. 自动处置\n\n");
                md.append("- 无需处置。\n\n");
            }

            auditRecorder.addCot(steps, 6, "验证", "复采磁盘与负载");
            String verifyLoad = safeCall(() -> systemLoadTool.checkSystemLoad(), "SystemLoadTool", steps);
            String verifyDisk = safeCall(() -> diskTool.checkDiskUsage(), "DiskTool", steps);
            report.put("verify", Map.of("load", truncate(verifyLoad, 500), "disk", truncate(verifyDisk, 500)));
            md.append("### 4. 验证\n\n");
            md.append(summarizeDisk(verifyDisk)).append("\n");
            md.append(summarizeLoad(verifyLoad)).append("\n\n");

            if (anyRemediated && metricsBeforeRemediation != null && remediationSummary != null) {
                Map<String, Object> effect = remediationEffectEvaluator.evaluate(
                        metricsBeforeRemediation,
                        remediationEffectEvaluator.captureMetrics(),
                        remediationSummary.executedCount());
                report.put("remediationEffect", effect);
                auditRecorder.addStep(steps, "effect",
                        String.format("自愈评分 %s/100（%s）", effect.get("healingScore"), effect.get("verdict")));
                md.append("### 自愈效果评估\n\n");
                md.append(String.format("- 评分：**%s/100**（%s）\n", effect.get("healingScore"), effect.get("verdict")));
                md.append(String.format("- 磁盘使用率变化：%.2f%%\n", effect.get("diskUsageDeltaPct")));
                md.append(String.format("- CPU 使用率变化：%.2f%%\n\n", effect.get("cpuUsageDeltaPct")));
            }

            if (!anyRemediated && !findings.isEmpty() && surface != McpToolSurface.READ_ONLY) {
                md.append("---\n\n");
                md.append("部分项为 **预览/待确认**。回复 **「执行修复」** 可在策略允许范围内继续自动处置。\n");
            } else if (anyRemediated) {
                md.append("---\n\n");
                md.append("已在策略允许范围内完成自动处置；完整步骤见 traceId 审计。\n");
            }

            String status = anyRemediated ? "REMEDIATED" : (findings.isEmpty() ? "HEALTHY" : "DIAGNOSED");
            auditRecorder.record(traceId, userMessage, intentRisk.name(), status,
                    "AutonomousOpsOrchestrator", true,
                    md.substring(0, Math.min(500, md.length())), steps, System.currentTimeMillis() - t0);
            AwmContext awmCtx = resolveAwmContext(findings);
            workflowInductionService.afterSuccessfulRun(
                    traceId,
                    userMessage,
                    status,
                    true,
                    steps,
                    awmCtx.domain(),
                    awmCtx.kinds());
            return new RunResult(md.toString(), traceId, report);

        } catch (Exception e) {
            log.error("自主运维编排失败 traceId={}", traceId, e);
            auditRecorder.addStep(steps, "error", e.getMessage());
            md.append("\n\n**编排失败:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "AutonomousOpsOrchestrator", false, e.getMessage(), steps, System.currentTimeMillis() - t0);
            report.put("error", e.getMessage());
            return new RunResult(md.toString(), traceId, report);
        }
    }

    private PerceptionSnapshot perceiveAll(List<Map<String, Object>> steps) throws InterruptedException {
        Map<String, String> raw = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = List.of(
                () -> { raw.put("load", safeCall(() -> systemLoadTool.checkSystemLoad(), "SystemLoadTool", steps)); return null; },
                () -> { raw.put("disk", safeCall(() -> diskTool.checkDiskUsage(), "DiskTool", steps)); return null; },
                () -> { raw.put("diskAnalyze", safeCall(() -> diskAnalyzeTool.analyzeDiskPressure(defaultLogRoot(), true, 10),
                        "DiskAnalyzeTool", steps)); return null; },
                () -> { raw.put("process", safeCall(() -> processTool.listProcesses(5.0, 5.0), "ProcessTool", steps)); return null; },
                () -> { raw.put("systemdFailed", safeCall(() -> systemdTool.listFailedSystemdUnits(), "SystemdTool", steps)); return null; },
                () -> { raw.put("network", safeCall(() -> networkTool.diagnoseNetwork(pingTarget, "ping", 3),
                        "NetworkTool", steps)); return null; },
                () -> { raw.put("firewall", safeCall(() -> firewallTool.checkFirewallStatus(), "FirewallTool", steps)); return null; },
                () -> { raw.put("cron", safeCall(() -> cronJobTool.listCronJobs("user"), "CronJobTool", steps)); return null; },
                () -> { raw.put("docker", safeCall(() -> dockerTool.listDockerContainers(false), "DockerTool", steps)); return null; },
                () -> { raw.put("journal", safeCall(() -> osInsightTool.queryJournalLogs(30, 80, false),
                        "OsInsightTool", steps)); return null; },
                () -> { raw.put("logAnalysis", safeCall(() -> logAnalysisTool.analyzeLogs(defaultLogRoot(), 80),
                        "LogAnalysisTool", steps)); return null; }
        );

        List<Callable<Void>> portTasks = parseHealthPorts().stream()
                .map(port -> (Callable<Void>) () -> {
                    String key = "port_" + port;
                    raw.put(key, safeCall(() -> portHealthTool.checkPortConnectivity(healthCheckHost, port, 3000),
                            "PortHealthTool:" + port, steps));
                    return null;
                })
                .toList();

        List<Callable<Void>> all = new ArrayList<>(tasks);
        all.addAll(portTasks);
        List<Future<Void>> futures = perceiveExecutor.invokeAll(all, 90, TimeUnit.SECONDS);
        for (Future<Void> f : futures) {
            try {
                f.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("感知任务结束: {}", e.getMessage());
            }
        }
        return PerceptionSnapshot.fromRaw(raw, objectMapper);
    }

    private List<Finding> diagnose(PerceptionSnapshot p) {
        List<Finding> out = new ArrayList<>();
        if (p.maxDiskUsePercent() >= diskPressurePercent) {
            out.add(new Finding("HIGH", "磁盘压力",
                    String.format(Locale.ROOT, "最高分区使用率约 %.0f%%（阈值 %.0f%%）",
                            p.maxDiskUsePercent(), diskPressurePercent),
                    "DISK_PRESSURE"));
        }
        if (p.cpuUsagePercent() >= cpuWarnPercent) {
            out.add(new Finding("HIGH", "CPU 负载偏高",
                    String.format(Locale.ROOT, "CPU 约 %.1f%%", p.cpuUsagePercent()), "CPU_HIGH"));
        }
        if (p.memUsagePercent() >= memWarnPercent) {
            out.add(new Finding("MEDIUM", "内存压力",
                    String.format(Locale.ROOT, "内存约 %.1f%%", p.memUsagePercent()), "MEM_HIGH"));
        }
        if (p.hasFailedUnits()) {
            out.add(new Finding("HIGH", "异常服务单元",
                    "systemctl --failed 或 Windows 非 Running 服务存在", "FAILED_SERVICE"));
        }
        for (Integer port : p.unreachablePorts()) {
            out.add(new Finding("MEDIUM", "端口不可达",
                    healthCheckHost + ":" + port + " TCP 连接失败", "PORT_DOWN"));
        }
        if (p.networkPacketLossPercent() >= 50) {
            out.add(new Finding("MEDIUM", "网络丢包",
                    String.format(Locale.ROOT, "ping %s 丢包约 %.0f%%", pingTarget, p.networkPacketLossPercent()),
                    "NETWORK_LOSS"));
        }
        if (p.logAnomalyHint()) {
            out.add(new Finding("MEDIUM", "日志异常线索",
                    "近期 journal / LogAnalysis 含 error 或异常聚类", "LOG_ANOMALY"));
        }
        if (p.dockerIssueHint()) {
            out.add(new Finding("LOW", "容器状态需关注",
                    "Docker 列表含非 running 状态", "DOCKER_STATE"));
        }
        if (p.zombieProcessHint()) {
            out.add(new Finding("MEDIUM", "僵尸进程",
                    "进程列表含 Z 状态条目，建议排查父进程回收", "ZOMBIE_PROCESS"));
        }
        return out;
    }

    private RemediationSummary remediate(List<Finding> findings, boolean forceRemediate,
                                       List<Map<String, Object>> steps,
                                       PerceptionSnapshot perception) throws Exception {
        List<String> actions = new ArrayList<>();
        int executed = 0;
        int previewed = 0;
        int skipped = 0;

        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());
        boolean needDiskRelief = kinds.contains("DISK_PRESSURE") || kinds.contains("LOG_ANOMALY");

        if (needDiskRelief) {
            RemediationOutcome temp = remediateTempClean(forceRemediate, steps);
            actions.add(temp.message());
            executed += temp.executed() ? 1 : 0;
            previewed += temp.previewed() ? 1 : 0;
            skipped += temp.skipped() ? 1 : 0;

            RemediationOutcome log = remediateLogClean(forceRemediate, steps);
            actions.add(log.message());
            executed += log.executed() ? 1 : 0;
            previewed += log.previewed() ? 1 : 0;
            skipped += log.skipped() ? 1 : 0;
        }

        if (kinds.contains("CPU_HIGH")) {
            String svc = serviceRestartCandidateResolver.pickDefaultFromAllowlist();
            if (svc != null) {
                RemediationOutcome restart = remediateServiceRestart(svc, forceRemediate, steps);
                actions.add(restart.message());
                executed += restart.executed() ? 1 : 0;
                previewed += restart.previewed() ? 1 : 0;
                skipped += restart.skipped() ? 1 : 0;
            } else {
                actions.add("- CPU 偏高：无治理允许的白名单服务可重启，建议人工排查高占用进程。");
                skipped++;
            }
        }

        if (kinds.contains("FAILED_SERVICE")) {
            String svc = serviceRestartCandidateResolver.pickFromFailedUnits(perception.failedUnitNames());
            if (svc != null) {
                RemediationOutcome restart = remediateServiceRestart(svc, forceRemediate, steps);
                actions.add(restart.message());
                executed += restart.executed() ? 1 : 0;
                previewed += restart.previewed() ? 1 : 0;
                skipped += restart.skipped() ? 1 : 0;
            } else {
                actions.add("- 失败 unit：未命中可重启白名单或治理禁止，请结合 SystemdTool 输出人工确认。");
                skipped++;
            }
        }

        if (actions.isEmpty()) {
            actions.add("- 无匹配的自动修复动作。");
        }
        return new RemediationSummary(actions, executed, previewed, skipped);
    }

    private RemediationOutcome remediateTempClean(boolean force, List<Map<String, Object>> steps) throws Exception {
        String path = OsRuntime.isWindows() ? "C:\\Windows\\Temp" : "/tmp";
        String instruction = "AutonomousOps CleanTempTool path=" + path;
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideTempCleanup(path, tempCleanDays, force, instruction);
        String preview = cleanTempTool.cleanTempFiles(path, tempCleanDays, true, false);
        int found = countFromTool(preview, "filesFound");
        if (found == 0) {
            return RemediationOutcome.skipped("- 临时目录：无可清理文件。");
        }
        if (decision.forbidden()) {
            return RemediationOutcome.skipped("- 临时目录：" + decision.reason());
        }
        if (decision.mayPreview()) {
            return RemediationOutcome.previewed("- 临时目录：预览 **" + found + "** 个文件，待确认。（" + decision.reason() + "）");
        }
        String exec = cleanTempTool.cleanTempFiles(path, tempCleanDays, false, true);
        int deleted = countFromTool(exec, "filesDeleted");
        auditRecorder.addStep(steps, "execute", "CleanTemp deleted=" + deleted);
        return RemediationOutcome.executed("- 临时目录：已删除 **" + deleted + "** 个文件。");
    }

    private RemediationOutcome remediateLogClean(boolean force, List<Map<String, Object>> steps) throws Exception {
        String path = defaultLogRoot();
        String instruction = "AutonomousOps LogCleanupTool path=" + path;
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideLogCleanup(path, logCleanDays, force, instruction);
        String preview = logCleanupTool.cleanupOldLogs(path, logCleanDays, true, false);
        int found = countFromTool(preview, "deletableCount", "filesFound");
        if (found == 0) {
            return RemediationOutcome.skipped("- 日志目录：无符合策略的陈旧文件。");
        }
        if (decision.forbidden()) {
            return RemediationOutcome.skipped("- 日志目录：" + decision.reason());
        }
        if (decision.mayPreview()) {
            return RemediationOutcome.previewed("- 日志目录：预览 **" + found + "** 个可删文件，待确认。（" + decision.reason() + "）");
        }
        String exec = logCleanupTool.cleanupOldLogs(path, logCleanDays, false, true);
        int deleted = countFromTool(exec, "filesDeleted");
        auditRecorder.addStep(steps, "execute", "LogCleanup deleted=" + deleted);
        return RemediationOutcome.executed("- 日志目录：已删除 **" + deleted + "** 个文件。");
    }

    private RemediationOutcome remediateServiceRestart(String serviceName, boolean force,
                                                       List<Map<String, Object>> steps) throws Exception {
        String instruction = "AutonomousOps ServiceRestartTool " + serviceName;
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideServiceRestart(serviceName, force, instruction);
        if (decision.forbidden()) {
            return RemediationOutcome.skipped("- 服务 **" + serviceName + "**：" + decision.reason());
        }
        if (decision.mayPreview()) {
            serviceRestartTool.restartService(serviceName, true, false);
            return RemediationOutcome.previewed("- 服务 **" + serviceName + "**：已预览，待确认后重启。（" + decision.reason() + "）");
        }
        serviceRestartTool.restartService(serviceName, false, true);
        auditRecorder.addStep(steps, "execute", "ServiceRestart " + serviceName);
        return RemediationOutcome.executed("- 服务 **" + serviceName + "**：已执行重启。");
    }

    private List<Integer> parseHealthPorts() {
        if (healthCheckPortsRaw == null || healthCheckPortsRaw.isBlank()) {
            return List.of(8080);
        }
        List<Integer> ports = new ArrayList<>();
        for (String p : healthCheckPortsRaw.split(",")) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0 && v <= 65535) {
                    ports.add(v);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ports.isEmpty() ? List.of(8080) : ports;
    }

    private static boolean chatPreviewOnly() {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            return false;
        }
        return ctx != null && ctx.isChatAgentPath();
    }

    private String buildRemediationPlan(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Finding f : findings) {
            sb.append(i++).append(". **").append(f.title()).append("** — ").append(f.detail()).append("\n");
            String action = planActionFor(f);
            if (action != null) {
                sb.append("   - 建议动作：").append(action).append("\n");
            }
        }
        sb.append("\n> 以上为基于当前采集的处置建议。回复 **「确认执行」** 将在策略允许范围内自动落地；否则仅预览。\n");
        return sb.toString();
    }

    private static String planActionFor(Finding f) {
        if (f == null || f.kind() == null) {
            return null;
        }
        return switch (f.kind()) {
            case "DISK_PRESSURE" -> "扫描热点目录 → 预览清理临时/陈旧日志 → 验证磁盘使用率";
            case "CPU_HIGH" -> "定位高 CPU 进程 → 评估是否重启白名单内服务";
            case "LOG_ANOMALY" -> "分析异常日志 → 预览日志目录清理";
            case "MEM_HIGH" -> "列出内存占用进程 → 建议限流或扩容";
            case "FAILED_SERVICE" -> "检查失败单元 → 预览重启（白名单内）";
            default -> "调用对应 MCP 工具验证后按策略处置";
        };
    }

    private String safeCall(Callable<String> call, String label, List<Map<String, Object>> steps) {
        try {
            String result = call.call();
            auditRecorder.addStep(steps, "perceive", label + ": " + truncate(result, 400));
            return result;
        } catch (Exception e) {
            log.warn("{} 感知失败: {}", label, e.getMessage());
            auditRecorder.addStep(steps, "perceive-error", label + ": " + e.getMessage());
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String defaultLogRoot() {
        return OsRuntime.isWindows() ? "C:\\Windows\\Logs" : "/var/log";
    }

    private int countFromTool(String toolJson, String... keys) {
        JsonNode data = parseToolData(toolJson);
        if (data == null) {
            return 0;
        }
        for (String k : keys) {
            if (data.has(k)) {
                return data.get(k).asInt(0);
            }
        }
        return 0;
    }

    private JsonNode parseToolData(String toolJson) {
        try {
            JsonNode root = objectMapper.readTree(toolJson);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                return null;
            }
            if (dataNode.isTextual()) {
                return objectMapper.readTree(dataNode.asText());
            }
            return dataNode;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeDisk(String diskToolJson) {
        double max = extractMaxDiskUsePercent(diskToolJson);
        if (max > 0) {
            return "- 验证：分区最高使用率约 **" + String.format(Locale.ROOT, "%.0f", max) + "%**";
        }
        return "- 验证：已复采磁盘（见工具输出）";
    }

    private String summarizeLoad(String loadJson) {
        JsonNode data = parseToolData(loadJson);
        if (data != null) {
            double cpu = data.path("cpuUsagePercent").asDouble(-1);
            double mem = data.path("memUsagePercent").asDouble(-1);
            if (cpu >= 0) {
                return "- 验证：CPU **" + String.format(Locale.ROOT, "%.1f", cpu)
                        + "%**，内存 **" + String.format(Locale.ROOT, "%.1f", mem) + "%**";
            }
        }
        return "- 验证：已复采系统负载";
    }

    private double extractMaxDiskUsePercent(String diskToolJson) {
        try {
            JsonNode root = objectMapper.readTree(diskToolJson);
            JsonNode data = root.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }
            if (data.isArray()) {
                double max = 0;
                for (JsonNode n : data) {
                    String use = n.path("usage").asText("");
                    if (use.endsWith("%")) {
                        try {
                            max = Math.max(max, Double.parseDouble(use.replace("%", "").trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                return max;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record AwmContext(String domain, List<String> kinds) {
    }

    private AwmContext resolveAwmContext(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return new AwmContext("disk", List.of("DISK_PRESSURE"));
        }
        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());
        if (kinds.contains("FAILED_SERVICE")) {
            return new AwmContext("service", List.of("FAILED_SERVICE"));
        }
        if (kinds.contains("DISK_PRESSURE") || kinds.contains("LOG_ANOMALY")) {
            return new AwmContext("disk", List.of("DISK_PRESSURE"));
        }
        if (kinds.contains("CPU_HIGH")) {
            return new AwmContext("cpu", List.of("CPU_HIGH"));
        }
        return new AwmContext("disk", List.of("DISK_PRESSURE"));
    }

    private void appendAwmGuidance(StringBuilder md, List<Map<String, Object>> steps,
                                   List<Finding> findings, String userMessage) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        AwmContext ctx = resolveAwmContext(findings);
        List<OpsWorkflow> workflows = workflowRetriever.retrieve(ctx.domain(), ctx.kinds(), userMessage, 2);
        if (workflows.isEmpty()) {
            return;
        }
        md.append("### 处置套路（AWM）\n\n");
        for (OpsWorkflow wf : workflows) {
            auditRecorder.addStep(steps, "workflow", "命中 AWM workflow=" + wf.workflowId());
            md.append(wf.toMarkdownBrief()).append("\n\n");
        }
        md.append("> 环境不一致时应偏离上述套路；写操作仍受治理准入与 OpsTrustPolicy 约束。\n\n");
    }

    private record Finding(String severity, String title, String detail, String kind) {
        Map<String, Object> toMap() {
            return Map.of("severity", severity, "title", title, "detail", detail, "kind", kind);
        }
    }

    private record RemediationOutcome(boolean executed, boolean previewed, boolean skipped, String message) {
        static RemediationOutcome executed(String msg) {
            return new RemediationOutcome(true, false, false, msg);
        }

        static RemediationOutcome previewed(String msg) {
            return new RemediationOutcome(false, true, false, msg);
        }

        static RemediationOutcome skipped(String msg) {
            return new RemediationOutcome(false, false, true, msg);
        }
    }

    private record RemediationSummary(List<String> actions, int executedCount, int previewCount, int skippedCount) {
        Map<String, Object> toMap() {
            return Map.of("actions", actions, "executedCount", executedCount,
                    "previewCount", previewCount, "skippedCount", skippedCount);
        }

        String toMarkdown() {
            return String.join("\n", actions) + "\n";
        }
    }

    private static final class PerceptionSnapshot {
        private final double maxDiskUsePercent;
        private final double cpuUsagePercent;
        private final double memUsagePercent;
        private final boolean hasFailedUnits;
        private final List<String> failedUnitNames;
        private final List<Integer> unreachablePorts;
        private final double networkPacketLossPercent;
        private final boolean logAnomalyHint;
        private final boolean dockerIssueHint;
        private final boolean zombieProcessHint;
        private final Map<String, String> raw;

        private PerceptionSnapshot(double maxDiskUsePercent, double cpuUsagePercent, double memUsagePercent,
                                   boolean hasFailedUnits, List<String> failedUnitNames,
                                   List<Integer> unreachablePorts,
                                   double networkPacketLossPercent, boolean logAnomalyHint,
                                   boolean dockerIssueHint, boolean zombieProcessHint, Map<String, String> raw) {
            this.maxDiskUsePercent = maxDiskUsePercent;
            this.cpuUsagePercent = cpuUsagePercent;
            this.memUsagePercent = memUsagePercent;
            this.hasFailedUnits = hasFailedUnits;
            this.failedUnitNames = failedUnitNames != null ? List.copyOf(failedUnitNames) : List.of();
            this.unreachablePorts = unreachablePorts;
            this.networkPacketLossPercent = networkPacketLossPercent;
            this.logAnomalyHint = logAnomalyHint;
            this.dockerIssueHint = dockerIssueHint;
            this.zombieProcessHint = zombieProcessHint;
            this.raw = raw;
        }

        static PerceptionSnapshot fromRaw(Map<String, String> raw, ObjectMapper mapper) {
            double maxDisk = 0;
            double cpu = 0;
            double mem = 0;
            boolean failedUnits = false;
            List<String> failedUnitNames = List.of();
            List<Integer> badPorts = new ArrayList<>();
            double packetLoss = 0;
            boolean logHint = false;
            boolean dockerHint = false;
            boolean zombieHint = false;

            try {
                if (raw.containsKey("disk")) {
                    JsonNode root = mapper.readTree(raw.get("disk"));
                    JsonNode data = unwrapData(root, mapper);
                    if (data.isArray()) {
                        for (JsonNode n : data) {
                            String use = n.path("usage").asText("");
                            if (use.endsWith("%")) {
                                try {
                                    maxDisk = Math.max(maxDisk, Double.parseDouble(use.replace("%", "").trim()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
                if (raw.containsKey("load")) {
                    JsonNode data = unwrapData(mapper.readTree(raw.get("load")), mapper);
                    cpu = data.path("cpuUsagePercent").asDouble(0);
                    mem = data.path("memUsagePercent").asDouble(0);
                }
                if (raw.containsKey("systemdFailed")) {
                    String systemdJson = raw.get("systemdFailed");
                    failedUnitNames = ServiceRestartCandidateResolver.parseFailedUnitsFromToolJson(systemdJson, mapper);
                    String out = systemdJson.toLowerCase(Locale.ROOT);
                    failedUnits = !failedUnitNames.isEmpty() || out.contains("failed") || out.contains("stopped")
                            || (out.contains("success") && out.contains("\"output\"") && !out.contains("0 failed"));
                }
                if (raw.containsKey("network")) {
                    JsonNode data = unwrapData(mapper.readTree(raw.get("network")), mapper);
                    if (data.isObject()) {
                        packetLoss = data.path("packetLossPercent").asDouble(0);
                    } else if (data.isTextual()) {
                        JsonNode inner = mapper.readTree(data.asText());
                        packetLoss = inner.path("packetLossPercent").asDouble(0);
                    }
                }
                String journal = raw.getOrDefault("journal", "");
                String logA = raw.getOrDefault("logAnalysis", "");
                logHint = containsErrorHint(journal) || containsErrorHint(logA);
                String docker = raw.getOrDefault("docker", "").toLowerCase(Locale.ROOT);
                dockerHint = docker.contains("exited") || docker.contains("dead") || docker.contains("restarting");
                String processBlob = raw.getOrDefault("process", "").toLowerCase(Locale.ROOT);
                zombieHint = processBlob.contains(" z ") || processBlob.contains("\"z\"")
                        || processBlob.contains("zombie") || processBlob.contains("状态 z");
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    if (!e.getKey().startsWith("port_")) {
                        continue;
                    }
                    try {
                        JsonNode data = unwrapData(mapper.readTree(e.getValue()), mapper);
                        boolean reachable = data.path("reachable").asBoolean(true);
                        if (!reachable) {
                            badPorts.add(Integer.parseInt(e.getKey().substring(5)));
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log.debug("解析感知快照失败: {}", e.getMessage());
            }

            return new PerceptionSnapshot(maxDisk, cpu, mem, failedUnits, failedUnitNames, badPorts, packetLoss, logHint, dockerHint,
                    zombieHint, raw);
        }

        private static JsonNode unwrapData(JsonNode root, ObjectMapper mapper) throws com.fasterxml.jackson.core.JsonProcessingException {
            JsonNode data = root.path("data");
            if (data.isTextual()) {
                return mapper.readTree(data.asText());
            }
            return data.isMissingNode() ? root : data;
        }

        private static boolean containsErrorHint(String blob) {
            if (blob == null) {
                return false;
            }
            String l = blob.toLowerCase(Locale.ROOT);
            return l.contains("error") || l.contains("fatal") || l.contains("exception") || l.contains("failed");
        }

        double maxDiskUsePercent() {
            return maxDiskUsePercent;
        }

        double cpuUsagePercent() {
            return cpuUsagePercent;
        }

        double memUsagePercent() {
            return memUsagePercent;
        }

        boolean hasFailedUnits() {
            return hasFailedUnits;
        }

        List<String> failedUnitNames() {
            return failedUnitNames;
        }

        List<Integer> unreachablePorts() {
            return unreachablePorts;
        }

        double networkPacketLossPercent() {
            return networkPacketLossPercent;
        }

        boolean logAnomalyHint() {
            return logAnomalyHint;
        }

        boolean dockerIssueHint() {
            return dockerIssueHint;
        }

        boolean zombieProcessHint() {
            return zombieProcessHint;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("maxDiskUsePercent", maxDiskUsePercent);
            m.put("cpuUsagePercent", cpuUsagePercent);
            m.put("memUsagePercent", memUsagePercent);
            m.put("hasFailedUnits", hasFailedUnits);
            m.put("failedUnitNames", failedUnitNames);
            m.put("unreachablePorts", unreachablePorts);
            m.put("networkPacketLossPercent", networkPacketLossPercent);
            m.put("logAnomalyHint", logAnomalyHint);
            m.put("dockerIssueHint", dockerIssueHint);
            m.put("zombieProcessHint", zombieProcessHint);
            m.put("toolsInvoked", raw.keySet());
            return m;
        }

        String toMarkdownSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("| 维度 | 摘要 |\n| --- | --- |\n");
            sb.append("| 负载 | CPU ").append(String.format(Locale.ROOT, "%.1f", cpuUsagePercent))
                    .append("% / 内存 ").append(String.format(Locale.ROOT, "%.1f", memUsagePercent)).append("% |\n");
            sb.append("| 磁盘 | 最高使用率约 ").append(String.format(Locale.ROOT, "%.0f", maxDiskUsePercent)).append("% |\n");
            sb.append("| 服务 | ").append(hasFailedUnits
                    ? "存在失败/停止单元" + (failedUnitNames.isEmpty() ? "" : "：" + String.join(", ", failedUnitNames))
                    : "未发现 failed 单元").append(" |\n");
            sb.append("| 网络 | ping 丢包约 ").append(String.format(Locale.ROOT, "%.0f", networkPacketLossPercent)).append("% |\n");
            sb.append("| 端口 | ").append(unreachablePorts.isEmpty() ? "监测端口均可达"
                    : "不可达: " + unreachablePorts).append(" |\n");
            sb.append("| 日志 | ").append(logAnomalyHint ? "含 error/异常线索" : "未见明显 error 聚集").append(" |\n");
            sb.append("| Docker | ").append(dockerIssueHint ? "存在非健康容器" : "正常或未安装").append(" |\n");
            sb.append("| 僵尸进程 | ").append(zombieProcessHint ? "检测到 Z 状态" : "未检测到").append(" |\n");
            sb.append("| 工具 | 并行调用 **").append(raw.size()).append("** 项 MCP 采集 |\n");
            return sb.toString();
        }
    }
}
