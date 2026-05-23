package com.award.log.agent;

import com.award.log.config.AgentOpsProperties;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsTrustPolicy;
import com.award.log.security.OpsTrustTier;
import com.award.log.security.RiskLevel;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 工作台运维闭环：感知 → 预览 →（策略内）执行 → 验证，减少「空确认」、提高真实可运维性。
 */
@Slf4j
@Service
public class AssistantOrchestrator {

    private final OpsIntentRouter opsIntentRouter;

    private final DiskTool diskTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final LogCleanupTool logCleanupTool;
    private final CleanTempTool cleanTempTool;
    private final SystemLoadTool systemLoadTool;
    private final ServiceRestartTool serviceRestartTool;
    private final OpsTrustPolicy opsTrustPolicy;
    private final AgenticRiskScoreEngine agenticRiskScoreEngine;
    private final AssistantAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final AgentOpsProperties agentOpsProperties;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    public AssistantOrchestrator(
            DiskTool diskTool,
            DiskAnalyzeTool diskAnalyzeTool,
            LogCleanupTool logCleanupTool,
            CleanTempTool cleanTempTool,
            SystemLoadTool systemLoadTool,
            ServiceRestartTool serviceRestartTool,
            OpsTrustPolicy opsTrustPolicy,
            AgenticRiskScoreEngine agenticRiskScoreEngine,
            AssistantAuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            AgentOpsProperties agentOpsProperties,
            OpsIntentRouter opsIntentRouter) {
        this.opsIntentRouter = opsIntentRouter;
        this.diskTool = diskTool;
        this.diskAnalyzeTool = diskAnalyzeTool;
        this.logCleanupTool = logCleanupTool;
        this.cleanTempTool = cleanTempTool;
        this.systemLoadTool = systemLoadTool;
        this.serviceRestartTool = serviceRestartTool;
        this.opsTrustPolicy = opsTrustPolicy;
        this.agenticRiskScoreEngine = agenticRiskScoreEngine;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.agentOpsProperties = agentOpsProperties;
    }

    @Value("${agent.assistant.orchestrator.log-clean-days:30}")
    private int logCleanDays;

    @Value("${agent.assistant.orchestrator.temp-clean-days:7}")
    private int tempCleanDays;

    @Value("${agent.assistant.orchestrator.disk-pressure-percent:80}")
    private double diskPressurePercent;

    public record RunResult(String markdown, String traceId) {}

    public boolean supports(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        OpsIntentRouter.Playbook pb = opsIntentRouter.resolve(userMessage);
        return pb == OpsIntentRouter.Playbook.DISK_CLEANUP
                || pb == OpsIntentRouter.Playbook.CPU_PRESSURE
                || pb == OpsIntentRouter.Playbook.PATROL_CONTINUATION;
    }

