package com.award.log.controller;

import com.award.log.agent.awm.FailureInsightService;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpExecuteController {

    private final McpToolCatalog mcpToolCatalog;
    private final McpSecurityService mcpSecurityService;
    private final McpExecutionService mcpExecutionService;
    private final McpAuditService mcpAuditService;
    private final FailureInsightService failureInsightService;

    @GetMapping("/tools")
    public Map<String, Object> getMcpTools() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", mcpToolCatalog.listToolsForApi());
        return response;
    }

    @PostMapping("/execute")
    public Map<String, Object> executeTool(@RequestBody Map<String, Object> request) {
        String toolName = (String) request.get("toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.get("parameters");
        String userMessage = stringField(request, "userMessage");

        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        log.info("收到工具执行请求: toolName={}", toolName);

        GateDecision decision = mcpSecurityService.evaluateInitial(toolName, parameters, userMessage);
        String userInstruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, parameters);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("MCP 安全门拦截: tool={} code={}", toolName, decision.getCode());
            mcpAuditService.persistGateReject(traceId, userInstruction, toolName, decision, startTime);
            failureInsightService.captureReject(userMessage, decision.getCode(), toolName, decision.getMessage(), traceId);
            return mcpSecurityService.buildBlockResponse(traceId, startTime, decision);
        }
        if (decision.getType() == GateDecision.Type.NEED_CONFIRM) {
            log.info("MCP 中等风险，需二次确认: tool={} score={}", toolName, decision.getAgenticRiskScore());
            mcpAuditService.persistNeedConfirm(traceId, userInstruction, toolName, decision, startTime);
            return mcpSecurityService.buildNeedConfirmResponse(traceId, startTime, decision, toolName, parameters);
        }

        Map<String, Object> execResponse = mcpExecutionService.execute(toolName, parameters, startTime, traceId, userInstruction);
        execResponse.put("traceId", traceId);
        mcpAuditService.persistSuccess(traceId, userInstruction, decision, toolName, execResponse, startTime);
        return execResponse;
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

        String userMessage = stringField(request, "userMessage");
        McpToolParamReader.applyConfirmedWriteToolParams(toolName, parameters, userMessage);
        String userInstruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluatePostConfirm(toolName, parameters, userMessage);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("确认执行路径仍被安全门拦截: tool={} code={}", toolName, decision.getCode());
            mcpAuditService.persistGateReject(traceId, userInstruction, toolName, decision, startTime);
            failureInsightService.captureReject(userMessage, decision.getCode(), toolName, decision.getMessage(), traceId);
            Map<String, Object> block = mcpSecurityService.buildBlockResponse(traceId, startTime, decision);
            return block;
        }

        log.info("用户已确认，执行工具: {}", toolName);
        Map<String, Object> execResponse = mcpExecutionService.execute(toolName, parameters, startTime, traceId, userInstruction);
        execResponse.put("traceId", traceId);
        mcpAuditService.persistSuccess(traceId, userInstruction, decision, toolName, execResponse, startTime);
        return execResponse;
    }

    /**
     * 延时任务到点执行：用户在创建任务时已确认；仍执行注入与高危校验。
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

        String userInstruction = mcpSecurityService.buildInstruction(toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluateDeferred(toolName, parameters);
        if (decision.getType() == GateDecision.Type.BLOCK) {
            log.warn("延时任务安全门拦截: tool={} code={}", toolName, decision.getCode());
            reject.put("success", false);
            reject.put("error", decision.getMessage());
            reject.put("securityCode", decision.getCode());
            reject.put("traceId", traceId);
            reject.put("duration", System.currentTimeMillis() - startTime);
            return reject;
        }
        String deferredInstruction = "[DEFERRED_SCHEDULE] " + userInstruction;
        Map<String, Object> execResponse = mcpExecutionService.execute(toolName, parameters, startTime, traceId, deferredInstruction);
        execResponse.put("traceId", traceId);
        mcpAuditService.persistSuccess(traceId, deferredInstruction, decision, toolName, execResponse, startTime);
        return execResponse;
    }

    private static String stringField(Map<String, Object> request, String key) {
        if (request == null || key == null) {
            return "";
        }
        Object v = request.get(key);
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }
}
