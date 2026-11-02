package com.award.log.controller;

import com.award.log.agent.awm.FailureInsightService;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.config.OpsPepProperties;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.effect.SessionRiskBudgetService;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpPendingConfirmationService;
import com.award.log.service.mcp.McpSecurityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具 HTTP 执行入口：目录查询、安全门预检、确认后实写。
 * HTTP 前缀：{@code /api/mcp}。
 * 中高风险写操作必须走待确认 + 能力凭证；延时调度走独立安全评估。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpExecuteController {

    private final McpToolCatalog mcpToolCatalog;
    private final McpSecurityService mcpSecurityService;
    private final HttpAuditSubject httpAuditSubject;
    private final OpsExecutionPipeline opsExecutionPipeline;
    @org.springframework.beans.factory.annotation.Autowired
    public McpExecuteController(McpToolCatalog catalog, McpSecurityService security,
            HttpAuditSubject subject, OpsExecutionPipeline pipeline) {
        this.mcpToolCatalog = catalog;
        this.mcpSecurityService = security;
        this.httpAuditSubject = subject;
        this.opsExecutionPipeline = pipeline;
    }

    /** Source compatibility for embedded callers; legacy execution parameters no longer open a bypass. */
    public McpExecuteController(McpToolCatalog catalog, McpSecurityService security,
            McpExecutionService execution, McpAuditService audit, FailureInsightService failure,
            McpPendingConfirmationService pending, HttpAuditSubject subject, ToolEffectResolver effects,
            SessionRiskBudgetService budget, OpsExecutionPipeline pipeline, OpsPepProperties properties) {
        this(catalog, security, subject, pipeline);
    }

    @GetMapping("/tools")
    public Map<String, Object> getMcpTools() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", mcpToolCatalog.listToolsForApi());
        response.put("contractVersion", 2);
        return response;
    }

    /** 安全门先裁决：拦截、待确认或放行；放行后仍受会话风险预算约束。 */
    @PostMapping("/execute")
    public Map<String, Object> executeTool(@RequestBody Map<String, Object> request) {
        if (request == null) {
            return errorResponse("请求体不能为空");
        }

        String toolName = stringField(request, "toolName");
        Map<String, Object> parameters = extractParameters(request);
        String userMessage = stringField(request, "userMessage");
        String requester = currentRequester();
        boolean previewOnly = Boolean.TRUE.equals(request.get("previewOnly"));

        OpsExecutionPipeline.PipelineResult result = previewOnly
                ? opsExecutionPipeline.preview(toolName, parameters, userMessage, requester)
                : opsExecutionPipeline.executeDirect(toolName, parameters, userMessage, requester);
        return decoratePipelineResult(result, toolName);
    }

    /** 确认写：核销 pending 与能力凭证后强制实写参数，并再次过安全门。 */
    @PostMapping("/confirmExecute")
    public Map<String, Object> confirmExecuteTool(@RequestBody Map<String, Object> request) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

        if (request == null) {
            return errorResponse("请求体不能为空", traceId, startTime, "", 400);
        }

        String confirmCode = stringField(request, "confirmCode");
        String confirmationId = stringField(request, "confirmationId");
        String capabilityToken = stringField(request, "capabilityToken");
        String confirmToken = stringField(request, "confirmToken");
        String sessionId = stringField(request, "sessionId");
        if (confirmationId.isBlank()) {
            confirmationId = stringField(request, "traceId");
        }

        OpsExecutionPipeline.PipelineResult result = opsExecutionPipeline.executeConfirmed(
                confirmationId, capabilityToken, confirmToken, confirmCode, currentRequester(), sessionId);
        String toolName = result.toolName() == null || result.toolName().isBlank()
                ? stringField(request, "toolName") : result.toolName();
        return decoratePipelineResult(result, toolName);
    }

    /** 延时任务携带原操作者；真实写必须回到实时预览确认，禁止调度绕过凭证。 */
    public Map<String, Object> executeDeferredScheduledTool(String toolName, Map<String, Object> parameters) {
        return executeDeferredScheduledTool(toolName, parameters, "deferred");
    }

    public Map<String, Object> executeDeferredScheduledTool(String toolName, Map<String, Object> parameters, String requester) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        if (toolName == null || toolName.isBlank()) return errorResponse("工具名称不能为空", traceId, start, "", 400);
        Map<String,Object> platform = mcpToolCatalog.describePlatformSupport(toolName);
        if (!Boolean.TRUE.equals(platform.get("available"))) return unsupportedResponse(toolName, traceId, start, platform);
        Map<String, Object> params = parameters == null ? new HashMap<>() : new HashMap<>(parameters);
        if (new ToolEffectResolver().resolve(toolName, params).writeEffect()
                && WriteToolResultSupport.requestedRealWrite(params)) {
            return errorResponse("延时真实写缺少持久化审批凭证，请在执行时重新预览确认");
        }
        GateDecision deferred = mcpSecurityService.evaluateDeferred(toolName, params);
        if (deferred.getType() == GateDecision.Type.BLOCK) {
            Map<String,Object> reject = new LinkedHashMap<>();
            reject.put("success",false); reject.put("error",deferred.getMessage()); reject.put("securityCode",deferred.getCode());
            return decorateEnvelope(reject,toolName,traceId,403);
        }
        return decoratePipelineResult(opsExecutionPipeline.executeDirect(toolName, params,
                "[DEFERRED_SCHEDULE] 只读或预览", requester), toolName);
    }

    private String currentRequester() {
        String requester = httpAuditSubject.currentOperatorId();
        return requester == null || requester.isBlank() ? "anonymous" : requester;
    }

    private Map<String, Object> decoratePipelineResult(OpsExecutionPipeline.PipelineResult result, String toolName) {
        Map<String, Object> body = result.body() != null ? new LinkedHashMap<>(result.body()) : new LinkedHashMap<>();
        String traceId = String.valueOf(body.getOrDefault("traceId", result.traceId()));
        int status = Boolean.TRUE.equals(body.get("success")) ? 200
                : Boolean.TRUE.equals(body.get("needConfirm")) ? 202
                : body.get("platformSupport") instanceof Map<?, ?> support && !Boolean.TRUE.equals(support.get("available")) ? 409
                : body.containsKey("securityCode") ? 403
                : isUnknownToolError(body) ? 404
                : 500;
        if (result.effect() != null) {
            body.put("toolEffect", result.effect().toMap());
        }
        if (result.effectFingerprint() != null && !result.effectFingerprint().isBlank()) {
            body.put("effectFingerprint", result.effectFingerprint());
        }
        return decorateEnvelope(body, toolName, traceId, status);
    }

    private static boolean isUnknownToolError(Map<String, Object> body) {
        Object err = body.get("error");
        return err != null && String.valueOf(err).contains("未找到可执行工具");
    }

    private Map<String, Object> decorateEnvelope(Map<String, Object> raw,
                                                 String toolName,
                                                 String traceId,
                                                 int statusCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (raw != null) {
            response.putAll(raw);
        }
        Map<String, Object> platformSupport = mcpToolCatalog.describePlatformSupport(toolName);
        response.put("toolName", toolName);
        response.put("traceId", response.getOrDefault("traceId", traceId));
        response.put("statusCode", statusCode);
        response.putIfAbsent("success", false);
        response.putIfAbsent("needConfirm", false);
        response.putIfAbsent("confirmationId", "");
        response.putIfAbsent("expiresAtMs", null);
        response.putIfAbsent("capabilityToken", "");
        response.put("platformSupport", platformSupport);
        response.put("warnings", buildWarnings(response, platformSupport));
        return response;
    }

    private Map<String, Object> unsupportedResponse(String toolName,
                                                    String traceId,
                                                    long startTime,
                                                    Map<String, Object> platformSupport) {
        String reason = String.valueOf(platformSupport.getOrDefault("reason", "当前平台暂不支持该工具"));
        Map<String, Object> response = errorResponse(reason, traceId, startTime, toolName, 409);
        response.put("platformSupport", platformSupport);
        response.put("warnings", List.of(reason));
        return response;
    }

    private List<String> buildWarnings(Map<String, Object> response, Map<String, Object> platformSupport) {
        List<String> warnings = new ArrayList<>();
        if (platformSupport != null && !Boolean.TRUE.equals(platformSupport.get("available"))) {
            warnings.add(String.valueOf(platformSupport.getOrDefault("reason", "当前平台不可用")));
        }
        Object writeMismatchMessage = response.get("writeMismatchMessage");
        if (writeMismatchMessage instanceof String message && !message.isBlank()) {
            warnings.add(message.trim());
        }
        Object evidenceMessage = response.get("evidenceMessage");
        if (evidenceMessage instanceof String message && !message.isBlank()) {
            warnings.add(message.trim());
        }
        return warnings;
    }

    private static String stringField(Map<String, Object> request, String key) {
        if (request == null || key == null) {
            return "";
        }
        Object value = request.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractParameters(Map<String, Object> request) {
        if (request == null) {
            return new HashMap<>();
        }
        Object raw = request.get("parameters");
        if (raw instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }

    private Map<String, Object> errorResponse(String message) {
        return errorResponse(message, UUID.randomUUID().toString(), System.currentTimeMillis(), "", 500);
    }

    private Map<String, Object> errorResponse(String message,
                                              String traceId,
                                              long startTime,
                                              String toolName,
                                              int statusCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message);
        response.put("traceId", traceId);
        response.put("duration", System.currentTimeMillis() - startTime);
        return decorateEnvelope(response, toolName, traceId, statusCode);
    }
}