    public RunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolve(userMessage);
        if (playbook == OpsIntentRouter.Playbook.PATROL_CONTINUATION) {
            return runPatrolContinuation(userMessage, surface, intentRisk);
        }
        if (playbook == OpsIntentRouter.Playbook.CPU_PRESSURE) {
            return runCpuPressure(userMessage, surface, intentRisk);
        }
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            String md = buildReadOnlyReply(traceId);
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "READ_ONLY_SURFACE",
                    "AssistantOrchestrator", false, "只读工具面", steps, System.currentTimeMillis() - t0);
            return new RunResult(md, traceId);
        }

        boolean forceExecute = opsIntentRouter.forceRemediate(userMessage);
        StringBuilder md = new StringBuilder();
        md.append("> **traceId:** `").append(traceId).append("`\n\n");
        md.append("## 运维闭环报告\n\n");

        try {
            auditRecorder.addCot(steps, 2, "感知", "采集磁盘与热点目录");
            String diskOverview = diskTool.checkDiskUsage();
            String analyzeJson = diskAnalyzeTool.analyzeDiskPressure(
                    defaultLogRoot(), true, 12);
            auditRecorder.addStep(steps, "perceive", truncate(diskOverview, 800));
            auditRecorder.addStep(steps, "perceive", truncate(analyzeJson, 800));

            md.append("### 1. 环境感知\n\n");
            md.append(summarizeDisk(diskOverview)).append("\n\n");
            md.append("热点扫描已执行（`DiskAnalyzeTool`），详见工具原始 JSON。\n\n");

            double maxUse = extractMaxDiskUsePercent(diskOverview);
            boolean needTemp = maxUse >= diskPressurePercent || mentionsTemp(userMessage);
            boolean needLog = mentionsLog(userMessage) || maxUse >= diskPressurePercent;

            if (!needTemp && !needLog) {
                needLog = true;
            }

            boolean anyExecuted = false;

            if (needTemp) {
                anyExecuted |= appendTempCleanupSection(md, steps, forceExecute);
            }
            if (needLog) {
                anyExecuted |= appendLogCleanupSection(md, steps, forceExecute);
            }

            auditRecorder.addCot(steps, 4, "验证", "再次采集 df");
            String verifyDisk = diskTool.checkDiskUsage();
            auditRecorder.addStep(steps, "verify", truncate(verifyDisk, 600));
            md.append("### 验证\n\n");
            md.append(summarizeDisk(verifyDisk)).append("\n\n");

            if (!anyExecuted) {
                md.append("---\n\n");
                md.append("当前为 **预览模式** 或风险分需人工跟进。若预览结果符合预期，请回复：**执行清理** 或 **按预览执行**。\n");
            } else {
                md.append("---\n\n");
                md.append("已在策略允许范围内完成写操作；完整步骤见运维链路审计（traceId 如上）。\n");
            }

            auditRecorder.record(traceId, userMessage, intentRisk.name(), anyExecuted ? "EXECUTED" : "PREVIEW",
                    "AssistantOrchestrator", true, md.substring(0, Math.min(500, md.length())), steps,
                    System.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);

        } catch (Exception e) {
            log.error("编排器执行失败 traceId={}", traceId, e);
            auditRecorder.addStep(steps, "error", e.getMessage());
            md.append("\n\n**编排失败:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "AssistantOrchestrator", false, e.getMessage(), steps, System.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);
        }
    }

    private boolean appendLogCleanupSection(StringBuilder md, List<Map<String, Object>> steps, boolean forceExecute)
            throws Exception {
        String path = defaultLogRoot();
        Map<String, Object> params = Map.of(
                "path", path,
                "days", logCleanDays,
                "dryRun", true
        );
        String instruction = "执行 LogCleanupTool 参数: path=" + path + " days=" + logCleanDays + " dryRun=true";
        OpsTrustTier tier = opsTrustPolicy.tierForTool("LogCleanupTool", params, instruction);

        String preview = logCleanupTool.cleanupOldLogs(path, logCleanDays, true, false);
        JsonNode data = parseToolData(preview);
        int found = deletableCount(data);
        int protectedSkip = data != null && data.has("protectedSkipped") ? data.get("protectedSkipped").asInt(0) : 0;

        auditRecorder.addCot(steps, 3, "推理", "日志清理预览 filesFound=" + found + " tier=" + tier);
        md.append("### 2. 日志清理（").append(path).append("）\n\n");
        md.append("- 预览候选：**").append(found).append("** 个；保护规则跳过：**").append(protectedSkip).append("**\n");
        md.append("- ").append(opsTrustPolicy.tierExplanation(tier, riskScore("LogCleanupTool", params, instruction))).append("\n");

        if (found == 0) {
            md.append("- 无符合条件且允许删除的陈旧日志。\n\n");
            return false;
        }

        if (tier == OpsTrustTier.BLOCK) {
            md.append("- **已拒绝执行**（风险分过高）。\n\n");
            return false;
        }

        boolean shouldRun = forceExecute || tier == OpsTrustTier.AUTO || tier == OpsTrustTier.NOTIFY;
        if (!shouldRun) {
            md.append("- 已生成预览，等待您确认后再删。\n\n");
            return false;
        }

        String exec = logCleanupTool.cleanupOldLogs(path, logCleanDays, false, true);
        JsonNode execData = parseToolData(exec);
        int deleted = execData != null && execData.has("filesDeleted") ? execData.get("filesDeleted").asInt(0) : 0;
        auditRecorder.addStep(steps, "execute", "LogCleanup deleted=" + deleted);
        md.append("- **已执行删除：** ").append(deleted).append(" 个文件（已排除受保护项）。\n\n");
        return deleted > 0;
    }

    private boolean appendTempCleanupSection(StringBuilder md, List<Map<String, Object>> steps, boolean forceExecute)
            throws Exception {
        String path = OsRuntime.isWindows() ? "C:\\Windows\\Temp" : "/tmp";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        params.put("days", tempCleanDays);
        params.put("dryRun", true);
        String instruction = "执行 CleanTempTool path=" + path + " days=" + tempCleanDays;
        OpsTrustTier tier = opsTrustPolicy.tierForTool("CleanTempTool", params, instruction);

        String preview = cleanTempTool.cleanTempFiles(path, tempCleanDays, true, false);
        JsonNode data = parseToolData(preview);
        int found = data != null && data.has("filesFound") ? data.get("filesFound").asInt(0) : 0;

        md.append("### 临时目录清理（").append(path).append("）\n\n");
        md.append("- 预览：**").append(found).append("** 个文件\n");
        md.append("- ").append(opsTrustPolicy.tierExplanation(tier, riskScore("CleanTempTool", params, instruction))).append("\n");

        if (found == 0 || tier == OpsTrustTier.BLOCK) {
            if (tier == OpsTrustTier.BLOCK) {
                md.append("- **已拒绝执行**。\n\n");
            } else {
                md.append("- 无需清理。\n\n");
            }
            return false;
        }

        boolean shouldRun = forceExecute || tier == OpsTrustTier.AUTO || tier == OpsTrustTier.NOTIFY;
        if (!shouldRun) {
            md.append("- 等待确认。\n\n");
            return false;
        }

        String exec = cleanTempTool.cleanTempFiles(path, tempCleanDays, false, true);
        JsonNode execData = parseToolData(exec);
        int deleted = execData != null && execData.has("filesDeleted") ? execData.get("filesDeleted").asInt(0) : 0;
        auditRecorder.addStep(steps, "execute", "CleanTemp deleted=" + deleted);
        md.append("- **已执行删除：** ").append(deleted).append(" 个临时文件。\n\n");
        return deleted > 0;
    }

    private double riskScore(String toolName, Map<String, Object> params, String instruction) {
        return agenticRiskScoreEngine.score(toolName, params, instruction).total();
    }

    private RunResult runPatrolContinuation(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            return new RunResult(buildReadOnlyReply(traceId), traceId);
        }
        if (opsAutoRemediationService == null) {
            return new RunResult("> **traceId:** `" + traceId + "`\n\n自动修复服务未启用。", traceId);
        }

        Map<String, Object> pending = opsAutoRemediationService.getPendingProposalView();
        if (!Boolean.TRUE.equals(pending.get("hasPending"))) {
            String md = "> **traceId:** `" + traceId + "`\n\n当前 **无待确认的巡检方案**。可说「磁盘/空间/清理」触发新一轮闭环，或等待下次巡检。\n";
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "NO_PENDING",
                    "PatrolContinuation", false, "无待确认", steps, System.currentTimeMillis() - t0);
            return new RunResult(md, traceId);
        }

        String proposalId = String.valueOf(pending.get("proposalId"));
        auditRecorder.addCot(steps, 2, "感知", "加载巡检待确认方案 " + proposalId);
        auditRecorder.addCot(steps, 3, "推理", String.valueOf(pending.getOrDefault("summary", "")));

        Map<String, Object> exec = opsAutoRemediationService.confirmPendingFromAssistant(proposalId);
        boolean ok = Boolean.TRUE.equals(exec.get("success"));
        auditRecorder.addStep(steps, "execute", truncate(String.valueOf(exec), 1200));
        auditRecorder.addCot(steps, 4, "验证", ok ? "巡检方案已执行" : "执行失败");

        StringBuilder md = new StringBuilder();
        md.append("> **traceId:** `").append(traceId).append("`\n\n");
        md.append("## 巡检待办执行\n\n");
        md.append("- 方案 ID：`").append(proposalId).append("`\n");
        md.append("- 摘要：").append(pending.getOrDefault("summary", "")).append("\n");
        md.append("- 结果：**").append(ok ? "成功" : "失败").append("**\n\n");
        if (exec.get("actions") != null) {
            md.append("```json\n").append(truncate(String.valueOf(exec.get("actions")), 2000)).append("\n```\n");
        }
        if (!ok) {
            md.append("\n错误：").append(exec.getOrDefault("error", "未知")).append("\n");
        }

        auditRecorder.record(traceId, userMessage, intentRisk.name(), ok ? "EXECUTED" : "FAILED",
                "PatrolContinuation", ok, md.substring(0, Math.min(400, md.length())), steps,
                System.currentTimeMillis() - t0);
        return new RunResult(md.toString(), traceId);
    }

    private RunResult runCpuPressure(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        long t0 = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            return new RunResult(buildReadOnlyReply(traceId), traceId);
        }

        StringBuilder md = new StringBuilder();
        md.append("> **traceId:** `").append(traceId).append("`\n\n");
        md.append("## CPU / 负载闭环\n\n");

        try {
            String loadJson = systemLoadTool.checkSystemLoad();
            auditRecorder.addStep(steps, "perceive", truncate(loadJson, 800));
            md.append("### 感知\n\n已采集系统负载（`SystemLoadTool`）。\n\n");

            boolean wantRestart = userMessage.contains("重启") || userMessage.contains("restart");
            boolean anyExecuted = false;
            if (wantRestart) {
                String svc = pickRestartCandidate();
                if (svc == null) {
                    md.append("### 服务重启\n\n未配置可重启服务白名单。\n\n");
                } else {
                    anyExecuted |= appendServiceRestartSection(md, steps, svc, true);
                }
            } else {
                md.append("### 建议\n\n");
                md.append("- 若需重启白名单内非关键服务，请说明服务名并带「重启」（如：重启 nginx）。\n");
                md.append("- 允许重启列表见配置 `agent.service-restart.allowlist`。\n\n");
            }

            auditRecorder.record(traceId, userMessage, intentRisk.name(), anyExecuted ? "EXECUTED" : "PREVIEW",
                    "CpuPressure", true, md.substring(0, Math.min(400, md.length())), steps,
                    System.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);
        } catch (Exception e) {
            log.error("CPU 闭环失败", e);
            md.append("**失败:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "CpuPressure", false, e.getMessage(), steps, System.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);
        }
    }

    private String pickRestartCandidate() {
        List<String> list = agentOpsProperties.getServiceRestart().getAllowlist();
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0).trim().toLowerCase(Locale.ROOT);
    }

    private boolean appendServiceRestartSection(
            StringBuilder md, List<Map<String, Object>> steps, String serviceName, boolean forceExecute)
            throws Exception {
        Map<String, Object> params = Map.of("serviceName", serviceName, "dryRun", true);
        String instruction = "执行 ServiceRestartTool serviceName=" + serviceName;
        OpsTrustTier tier = opsTrustPolicy.tierForTool("ServiceRestartTool", params, instruction);

        String preview = serviceRestartTool.restartService(serviceName, true, false);
        md.append("### 服务重启（").append(serviceName).append("）\n\n");
        md.append("- ").append(opsTrustPolicy.tierExplanation(tier, riskScore("ServiceRestartTool", params, instruction))).append("\n");

        if (tier == OpsTrustTier.BLOCK) {
            md.append("- **已拒绝**。\n\n");
            return false;
        }
        boolean shouldRun = forceExecute || tier == OpsTrustTier.AUTO || tier == OpsTrustTier.NOTIFY;
        if (!shouldRun) {
            md.append("- 已预览，回复「执行清理」或明确「重启 ").append(serviceName).append("」后执行。\n\n");
            return false;
        }
        String exec = serviceRestartTool.restartService(serviceName, false, true);
        auditRecorder.addStep(steps, "execute", truncate(exec, 800));
        md.append("- **已执行重启**（请自行验证业务恢复）。\n\n");
        return parseToolSuccess(exec);
    }

    private boolean parseToolSuccess(String toolJson) {
        JsonNode data = parseToolData(toolJson);
        return data != null || (toolJson != null && toolJson.contains("\"success\":true"));
    }

    private String buildReadOnlyReply(String traceId) {
        return "> **traceId:** `" + traceId + "`\n\n"
                + "当前会话为 **只读工具面**，已跳过自动清理。请先降低操作风险表述，或说明仅做磁盘/日志**分析**。\n";
    }

    private static String defaultLogRoot() {
        return OsRuntime.isWindows() ? "C:\\Windows\\Logs" : "/var/log";
    }

    private static boolean mentionsTemp(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("临时") || m.contains("tmp") || m.contains("temp") || m.contains("垃圾");
    }

    private static boolean mentionsLog(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("日志") || m.contains("log");
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
            log.debug("解析工具 JSON 失败: {}", e.getMessage());
            return null;
        }
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
                    max = Math.max(max, parsePercent(use));
                }
                return max;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static double parsePercent(String usage) {
        if (usage == null || !usage.endsWith("%")) {
            return 0;
        }
        try {
            return Double.parseDouble(usage.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String summarizeDisk(String diskToolJson) {
        double max = extractMaxDiskUsePercent(diskToolJson);
        if (max > 0) {
            return "- 分区最高使用率约 **" + String.format(Locale.ROOT, "%.0f", max) + "%**";
        }
        return "- 已采集 `df` 结果（见工具输出）";
    }

    private static int deletableCount(JsonNode data) {
        if (data == null) {
            return 0;
        }
        if (data.has("deletableCount")) {
            return data.get("deletableCount").asInt(0);
        }
        return data.has("filesFound") ? data.get("filesFound").asInt(0) : 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
