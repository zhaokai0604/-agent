package com.award.log.controller;

import com.award.log.mcp.tools.*;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.McpInvocationSecurityGate.McpSecurityProfile;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.trace.TraceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpExecuteController {

    private final DiskTool diskTool;
    private final ProcessTool processTool;
    private final SystemLoadTool systemLoadTool;
    private final LogAnalysisTool logAnalysisTool;
    private final CleanTempTool cleanTempTool;
    private final ConfigCheckTool configCheckTool;
    private final NetworkTool networkTool;
    private final PrivilegeTool privilegeTool;
    private final OsInsightTool osInsightTool;

    private final McpInvocationSecurityGate mcpInvocationSecurityGate;
    private final OpsAuditTraceService opsAuditTraceService;
    private final HttpAuditSubject httpAuditSubject;
    private final OpsPathPolicy opsPathPolicy;

    @GetMapping("/tools")
    public Map<String, Object> getMcpTools() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        
        tools.add(createToolInfo("DiskTool", "磁盘检查工具", "检查磁盘使用情况", "/mcp/execute"));
        tools.add(createToolInfo("DiskInsightTool", "磁盘热点扫描", "du 排序定位大目录（白名单路径）", "/mcp/execute"));
        tools.add(createToolInfo("ProcessTool", "进程管理工具", "查看和管理系统进程", "/mcp/execute"));
        tools.add(createToolInfo("SystemLoadTool", "系统负载工具", "检查系统负载状态", "/mcp/execute"));
        tools.add(createToolInfo("LogAnalysisTool", "日志分析工具", "分析系统日志", "/mcp/execute"));
        tools.add(createToolInfo("CleanTempTool", "文件清理工具", "清理临时文件", "/mcp/execute"));
        tools.add(createToolInfo("ConfigCheckTool", "配置检查工具", "检查配置文件", "/mcp/execute"));
        tools.add(createToolInfo("NetworkTool", "网络诊断工具", "诊断网络连接", "/mcp/execute"));
        tools.add(createToolInfo("PrivilegeTool", "权限验证工具", "验证文件权限", "/mcp/execute"));
        tools.add(createToolInfo("OsInsightTool", "OS 深度感知", "journalctl / ss / lsof", "/mcp/execute"));

        response.put("success", true);
        response.put("data", tools);
        return response;
    }
    
    private Map<String, Object> createToolInfo(String name, String description, String summary, String endpoint) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("summary", summary);
        tool.put("endpoint", endpoint);
        return tool;
    }

    @PostMapping("/execute")
    public Map<String, Object> executeTool(@RequestBody Map<String, Object> request) {
        String toolName = (String) request.get("toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.get("parameters");

        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        log.info("收到工具执行请求: toolName={}", toolName);

        GateDecision decision = mcpInvocationSecurityGate.evaluate(toolName, parameters, McpSecurityProfile.INITIAL_REQUEST);
        String userInstruction = mcpInvocationSecurityGate.buildInstruction(toolName, parameters);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("MCP 安全门拦截: tool={} code={}", toolName, decision.getCode());
            persistGateReject(traceId, userInstruction, toolName, decision, startTime);
            return gateBlockResponse(traceId, startTime, decision);
        }
        if (decision.getType() == GateDecision.Type.NEED_CONFIRM) {
            log.info("MCP 中等风险，需二次确认: tool={}", toolName);
            persistMcpReject(traceId, userInstruction, decision.getRiskLevel(), toolName, "NEED_CONFIRM",
                    decision.getMessage(), startTime);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("needConfirm", true);
            response.put("message", "该操作存在中等风险，请在界面点击确认（等同口令「确认执行」）");
            response.put("riskLevel", decision.getRiskLevel().name());
            response.put("toolName", toolName);
            response.put("parameters", parameters);
            response.put("traceId", traceId);
            response.put("duration", System.currentTimeMillis() - startTime);
            return response;
        }

        RiskLevel evaluatedRisk = decision.getRiskLevel();
        Map<String, Object> execResponse = doExecuteTool(toolName, parameters, startTime, traceId, userInstruction);
        execResponse.put("traceId", traceId);
        persistMcpSuccess(traceId, userInstruction, evaluatedRisk, toolName, execResponse, startTime);
        return execResponse;
    }

    private void persistGateReject(String traceId, String userInstruction, String toolName, GateDecision d, long startTime) {
        persistMcpReject(traceId, userInstruction, RiskLevel.HIGH, toolName, mapGateCodeToOutcome(d.getCode()),
                d.getMessage(), startTime);
    }

    private static String mapGateCodeToOutcome(String code) {
        if (code == null) {
            return "SECURITY_REJECT";
        }
        return switch (code) {
            case "INJECTION" -> "REJECT_INJECTION";
            case "HIGH_INTENT" -> "REJECT_HIGH_INTENT";
            case "HIGH_RISK_COMMAND" -> "REJECT_HIGH_RISK_COMMAND";
            default -> "SECURITY_REJECT";
        };
    }

    private static Map<String, Object> gateBlockResponse(String traceId, long startTime, GateDecision d) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", d.getMessage());
        response.put("securityCode", d.getCode());
        response.put("traceId", traceId);
        response.put("duration", System.currentTimeMillis() - startTime);
        return response;
    }

    private void persistMcpReject(String traceId, String userInstruction, RiskLevel risk, String toolName,
                                  String outcome, String summary, long startTime) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.step("receive", userInstruction));
        steps.add(TraceService.step("security", outcome));
        opsAuditTraceService.save(traceId, "MCP", userInstruction, risk.name(), outcome, toolName, false,
                summary, steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion());
    }

    private void persistMcpSuccess(String traceId, String userInstruction, RiskLevel risk, String toolName,
                                   Map<String, Object> resp, long startTime) {
        boolean ok = Boolean.TRUE.equals(resp.get("success"));
        String summary = ok ? String.valueOf(resp.get("data")) : String.valueOf(resp.get("error"));
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.step("receive", userInstruction));
        steps.add(TraceService.step("security", "PASS"));
        steps.add(TraceService.step("execute", toolName));
        steps.add(TraceService.step("result", ok ? "OK" : "FAIL"));
        opsAuditTraceService.save(traceId, "MCP", userInstruction, risk.name(), "PASS", toolName, ok,
                summary, steps, System.currentTimeMillis() - startTime,
                httpAuditSubject.currentOperatorId(), opsPathPolicy.getPolicyVersion());
    }
    
    @PostMapping("/confirmExecute")
    public Map<String, Object> confirmExecuteTool(@RequestBody Map<String, Object> request) {
        String toolName = (String) request.get("toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.get("parameters");
        String confirmCode = (String) request.get("confirmCode");

        long startTime = System.currentTimeMillis();
        Map<String, Object> response = new HashMap<>();
        String traceId = UUID.randomUUID().toString();

        log.info("收到确认执行请求: toolName={}, confirmCode={}", toolName, confirmCode);

        if (toolName == null || toolName.isBlank()) {
            response.put("success", false);
            response.put("error", "工具名称不能为空");
            response.put("traceId", traceId);
            response.put("duration", System.currentTimeMillis() - startTime);
            return response;
        }

        if (!"确认执行".equals(confirmCode)) {
            response.put("success", false);
            response.put("error", "确认码不正确，请输入 '确认执行'");
            response.put("traceId", traceId);
            response.put("duration", System.currentTimeMillis() - startTime);
            return response;
        }

        String userInstruction = mcpInvocationSecurityGate.buildInstruction(toolName, parameters);
        GateDecision decision = mcpInvocationSecurityGate.evaluate(toolName, parameters, McpSecurityProfile.POST_CONFIRMATION);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("确认执行路径仍被安全门拦截: tool={} code={}", toolName, decision.getCode());
            persistGateReject(traceId, userInstruction, toolName, decision, startTime);
            response.put("success", false);
            response.put("error", decision.getMessage());
            response.put("securityCode", decision.getCode());
            response.put("traceId", traceId);
            response.put("duration", System.currentTimeMillis() - startTime);
            return response;
        }

        log.info("用户已确认，执行工具: {}", toolName);
        Map<String, Object> execResponse = doExecuteTool(toolName, parameters, startTime, traceId, userInstruction);
        execResponse.put("traceId", traceId);
        persistMcpSuccess(traceId, userInstruction, decision.getRiskLevel(), toolName, execResponse, startTime);
        return execResponse;
    }

    /**
     * 延时任务到点执行：用户在创建任务时已确认；仍执行注入与高危校验；
     * MEDIUM 风险不再要求实时二次确认（与即时 MCP 路径区分）。
     */
    public Map<String, Object> executeDeferredScheduledTool(String toolName, Map<String, Object> parameters) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> reject = new HashMap<>();

        if (toolName == null || toolName.isBlank()) {
            reject.put("success", false);
            reject.put("error", "工具名称不能为空");
            reject.put("duration", System.currentTimeMillis() - startTime);
            return reject;
        }

        String userInstruction = mcpInvocationSecurityGate.buildInstruction(toolName, parameters);
        GateDecision decision = mcpInvocationSecurityGate.evaluate(toolName, parameters, McpSecurityProfile.DEFERRED_SCHEDULED);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("延时任务安全门拦截: tool={} code={}", toolName, decision.getCode());
            reject.put("success", false);
            reject.put("error", decision.getMessage());
            reject.put("securityCode", decision.getCode());
            reject.put("traceId", traceId);
            reject.put("duration", System.currentTimeMillis() - startTime);
            return reject;
        }
        RiskLevel evaluatedRisk = decision.getRiskLevel();
        String deferredInstruction = "[DEFERRED_SCHEDULE] " + userInstruction;
        Map<String, Object> execResponse = doExecuteTool(toolName, parameters, startTime, traceId, deferredInstruction);
        execResponse.put("traceId", traceId);
        persistMcpSuccess(traceId, deferredInstruction, evaluatedRisk, toolName, execResponse, startTime);
        return execResponse;
    }
    
    private Map<String, Object> doExecuteTool(String toolName, Map<String, Object> parameters, long startTime,
                                              String traceId, String userInstruction) {
        OpsSecurityContext.open(traceId, userInstruction, true);
        try {
            Map<String, Object> response = new HashMap<>();
            String result = null;
            boolean success = true;
            String errorMsg = null;

            try {
                switch (toolName) {
                    case "DiskTool":
                        result = diskTool.checkDiskUsage();
                        break;

                    case "DiskInsightTool":
                        String rootPath = getStringParam(parameters, "rootPath");
                        Integer maxDepth = getIntegerParam(parameters, "maxDepth");
                        Integer topN = getIntegerParam(parameters, "topN");
                        result = diskTool.rankDiskUsageUnderPath(rootPath, maxDepth, topN);
                        break;

                    case "ProcessTool":
                        Double minCpu = getDoubleParam(parameters, "minCpu");
                        Double minMem = getDoubleParam(parameters, "minMem");
                        result = processTool.listProcesses(minCpu, minMem);
                        break;

                    case "SystemLoadTool":
                        result = systemLoadTool.checkSystemLoad();
                        break;

                    case "LogAnalysisTool":
                        String logPath = getStringParam(parameters, "logPath");
                        Integer lines = getIntegerParam(parameters, "lines");
                        result = logAnalysisTool.analyzeLogs(logPath, lines);
                        break;

                case "CleanTempTool":
                    String path = getStringParam(parameters, "path");
                    Integer days = getIntegerParam(parameters, "days");
                    Boolean dryRun = getBooleanParam(parameters, "dryRun");
                    Boolean confirmDelete = getBooleanParam(parameters, "confirmDelete");
                    result = cleanTempTool.cleanTempFiles(path, days, dryRun, confirmDelete);
                    break;

                    case "ConfigCheckTool":
                        String configPath = getStringParam(parameters, "configPath");
                        if (configPath == null || configPath.isBlank()) {
                            success = false;
                            errorMsg = "配置文件路径不能为空";
                        } else {
                            result = configCheckTool.checkConfig(configPath);
                        }
                        break;

                    case "NetworkTool":
                        String target = getStringParam(parameters, "target");
                        String type = getStringParam(parameters, "type");
                        Integer count = getIntegerParam(parameters, "count");
                        if (target == null || target.isBlank()) {
                            success = false;
                            errorMsg = "目标主机不能为空";
                        } else {
                            result = networkTool.diagnoseNetwork(target, type, count);
                        }
                        break;

                    case "PrivilegeTool":
                        String resource = getStringParam(parameters, "resource");
                        String action = getStringParam(parameters, "action");
                        if (resource == null || resource.isBlank()) {
                            success = false;
                            errorMsg = "资源路径不能为空";
                        } else {
                            result = privilegeTool.checkPrivilege(resource, action);
                        }
                        break;

                    case "OsInsightTool":
                        String op = getStringParam(parameters, "operation");
                        Integer sinceMin = getIntegerParam(parameters, "sinceMinutes");
                        Integer maxLines = getIntegerParam(parameters, "maxLines");
                        Integer pid = getIntegerParam(parameters, "pid");
                        result = osInsightTool.executeGateway(op, sinceMin, maxLines, pid);
                        break;

                    default:
                        log.warn("未知的工具名称: {}", toolName);
                        success = false;
                        errorMsg = "未知的工具: " + toolName;
                        break;
                }
            } catch (JsonProcessingException e) {
                log.error("JSON处理异常: {}", e.getMessage(), e);
                success = false;
                errorMsg = "JSON处理异常: " + e.getMessage();
            } catch (Exception e) {
                log.error("执行工具 {} 时发生异常: {}", toolName, e.getMessage(), e);
                success = false;
                errorMsg = "执行异常: " + e.getMessage();
            }

            long duration = System.currentTimeMillis() - startTime;

            if (success) {
                response.put("success", true);
                response.put("data", result);
                response.put("duration", duration);
                log.info("工具 {} 执行成功，耗时: {}ms", toolName, duration);
            } else {
                response.put("success", false);
                response.put("error", errorMsg);
                response.put("duration", duration);
                log.info("工具 {} 执行失败: {}", toolName, errorMsg);
            }

            return response;
        } finally {
            OpsSecurityContext.clear();
        }
    }

    private String getStringParam(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getIntegerParam(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDoubleParam(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean getBooleanParam(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
