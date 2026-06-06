package com.award.log.controller;

import com.award.log.config.SystemConfigRuntimeState;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.mcp.McpToolRegistry;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.tools.*;
import com.award.log.agent.awm.FailureInsightService;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.HighRiskCommandDetector;
import com.award.log.security.IntentRiskFilter;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.OpsSecurityRejectedException;
import com.award.log.security.PromptInjectionGuard;
import com.award.log.security.ReadOnlySurfaceDenylist;
import com.award.log.security.RiskLevel;
import com.award.log.security.ToolSurfaceResolver;
import com.award.log.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private IntentRiskFilter intentRiskFilter;

    @Autowired
    private PromptInjectionGuard promptInjectionGuard;

    @Autowired
    private HighRiskCommandDetector highRiskCommandDetector;

    @Autowired
    private MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    private TraceService traceService;

    @Autowired
    private HttpAuditSubject httpAuditSubject;

    @Autowired
    private OpsPathPolicy opsPathPolicy;

    @Autowired
    private DiskTool diskTool;

    @Autowired
    private ProcessTool processTool;

    @Autowired
    private SystemLoadTool systemLoadTool;

    @Autowired
    private CleanTempTool cleanTempTool;

    @Autowired
    private ConfigCheckTool configCheckTool;

    @Autowired
    private NetworkTool networkTool;

    @Autowired
    private PrivilegeTool privilegeTool;

    @Autowired
    private LogAnalysisTool logAnalysisTool;

    @Autowired
    private OsInsightTool osInsightTool;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private CollaborativeDecisionService collaborativeDecisionService;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private McpToolRegistry mcpToolRegistry;

    @Autowired
    private ToolSurfaceResolver toolSurfaceResolver;

    @Autowired
    private ReadOnlySurfaceDenylist readOnlySurfaceDenylist;

    @Autowired(required = false)
    private FailureInsightService failureInsightService;

    @Autowired
    private SystemConfigRuntimeState systemConfigRuntimeState;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        Object messageObj = request != null ? request.get("message") : null;
        String rawMessage = messageObj == null ? "" : String.valueOf(messageObj).trim();
        String userInput = McpInvocationSecurityGate.enforceChatMessageLimit(rawMessage, 32_000);
        Object agentFlagObj = request != null ? request.get("useAiAgent") : null;
        String useAiAgent = agentFlagObj == null ? "false" : String.valueOf(agentFlagObj).trim();
        String traceId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        RiskLevel riskLevel = RiskLevel.LOW;
        String toolName = "NONE";
        String resultSummary = "";
        boolean success = true;

        log.info("接收到用户输入: {}, useAiAgent: {}, traceId: {}", userInput, useAiAgent, traceId);

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.step("receive", userInput));

        try {
            // 1. 抗注入检测
            if (promptInjectionGuard.isInjection(userInput)) {
                riskLevel = RiskLevel.HIGH;
                toolName = "REJECTED_INJECTION";
                resultSummary = "检测到注入攻击";
                log.warn("检测到注入攻击，已拦截: {}", userInput);
                steps.add(TraceService.step("security", "REJECT_INJECTION"));
                recordAudit(traceId, userInput, riskLevel, "REJECT_INJECTION", toolName, false, resultSummary, startTime, steps);
                captureFailureInsight(userInput, "REJECT_INJECTION", toolName, resultSummary, traceId);
                return buildResponse("检测到注入攻击，已拦截", traceId, riskLevel, false);
            }

            // 2. 意图风险评估
            riskLevel = intentRiskFilter.evaluate(userInput);
            if (riskLevel == RiskLevel.HIGH) {
                toolName = "REJECTED_HIGH_RISK";
                resultSummary = "高风险意图";
                log.warn("高风险意图，已拦截: {}", userInput);
                steps.add(TraceService.step("security", "REJECT_HIGH_INTENT"));
                recordAudit(traceId, userInput, riskLevel, "REJECT_HIGH_INTENT", toolName, false, resultSummary, startTime, steps);
                captureFailureInsight(userInput, "REJECT_HIGH_INTENT", toolName, resultSummary, traceId);
                return buildResponse("检测到高风险操作，需要人工确认", traceId, riskLevel, false);
            }

            // 3. 高危指令检测
            if (highRiskCommandDetector.isHighRiskCommand(userInput)) {
                riskLevel = RiskLevel.HIGH;
                toolName = "REJECTED_HIGH_RISK_COMMAND";
                resultSummary = "高危命令";
                log.warn("高危命令，已拦截: {}", userInput);
                steps.add(TraceService.step("security", "REJECT_HIGH_RISK_COMMAND"));
                recordAudit(traceId, userInput, riskLevel, "REJECT_HIGH_RISK_COMMAND", toolName, false, resultSummary, startTime, steps);
                captureFailureInsight(userInput, "REJECT_HIGH_RISK_COMMAND", toolName, resultSummary, traceId);
                return buildResponse("检测到高危命令，已拦截", traceId, riskLevel, false);
            }

            steps.add(TraceService.step("security", "PASS"));

            // 4. 选择并执行 Tool
            ToolExecutionResult executionResult;
            if ("true".equalsIgnoreCase(useAiAgent) && chatClientBuilder != null) {
                executionResult = executeToolWithAiAgent(userInput, traceId);
            } else {
                executionResult = executeTool(userInput, traceId);
            }
            toolName = executionResult.getToolName();
            String response = executionResult.getResponse();
            resultSummary = response;
            success = !(toolName != null && toolName.startsWith("REJECTED"));

            steps.add(TraceService.step("execute", toolName));
            steps.add(TraceService.step("result", success ? "OK" : "FAIL"));
            String securityOutcome = success ? "PASS"
                    : (toolName != null && toolName.startsWith("REJECTED") ? toolName : "FAIL");
            recordAudit(traceId, userInput, riskLevel, securityOutcome, toolName, success, resultSummary, startTime, steps);
            return buildResponse(response, traceId, riskLevel, success);

        } catch (Exception e) {
            log.error("处理用户请求异常: {}", e.getMessage(), e);
            resultSummary = "处理异常: " + e.getMessage();
            success = false;
            steps.add(TraceService.step("error", e.getMessage()));
            recordAudit(traceId, userInput, riskLevel, "ERROR", toolName, false, resultSummary, startTime, steps);
            return buildResponse("处理请求时发生异常，请稍后重试", traceId, riskLevel, false);
        }
    }

    private ToolExecutionResult executeToolWithAiAgent(String userInput, String traceId) {
        try {
            log.info("使用 AI Agent 处理用户输入: {}", userInput);
            
            if (chatClientBuilder == null) {
                log.warn("ChatClient.Builder 不可用，回退到关键词路由");
                return executeTool(userInput, traceId);
            }
            
            ChatClient chatClient = chatClientBuilder.build();
            
            String systemPrompt = """
                    ## 角色
                    你是面向 **生产级 heterogenous fleet**（GNU/Linux·含麒麟 / Windows Server）的 **Tool-Augmented SRE Copilot**。准则：**Tools-first for facts**——凡涉及主机状态、日志、网络、清理与配置校验，必须先经由已注册工具采集观测数据，再据此推理。
                    
                    ## 工具调用与答复契约
                    - 工具输出（JSON 或结构化字段）须在答复 **首屏摘要**：关键指标名称 + 数值 + 单位/语义；禁止仅以「已执行完毕」「请看 JSON」敷衍收尾。
                    - 对工具失败或策略拦截：复述 **错误类别**（权限 / 路径策略 / 超时 / 参数），给出 **下一步穷举式排查项**（≤5 条）。
                    - 当用户意图已通过 HTTP 风控且本会话为 **完整工具面** 时：可调用 `CleanTempTool`、`LogCleanupTool`、`ServiceRestartTool` 等治理类工具，**必须先 Dry-Run/预览**（若工具支持）、严格遵守 `OpsPathPolicy` 白名单，并在答复中写明 **影响范围与回滚**。
                    - Linux 典型链路：`df`/`findmnt`、`du`、journald、`ss`/`ip`、`lsof`；Windows：`Get-CimInstance`、`Get-WinEvent`、`Get-NetTCPConnection`、`Test-NetConnection` 等。**禁止**在未经核实宿主 OS 的情况下假定 shell 方言。
                    
                    ## 输出形态（Markdown）
                    使用 `##` `###`、列表与代码围栏；高危操作建议单列 **Verification / Rollback** 小节。
                    
                    ## 工具覆盖域（示意）
                    磁盘容量与热点、进程与资源、系统负载、日志文件解析、临时目录治理、配置静态检查、网络探测、权限探测、OS 深度洞察（journal / socket / fd）。
                    """;
            
            RiskLevel surfaceRisk = intentRiskFilter.evaluate(userInput);
            OpsSecurityContext.open(traceId, userInput, false,
                    toolSurfaceResolver.resolve(userInput, surfaceRisk));
            try {
                org.springframework.ai.tool.ToolCallback[] toolCallbacks = mcpToolRegistry.getToolCallbacksForSurface(
                        toolSurfaceResolver.resolve(userInput, surfaceRisk), readOnlySurfaceDenylist);
                var promptSpec = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userInput);
                if (toolCallbacks.length > 0) {
                    promptSpec = promptSpec.toolCallbacks(toolCallbacks);
                }
                String response = promptSpec.call().content();
                log.info("AI Agent 处理完成: {}", response);
                return new ToolExecutionResult("AI_AGENT", response);
            } finally {
                OpsSecurityContext.clear();
            }
        } catch (OpsSecurityRejectedException e) {
            log.warn("AI Agent 工具层安全拦截: {}", e.getMessage());
            return new ToolExecutionResult("REJECTED_TOOL_SECURITY", e.getMessage());
        } catch (Exception e) {
            log.error("AI Agent 执行异常，回退到关键词路由: {}", e.getMessage(), e);
            Throwable c = e.getCause();
            while (c != null) {
                if (c instanceof OpsSecurityRejectedException ore) {
                    return new ToolExecutionResult("REJECTED_TOOL_SECURITY", ore.getMessage());
                }
                c = c.getCause();
            }
            return executeTool(userInput, traceId);
        }
    }

    private ToolExecutionResult executeTool(String userInput, String traceId) {
        String lowerInput = userInput.toLowerCase();

        OpsSecurityContext.open(traceId, userInput, true);
        try {
            // 检查当前执行用户（MinPrivilegeExecutor 集成）
            String currentUser = minPrivilegeExecutor.getRunAsUser();
            log.info("当前执行用户: {}", currentUser);

            if (matchesDiskInsightIntent(lowerInput)) {
                String root = extractDiskInsightRoot(userInput);
                log.info("调用 DiskTool.rankDiskUsageUnderPath({})", root);
                String result = diskTool.rankDiskUsageUnderPath(root, 2, 15);
                String response = parseDiskInsightResult(result);
                return new ToolExecutionResult("DiskInsightTool", response);
            }

            if (lowerInput.contains("磁盘") || lowerInput.contains("df") || lowerInput.contains("空间") || lowerInput.contains("使用率")) {
                log.info("调用 DiskTool.checkDiskUsage()");
                String result = diskTool.checkDiskUsage();
                String response = parseDiskResult(result);
                return new ToolExecutionResult("DiskTool", response);
            }

            if (lowerInput.contains("进程") || lowerInput.contains("僵尸") || lowerInput.contains("cpu") || lowerInput.contains("内存")) {
                log.info("调用 ProcessTool.listProcesses()");
                String result = processTool.listProcesses(5.0, 5.0);
                String response = parseProcessResult(result);
                return new ToolExecutionResult("ProcessTool", response);
            }

            if (lowerInput.contains("负载") || lowerInput.contains("系统状态") || lowerInput.contains("uptime")) {
                log.info("调用 SystemLoadTool.checkSystemLoad()");
                String result = systemLoadTool.checkSystemLoad();
                String response = parseSystemLoadResult(result);
                return new ToolExecutionResult("SystemLoadTool", response);
            }

            if (lowerInput.contains("清理临时") || lowerInput.contains("清理垃圾") || lowerInput.contains("dry-run")) {
                log.info("调用 CleanTempTool.cleanTempFiles()");
                String result = cleanTempTool.cleanTempFiles("/tmp", 7, true, null);
                String response = parseCleanTempResult(result);
                return new ToolExecutionResult("CleanTempTool", response);
            }

            if (lowerInput.contains("配置检查") || lowerInput.contains("nginx") || lowerInput.contains("sshd")) {
                log.info("调用 ConfigCheckTool.checkConfig()");
                String configPath = "/etc/nginx/nginx.conf";
                if (lowerInput.contains("sshd")) {
                    configPath = "/etc/ssh/sshd_config";
                }
                String result = configCheckTool.checkConfig(configPath);
                String response = parseConfigCheckResult(result, configPath);
                return new ToolExecutionResult("ConfigCheckTool", response);
            }

            if (lowerInput.contains("网络") || lowerInput.contains("ping")) {
                log.info("调用 NetworkTool.diagnoseNetwork()");
                String target = extractIp(lowerInput);
                if (target == null || target.isBlank()) {
                    target = systemConfigRuntimeState.getPingTarget();
                }
                String result = networkTool.diagnoseNetwork(target, "ping", 4);
                String response = parseNetworkResult(result, target);
                return new ToolExecutionResult("NetworkTool", response);
            }

            if (lowerInput.contains("权限") || lowerInput.contains("检查权限")) {
                log.info("调用 PrivilegeTool.checkPrivilege()");
                String resource = extractResource(lowerInput);
                if (resource == null) resource = "/etc/passwd";
                String result = privilegeTool.checkPrivilege(resource, "execute");
                String response = parsePrivilegeResult(result, resource);
                return new ToolExecutionResult("PrivilegeTool", response);
            }

            if (lowerInput.contains("lsof")
                    || (lowerInput.contains("打开") && lowerInput.contains("文件") && lowerInput.contains("进程"))) {
                log.info("调用 OsInsightTool (lsof)");
                int pid = extractPidFromInput(userInput);
                if (pid < 1) {
                    pid = 1;
                }
                String result = osInsightTool.executeGateway("lsof", null, null, pid);
                return new ToolExecutionResult("OsInsightTool", parseOsInsightPlainResult(result));
            }

            if (lowerInput.contains("监听端口")
                    || lowerInput.contains("网络套接字")
                    || (lowerInput.contains("套接字") && lowerInput.contains("监听"))) {
                log.info("调用 OsInsightTool (ss)");
                String result = osInsightTool.executeGateway("ss", null, null, null);
                return new ToolExecutionResult("OsInsightTool", parseOsInsightPlainResult(result));
            }

            if (lowerInput.contains("journalctl")
                    || (lowerInput.contains("systemd") && lowerInput.contains("日志"))
                    || lowerInput.contains("内核日志")) {
                log.info("调用 OsInsightTool (journal)");
                String result = osInsightTool.executeGateway("journal", 30, 200, null);
                return new ToolExecutionResult("OsInsightTool", parseOsInsightPlainResult(result));
            }

            if (lowerInput.contains("日志分析") || lowerInput.contains("异常")) {
                log.info("调用 LogAnalysisTool.analyzeLogs()");
                String result = logAnalysisTool.analyzeLogs("/var/log/syslog", 100);
                String response = parseLogAnalysisResult(result);
                return new ToolExecutionResult("LogAnalysisTool", response);
            }

            // 默认回复
            return new ToolExecutionResult("NONE", "抱歉，我无法理解您的请求。请尝试询问关于磁盘、磁盘热点、进程、负载、清理临时文件、配置检查、网络、权限、日志分析，或 journalctl / 监听端口 / lsof 等 OS 感知类问题。");
        } catch (Exception e) {
            log.error("执行工具异常: {}", e.getMessage(), e);
            return new ToolExecutionResult("ERROR", "执行工具时发生异常: " + e.getMessage());
        } finally {
            OpsSecurityContext.clear();
        }
    }

    private String extractIp(String input) {
        // 简单的 IP 提取逻辑
        String[] parts = input.split(" ");
        for (String part : parts) {
            if (part.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return part;
            }
        }
        return null;
    }

    private String extractResource(String input) {
        // 简单的资源路径提取逻辑
        String[] parts = input.split(" ");
        for (String part : parts) {
            if (part.startsWith("/")) {
                return part;
            }
        }
        return null;
    }

    /**
     * ToolResult.data 常为 JSON 字符串，解析为 Map/List 供关键词路由展示。
     */
    private Object unwrapToolDataPayload(Object data) {
        if (!(data instanceof String)) {
            return data;
        }
        String s = ((String) data).trim();
        if (s.isEmpty()) {
            return data;
        }
        char c = s.charAt(0);
        if (c != '{' && c != '[') {
            return data;
        }
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception e) {
            return data;
        }
    }

    private int extractPidFromInput(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)pid\\s*[:：]?\\s*(\\d{1,7})")
                .matcher(input);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private String parseOsInsightPlainResult(String result) {
        try {
            Map<?, ?> m = objectMapper.readValue(result, Map.class);
            if (!m.containsKey("tool") && Boolean.TRUE.equals(m.get("success")) && m.get("data") instanceof String) {
                Object inner = unwrapToolDataPayload(m.get("data"));
                if (inner instanceof Map) {
                    m = (Map<?, ?>) inner;
                }
            }
            Object tool = m.get("tool");
            Object cmd = m.get("command");
            Object ok = m.get("success");
            if (Boolean.FALSE.equals(ok)) {
                return "OS 感知失败（" + tool + "）：" + m.get("error");
            }
            Object output = m.get("output");
            String preview = output != null ? output.toString() : "";
            if (preview.length() > 2500) {
                preview = preview.substring(0, 2500) + "\n...[truncated]";
            }
            return "OS 感知 [" + tool + "]\n命令: " + cmd + "\n输出预览:\n" + (preview.isEmpty() ? "(无输出)" : preview);
        } catch (Exception e) {
            log.warn("解析 OsInsight 结果异常: {}", e.getMessage());
            return "OS 感知结果已返回，解析预览失败时可查看原始 JSON。";
        }
    }

    private boolean matchesDiskInsightIntent(String lowerInput) {
        return lowerInput.contains("磁盘热点")
                || lowerInput.contains("大目录")
                || lowerInput.contains("谁占用")
                || lowerInput.contains("空间占用")
                || lowerInput.contains("du ")
                || (lowerInput.contains("磁盘") && lowerInput.contains("占"));
    }

    private String extractDiskInsightRoot(String input) {
        if (input == null) {
            return "/tmp";
        }
        for (String part : input.split("\\s+")) {
            if (part.startsWith("/") && opsPathPolicy.isAllowedDiskInsightRoot(part)) {
                return opsPathPolicy.normalizeUnixPath(part);
            }
        }
        String norm = input.replace('\\', '/');
        if (norm.contains("/var/log")) {
            return "/var/log";
        }
        if (norm.contains("/var/tmp")) {
            return "/var/tmp";
        }
        if (norm.contains("/tmp")) {
            return "/tmp";
        }
        return "/tmp";
    }

    private void captureFailureInsight(
            String userInput,
            String securityCode,
            String toolName,
            String detail,
            String traceId
    ) {
        if (failureInsightService != null) {
            failureInsightService.captureReject(userInput, securityCode, toolName, detail, traceId);
        }
    }

    private void recordAudit(
            String traceId,
            String userInput,
            RiskLevel riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            long startTime,
            List<Map<String, Object>> steps
    ) {
        long duration = System.currentTimeMillis() - startTime;
        String summary = resultSummary == null ? "" : resultSummary;
        if (summary.length() > 2000) {
            summary = summary.substring(0, 2000) + "...";
        }
        traceService.recordFull(
                traceId,
                "CHAT",
                userInput,
                riskLevel.name(),
                securityOutcome,
                toolName,
                executionOk,
                summary,
                steps,
                duration,
                httpAuditSubject.currentOperatorId(),
                opsPathPolicy.getPolicyVersion()
        );
    }

    private Map<String, Object> buildResponse(String reply, String traceId, RiskLevel riskLevel, boolean success) {
        return Map.of(
                "reply", reply,
                "traceId", traceId,
                "riskLevel", riskLevel.name(),
                "success", success
        );
    }

    // 解析工具返回结果为自然语言
    private String parseDiskResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                List<?> diskList = null;
                if (data instanceof List) {
                    diskList = (List<?>) data;
                } else if (data instanceof String s && !s.isBlank()) {
                    diskList = objectMapper.readValue(s.trim(), List.class);
                }
                if (diskList != null) {
                    StringBuilder response = new StringBuilder();
                    List<String> highUsageDisks = new ArrayList<>();
                    
                    for (Object item : diskList) {
                        if (item instanceof Map) {
                            Map<?, ?> diskInfo = (Map<?, ?>) item;
                            String filesystem = (String) diskInfo.get("filesystem");
                            String usePercentStr = (String) diskInfo.get("usePercent");
                            String mountedOn = (String) diskInfo.get("mountedOn");
                            
                            if (filesystem != null && usePercentStr != null && mountedOn != null) {
                                try {
                                    int usePercent = Integer.parseInt(usePercentStr.replace("%", ""));
                                    if (usePercent > 80) {
                                        highUsageDisks.add(mountedOn + " 使用了 " + usePercentStr);
                                    }
                                } catch (NumberFormatException e) {
                                    log.warn("解析磁盘使用率失败: {}", usePercentStr);
                                }
                            }
                        }
                    }
                    
                    if (highUsageDisks.isEmpty()) {
                        response.append("所有磁盘分区使用率都在80%以下，状态良好。");
                    } else {
                        response.append("磁盘使用情况：\n");
                        for (String disk : highUsageDisks) {
                            response.append(disk).append("，建议清理。\n");
                        }
                    }
                    return response.toString();
                }
            } else {
                return "磁盘检查失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析磁盘结果异常: {}", e.getMessage());
        }
        return "磁盘使用情况已查询，详细信息请查看系统输出。";
    }

    private String parseDiskInsightResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (!Boolean.TRUE.equals(toolResult.get("success"))) {
                return "磁盘热点扫描失败: " + toolResult.get("error");
            }
            Object rawData = toolResult.get("data");
            String innerJson = rawData instanceof String ? (String) rawData : objectMapper.writeValueAsString(rawData);
            Map<?, ?> payload = objectMapper.readValue(innerJson, Map.class);
            Object entriesObj = payload.get("entries");
            if (!(entriesObj instanceof List)) {
                return "未解析到目录占用数据（可能目录为空或无权限）。";
            }
            List<?> entries = (List<?>) entriesObj;
            if (entries.isEmpty()) {
                return "未解析到目录占用数据（可能目录为空或无权限）。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("目录 ").append(payload.get("root")).append(" 下占用排序（du，前 ")
                    .append(entries.size()).append(" 条）：\n");
            int i = 1;
            for (Object o : entries) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) o;
                Object kbObj = row.get("kb");
                String path = String.valueOf(row.get("path"));
                long kb = kbObj instanceof Number ? ((Number) kbObj).longValue() : 0L;
                sb.append(i++).append(". ").append(formatDuKb(kb)).append(" — ").append(path).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("解析磁盘热点结果异常", e);
            return "磁盘热点扫描已完成，详情见原始输出。";
        }
    }

    private static String formatDuKb(long kb) {
        if (kb >= 1048576L) {
            return String.format("%.2f GB", kb / 1048576.0);
        }
        if (kb >= 1024L) {
            return String.format("%.2f MB", kb / 1024.0);
        }
        return kb + " KB";
    }

    private String parseProcessResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof List) {
                    List<?> processList = (List<?>) data;
                    int count = processList.size();
                    StringBuilder response = new StringBuilder("系统进程情况：\n");
                    response.append("共找到 " + count + " 个符合条件的进程\n");
                    
                    if (count > 0) {
                        response.append("前3个CPU使用率最高的进程：\n");
                        // 按CPU使用率排序
                        List<Map<?, ?>> sortedProcesses = new ArrayList<>();
                        for (Object item : processList) {
                            if (item instanceof Map) {
                                sortedProcesses.add((Map<?, ?>) item);
                            }
                        }
                        
                        sortedProcesses.sort((p1, p2) -> {
                            double cpu1 = Double.parseDouble(p1.get("cpu").toString());
                            double cpu2 = Double.parseDouble(p2.get("cpu").toString());
                            return Double.compare(cpu2, cpu1);
                        });
                        
                        int limit = Math.min(3, sortedProcesses.size());
                        for (int i = 0; i < limit; i++) {
                            Map<?, ?> processInfo = sortedProcesses.get(i);
                            String pid = processInfo.get("pid").toString();
                            String cpu = processInfo.get("cpu").toString();
                            String command = processInfo.get("command").toString();
                            response.append("进程名: " + command + ", PID: " + pid + ", CPU: " + cpu + "%\n");
                        }
                    }
                    return response.toString();
                }
            } else {
                return "进程查询失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析进程结果异常: {}", e.getMessage());
        }
        return "进程列表已查询，CPU/内存使用率超过5%的进程已筛选。";
    }

    private String parseSystemLoadResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> loadInfo = (Map<?, ?>) data;
                    StringBuilder response = new StringBuilder("系统负载状态：\n");
                    
                    Object cpuUsage = loadInfo.get("cpuUsagePercent");
                    Object memUsage = loadInfo.get("memUsagePercent");
                    Object loadAvg1min = loadInfo.get("loadAvg1min");
                    
                    if (cpuUsage != null) {
                        response.append("CPU使用率：" + cpuUsage + "%\n");
                    }
                    if (memUsage != null) {
                        response.append("内存使用率：" + memUsage + "%\n");
                    }
                    if (loadAvg1min != null) {
                        response.append("1分钟平均负载：" + loadAvg1min + "\n");
                    }
                    
                    return response.toString();
                }
            } else {
                return "系统负载查询失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析系统负载结果异常: {}", e.getMessage());
        }
        return "系统负载状态已查询，包括CPU、内存和负载信息。";
    }

    private String parseCleanTempResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> cleanInfo = (Map<?, ?>) data;
                    Boolean dryRun = (Boolean) cleanInfo.get("dryRun");
                    Integer filesToDelete = (Integer) cleanInfo.get("filesToDelete");
                    Long totalSize = (Long) cleanInfo.get("totalSize");
                    Integer filesDeleted = (Integer) cleanInfo.get("filesDeleted");
                    
                    if (dryRun != null) {
                        if (dryRun) {
                            if (filesToDelete != null && totalSize != null) {
                                return "临时文件清理预览：\n" +
                                       "将删除 " + filesToDelete + " 个文件\n" +
                                       "释放空间：" + (totalSize / 1024) + " KB\n" +
                                       "未实际删除文件（dry-run模式）";
                            }
                        } else {
                            if (filesDeleted != null) {
                                return "临时文件清理完成：\n" +
                                       "实际删除了 " + filesDeleted + " 个文件\n" +
                                       "空间清理完成";
                            }
                        }
                    }
                }
            } else {
                return "临时文件清理失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析清理结果异常: {}", e.getMessage());
        }
        return "临时文件清理完成。";
    }

    private String parseConfigCheckResult(String result, String configPath) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> configInfo = (Map<?, ?>) data;
                    Boolean valid = (Boolean) configInfo.get("valid");
                    if (valid == null) {
                        valid = (Boolean) configInfo.get("passed");
                    }
                    String errorSummary = (String) configInfo.get("errorSummary");
                    if (errorSummary == null) {
                        errorSummary = (String) configInfo.get("message");
                    }
                    if (valid != null) {
                        if (valid) {
                            return "配置文件 " + configPath + " 语法检查通过，没有发现错误。";
                        } else {
                            return "配置文件 " + configPath + " 语法检查失败\n" +
                                   "错误摘要：" + (errorSummary != null ? errorSummary : "未知错误");
                        }
                    }
                }
            } else {
                return "配置文件检查失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析配置检查结果异常: {}", e.getMessage());
        }
        return "配置文件 " + configPath + " 语法检查完成。";
    }

    private String parseNetworkResult(String result, String target) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> networkInfo = (Map<?, ?>) data;
                    String type = (String) networkInfo.get("type");
                    
                    if ("ping".equals(type)) {
                        Boolean reachable = (Boolean) networkInfo.get("reachable");
                        Integer packetLoss = (Integer) networkInfo.get("packetLoss");
                        Double avgResponseTime = (Double) networkInfo.get("avgResponseTime");
                        
                        if (reachable != null) {
                            if (reachable) {
                                return "网络诊断结果：\n" +
                                       "目标 " + target + " 可达\n" +
                                       "丢包率：" + packetLoss + "%\n" +
                                       "平均延迟：" + avgResponseTime + " ms";
                            } else {
                                return "网络诊断结果：\n" +
                                       "目标 " + target + " 不可达\n" +
                                       "丢包率：" + packetLoss + "%";
                            }
                        }
                    } else if ("traceroute".equals(type)) {
                        List<?> hops = (List<?>) networkInfo.get("hops");
                        if (hops != null) {
                            return "网络诊断结果：\n" +
                                   "目标 " + target + " 的路由跳数：" + hops.size() + "\n" +
                                   "路由追踪完成";
                        }
                    }
                }
            } else {
                return "网络诊断失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析网络诊断结果异常: {}", e.getMessage());
        }
        return "网络诊断完成，目标 " + target + " 的连通性已测试。";
    }

    private String parsePrivilegeResult(String result, String resource) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> privilegeInfo = (Map<?, ?>) data;
                    Boolean hasPermission = (Boolean) privilegeInfo.get("hasPermission");
                    if (hasPermission == null) {
                        hasPermission = (Boolean) privilegeInfo.get("hasPrivilege");
                    }
                    String permissionType = (String) privilegeInfo.get("permissionType");
                    if (permissionType == null) {
                        permissionType = (String) privilegeInfo.get("action");
                    }
                    if (hasPermission != null) {
                        String label = permissionType != null ? permissionType : "目标";
                        if (hasPermission) {
                            return "权限检查结果：\n" +
                                   "资源 " + resource + " 具有 " + label + " 权限";
                        } else {
                            return "权限检查结果：\n" +
                                   "资源 " + resource + " 缺少 " + label + " 权限\n" +
                                   "建议：使用 sudo 命令提升权限，或联系系统管理员获取相应权限";
                        }
                    }
                }
            } else {
                return "权限检查失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析权限检查结果异常: {}", e.getMessage());
        }
        return "权限检查完成，资源 " + resource + " 的执行权限已验证。";
    }

    private String parseLogAnalysisResult(String result) {
        try {
            Map<?, ?> toolResult = objectMapper.readValue(result, Map.class);
            if (Boolean.TRUE.equals(toolResult.get("success"))) {
                Object data = unwrapToolDataPayload(toolResult.get("data"));
                if (data instanceof Map) {
                    Map<?, ?> logInfo = (Map<?, ?>) data;
                    Integer errorCount = (Integer) logInfo.get("errorCount");
                    String rootCause = (String) logInfo.get("rootCause");
                    String recommendation = (String) logInfo.get("recommendation");
                    
                    StringBuilder response = new StringBuilder("日志分析结果：\n");
                    response.append("检测到 " + errorCount + " 个异常\n");
                    
                    if (rootCause != null) {
                        response.append("根因分析：" + rootCause + "\n");
                    }
                    
                    if (recommendation != null) {
                        response.append("修复建议：\n" + recommendation);
                    }
                    
                    return response.toString();
                }
            } else {
                return "日志分析失败: " + toolResult.get("error");
            }
        } catch (Exception e) {
            log.error("解析日志分析结果异常: {}", e.getMessage());
        }
        return "日志分析完成，已检测系统日志中的异常。";
    }

    // 工具执行结果类，解决并发问题
    private static class ToolExecutionResult {
        private final String toolName;
        private final String response;

        public ToolExecutionResult(String toolName, String response) {
            this.toolName = toolName;
            this.response = response;
        }

        public String getToolName() {
            return toolName;
        }

        public String getResponse() {
            return response;
        }
    }
}
