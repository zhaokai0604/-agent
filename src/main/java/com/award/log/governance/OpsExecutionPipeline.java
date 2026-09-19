package com.award.log.governance;

import com.award.log.config.OpsPepProperties;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.effect.ConfirmTokenHmac;
import com.award.log.security.effect.EffectFingerprint;
import com.award.log.security.effect.RemediationContractFactory;
import com.award.log.security.effect.RemediationVerifyEvaluator;
import com.award.log.security.effect.SessionRiskBudgetService;
import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.security.effect.WriteCapabilityToken;
import com.award.log.security.effect.WriteRemediationContract;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpPendingConfirmationService;
import com.award.log.service.mcp.McpSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PEP 执行管线：Plan/Preview → Approval → Execute → Verify → Recover 句柄。
 * 所有 DESTRUCTIVE/WRITE 工具的唯一写入口；验证产出 {@code verifyOutcome} 强状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsExecutionPipeline {

    private final McpToolCatalog mcpToolCatalog;
    private final McpSecurityService mcpSecurityService;
    private final McpExecutionService mcpExecutionService;
    private final McpAuditService mcpAuditService;
    private final McpPendingConfirmationService mcpPendingConfirmationService;
    private final ToolEffectResolver toolEffectResolver;
    private final SessionRiskBudgetService sessionRiskBudgetService;
    private final OpsPepProperties opsPepProperties;
    private final ObjectMapper objectMapper;

    public PipelineResult preview(String toolName, Map<String, Object> parameters, String userMessage, String requester) {
        Map<String, Object> params = dryRunParams(toolName, parameters);
        long start = System.currentTimeMillis();
        String traceId = newTraceId();
        Validation validation = validateTool(toolName, traceId, start);
        if (validation.error() != null) {
            Map<String, Object> err = validation.error();
            return new PipelineResult(err, traceId, start, toolName, null, "", "");
        }
        GateDecision decision = mcpSecurityService.evaluateInitial(toolName, params, userMessage);
        ToolEffect effect = toolEffectResolver.resolve(toolName, params);
        String fingerprint = EffectFingerprint.of(toolName, params, effect);
        String instruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, params);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            return PipelineResult.blocked(decision, traceId, start, toolName, effect, fingerprint);
        }

        Map<String, Object> exec = mcpExecutionService.execute(toolName, params, start, traceId, instruction);
        String previewJson = serializePreview(exec);
        WriteRemediationContract contract = RemediationContractFactory.build(toolName, parameters, effect, requester);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", Boolean.TRUE.equals(exec.get("success")));
        body.put("phase", "preview");
        body.put("dryRunPreview", previewJson);
        body.put("dryRunPreviewHash", ConfirmTokenHmac.hashPreview(previewJson));
        body.put("traceId", traceId);
        body.put("toolName", toolName);
        body.put("effectFingerprint", fingerprint);
        body.put("toolEffect", effect.toMap());
        body.put("remediationContract", contract.toMap());
        body.put("expectedEffect", contract.expectedEffect().toMap());
        body.put("recover", contract.recover().toMap());
        body.put("approval", contract.approval().toMap());
        body.put("duration", System.currentTimeMillis() - start);
        body.put("data", exec.get("data"));
        body.put("metricsBefore", RemediationMetricsCollector.fromResponse(
                objectMapper, toolName, exec, "preview"));
        if (!Boolean.TRUE.equals(exec.get("success"))) {
            body.put("error", exec.get("error"));
        }
        return new PipelineResult(body, traceId, start, toolName, effect, fingerprint, previewJson);
    }

    public PipelineResult propose(
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String requester,
            String dryRunPreviewJson) {
        return propose(toolName, parameters, userMessage, requester, dryRunPreviewJson, "");
    }

    public PipelineResult propose(
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String requester,
            String dryRunPreviewJson,
            String sessionId) {
        long start = System.currentTimeMillis();
        String traceId = newTraceId();
        Validation validation = validateTool(toolName, traceId, start);
        if (validation.error() != null) {
            Map<String, Object> err = validation.error();
            return new PipelineResult(err, traceId, start, toolName, null, "", "");
        }
        Map<String, Object> params = new HashMap<>(parameters == null ? Map.of() : parameters);
        GateDecision decision = mcpSecurityService.evaluateInitial(toolName, params, userMessage);
        ToolEffect effect = toolEffectResolver.resolve(toolName, params);
        String fingerprint = EffectFingerprint.of(toolName, params, effect);
        String instruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, params);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            return PipelineResult.blocked(decision, traceId, start, toolName, effect, fingerprint);
        }

        long ttl = opsPepProperties.getConfirmTtlMs();
        WriteCapabilityToken token = WriteCapabilityToken.issue(
                fingerprint, toolName, requester, ttl, dryRunPreviewJson,
                opsPepProperties.getConfirmHmacKey(), params);
        McpPendingConfirmationService.PendingConfirmation pending = mcpPendingConfirmationService.register(
                traceId, toolName, params, userMessage, instruction, requester,
                effect, fingerprint, token, dryRunPreviewJson, sessionId);

        WriteRemediationContract contract = RemediationContractFactory.build(toolName, params, effect, requester);
        Map<String, Object> body = mcpSecurityService.buildNeedConfirmResponse(
                traceId, start, decision, toolName, params);
        body.put("phase", "approval");
        body.put("confirmationId", pending.confirmationId());
        body.put("expiresAtMs", pending.expiresAtMs());
        body.put("capabilityToken", token.tokenId());
        body.put("confirmToken", token.confirmTokenHmac());
        body.put("dryRunPreviewHash", token.dryRunPreviewHash());
        body.put("needConfirm", true);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        body.put("remediationContract", contract.toMap());
        body.put("expectedEffect", contract.expectedEffect().toMap());
        body.put("recover", contract.recover().toMap());
        body.put("approval", contract.approval().toMap());
        if (!contract.recover().reversible() && !contract.recover().irreversibilityNote().isBlank()) {
            body.put("irreversibleWarning", contract.recover().irreversibilityNote());
        }
        mcpAuditService.persistNeedConfirm(traceId, instruction, toolName, decision, start, params, pending.confirmationId());
        return new PipelineResult(body, traceId, start, toolName, effect, fingerprint, dryRunPreviewJson);
    }

    public PipelineResult executeConfirmed(
            String confirmationId,
            String capabilityToken,
            String confirmToken,
            String confirmCode,
            String requester) {
        return executeConfirmed(confirmationId, capabilityToken, confirmToken, confirmCode, requester, "");
    }

    public PipelineResult executeConfirmed(
            String confirmationId,
            String capabilityToken,
            String confirmToken,
            String confirmCode,
            String requester,
            String sessionId) {
        long start = System.currentTimeMillis();
        if (!"确认执行".equals(confirmCode)) {
            return PipelineResult.error("确认码不正确，请输入 '确认执行'", confirmationId, start);
        }
        McpPendingConfirmationService.TakeResult take = mcpPendingConfirmationService.take(
                confirmationId, requester, capabilityToken, sessionId);
        if (take.status() != McpPendingConfirmationService.TakeStatus.OK || take.pending() == null) {
            return PipelineResult.error(mapTakeStatus(take.status()), confirmationId, start);
        }
        McpPendingConfirmationService.PendingConfirmation pending = take.pending();
        String traceId = pending.confirmationId();
        String toolName = pending.toolName();
        Map<String, Object> parameters = new HashMap<>(pending.parameters());
        ToolEffect effect = pending.effect() != null
                ? pending.effect()
                : toolEffectResolver.resolve(toolName, parameters);
        String fingerprint = pending.effectFingerprint();
        String dryRunPreview = pending.dryRunPreviewJson();

        WriteCapabilityToken token = pending.capabilityToken();
        if (token != null && opsPepProperties.isEnabled()
                && !ConfirmTokenHmac.matches(
                opsPepProperties.getConfirmHmacKey(),
                confirmToken,
                toolName,
                () -> EffectFingerprint.canonicalParams(parameters),
                dryRunPreview)) {
            return PipelineResult.error("HMAC 确认令牌无效或预览已变更，请重新预览", traceId, start);
        }

        String recomputed = EffectFingerprint.of(toolName, parameters, effect);
        if (!recomputed.equals(fingerprint)) {
            return PipelineResult.error("效果指纹校验失败，请重新预览确认", traceId, start);
        }

        SessionRiskBudgetService.BudgetDecision budget = sessionRiskBudgetService.check(requester, effect);
        if (!budget.allowed()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", budget.message());
            body.put("securityCode", budget.code());
            return new PipelineResult(body, traceId, start, toolName, effect, fingerprint, dryRunPreview);
        }

        McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluatePostConfirm(
                toolName, parameters, pending.userMessage());
        if (decision.getType() == GateDecision.Type.BLOCK) {
            return PipelineResult.blocked(decision, traceId, start, toolName, effect, fingerprint);
        }

        Map<String, Object> exec = mcpExecutionService.execute(
                toolName, parameters, start, traceId, pending.userInstruction(), true);
        exec.put("phase", "execute");
        exec.put("traceId", traceId);
        if (!pending.sessionId().isBlank()) {
            exec.put("sessionId", pending.sessionId());
        }
        if (Boolean.TRUE.equals(exec.get("success"))
                && WriteToolResultSupport.requestedRealWrite(parameters)) {
            sessionRiskBudgetService.consume(requester, effect);
            exec.put("riskBudget", sessionRiskBudgetService.summary(requester));
        }
        WriteRemediationContract contract = RemediationContractFactory.build(
                toolName, parameters, effect, requester);
        attachExecutionMetrics(exec, toolName, parameters, dryRunPreview, traceId, pending.userInstruction());
        exec.put("remediationContract", contract.toMap());
        exec.put("expectedEffect", contract.expectedEffect().toMap());
        exec.put("approval", contract.approval().toMap());
        Map<String, Object> verifyReport = verify(toolName, exec, dryRunPreview, parameters, effect, contract);
        exec.put("verify", verifyReport);
        exec.put("verified", verifyReport.get("verified"));
        exec.put("verifyOutcome", verifyReport.get("verifyOutcome"));
        exec.put("recover", verifyReport.getOrDefault("recover", contract.recover().toMap()));
        applyRemediationSuccessGate(exec, verifyReport, parameters);
        mcpAuditService.persistSuccess(traceId, pending.userInstruction(), decision, toolName, exec, start, parameters);
        return new PipelineResult(exec, traceId, start, toolName, effect, fingerprint, dryRunPreview);
    }

    /**
     * 执行后验证：写落地证据 + 预期变化对照；失败产出 {@code VERIFY_FAILED}/{@code EVIDENCE_INCOMPLETE}。
     */
    public Map<String, Object> verify(
            String toolName,
            Map<String, Object> executionBody,
            String dryRunPreviewJson,
            Map<String, Object> requestParameters) {
        ToolEffect effect = toolEffectResolver != null
                ? toolEffectResolver.resolve(toolName, requestParameters)
                : new ToolEffectResolver().resolve(toolName, requestParameters);
        WriteRemediationContract contract = RemediationContractFactory.build(
                toolName, requestParameters, effect, "");
        return verify(toolName, executionBody, dryRunPreviewJson, requestParameters, effect, contract);
    }

    public Map<String, Object> verify(
            String toolName,
            Map<String, Object> executionBody,
            String dryRunPreviewJson,
            Map<String, Object> requestParameters,
            ToolEffect effect,
            WriteRemediationContract contract) {
        return RemediationVerifyEvaluator.evaluate(
                toolName, executionBody, dryRunPreviewJson, requestParameters, contract, effect);
    }

    public PipelineResult executeDirect(
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String requester) {
        long start = System.currentTimeMillis();
        String traceId = newTraceId();
        Validation validation = validateTool(toolName, traceId, start);
        if (validation.error() != null) {
            Map<String, Object> err = validation.error();
            return new PipelineResult(err, traceId, start, toolName, null, "", "");
        }
        GateDecision decision = mcpSecurityService.evaluateInitial(toolName, parameters, userMessage);
        ToolEffect effect = toolEffectResolver.resolve(toolName, parameters);
        String fingerprint = EffectFingerprint.of(toolName, parameters, effect);
        String instruction = mcpSecurityService.buildAuditInstruction(userMessage, toolName, parameters);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            return PipelineResult.blocked(decision, traceId, start, toolName, effect, fingerprint);
        }
        if (decision.getType() == GateDecision.Type.NEED_CONFIRM) {
            Map<String, Object> dryParams = dryRunParams(toolName, parameters);
            Map<String, Object> dryExec = mcpExecutionService.execute(toolName, dryParams, start, traceId, instruction);
            String previewJson = serializePreview(dryExec);
            return propose(toolName, parameters, userMessage, requester, previewJson, "");
        }

        SessionRiskBudgetService.BudgetDecision budget = sessionRiskBudgetService.check(requester, effect);
        if (!budget.allowed()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", budget.message());
            body.put("securityCode", budget.code());
            return new PipelineResult(body, traceId, start, toolName, effect, fingerprint, "");
        }

        String beforePreviewJson = "";
        if (WriteToolResultSupport.requestedRealWrite(parameters)) {
            Map<String, Object> beforePreview = mcpExecutionService.execute(
                    toolName, dryRunParams(toolName, parameters), start, traceId,
                    instruction + " | pre-execution metrics");
            beforePreviewJson = serializePreview(beforePreview);
        }
        Map<String, Object> exec = mcpExecutionService.execute(toolName, parameters, start, traceId, instruction);
        exec.put("traceId", traceId);
        exec.put("phase", "execute");
        if (Boolean.TRUE.equals(exec.get("success"))
                && WriteToolResultSupport.requestedRealWrite(parameters)) {
            sessionRiskBudgetService.consume(requester, effect);
            exec.put("riskBudget", sessionRiskBudgetService.summary(requester));
        }
        WriteRemediationContract contract = RemediationContractFactory.build(
                toolName, parameters, effect, requester);
        attachExecutionMetrics(exec, toolName, parameters, beforePreviewJson, traceId,
                instruction + " | post-execution metrics");
        exec.put("remediationContract", contract.toMap());
        exec.put("expectedEffect", contract.expectedEffect().toMap());
        exec.put("approval", contract.approval().toMap());
        Map<String, Object> verifyReport = verify(toolName, exec, "", parameters, effect, contract);
        exec.put("verify", verifyReport);
        exec.put("verified", verifyReport.get("verified"));
        exec.put("verifyOutcome", verifyReport.get("verifyOutcome"));
        exec.put("recover", verifyReport.getOrDefault("recover", contract.recover().toMap()));
        applyRemediationSuccessGate(exec, verifyReport, parameters);
        mcpAuditService.persistSuccess(traceId, instruction, decision, toolName, exec, start, parameters);
        return new PipelineResult(exec, traceId, start, toolName, effect, fingerprint, "");
    }

    /**
     * 真实写：验证未通过（含 VERIFY_WEAK）时不得记为处置成功。
     */
    static void applyRemediationSuccessGate(
            Map<String, Object> exec,
            Map<String, Object> verifyReport,
            Map<String, Object> requestParameters) {
        if (exec == null || verifyReport == null) {
            return;
        }
        if (!WriteToolResultSupport.requestedRealWrite(
                requestParameters == null ? Map.of() : requestParameters)) {
            return;
        }
        boolean toolOk = Boolean.TRUE.equals(exec.get("success"));
        exec.put("executionSuccess", toolOk);
        boolean verified = Boolean.TRUE.equals(verifyReport.get("verified"));
        exec.put("remediationClosed", verified);
        if (toolOk && !verified) {
            exec.put("success", false);
            exec.put("verifyBlocked", true);
            Object msg = verifyReport.get("message");
            Object outcome = verifyReport.get("verifyOutcome");
            exec.put("error", "处置未闭环（" + outcome + "）："
                    + (msg == null ? "验证未通过" : msg));
        }
    }

    private Map<String, Object> dryRunParams(String toolName, Map<String, Object> parameters) {
        Map<String, Object> params = new HashMap<>(parameters == null ? Map.of() : parameters);
        params.put("dryRun", true);
        McpToolParamReader.applyDryRunDefaults(toolName, params);
        return params;
    }

    /**
     * 统一采集执行前后指标：前值来自预览，后值来自一次只读 dry-run 复测。
     * 这样 Agent、MCP、定时任务拿到的是同一套 effectMetrics，而不是各自拼接的日志。
     */
    private void attachExecutionMetrics(
            Map<String, Object> execution,
            String toolName,
            Map<String, Object> parameters,
            String beforePreviewJson,
            String traceId,
            String instruction) {
        if (execution == null || !WriteToolResultSupport.requestedRealWrite(
                parameters == null ? Map.of() : parameters)) {
            return;
        }
        Map<String, Object> beforeResponse = parseResponse(beforePreviewJson);
        Map<String, Object> before = RemediationMetricsCollector.fromResponse(
                objectMapper, toolName, beforeResponse, "pre-execution-preview");
        Map<String, Object> afterResponse;
        try {
            afterResponse = mcpExecutionService.execute(
                    toolName,
                    dryRunParams(toolName, parameters),
                    System.currentTimeMillis(),
                    traceId,
                    instruction);
        } catch (Exception e) {
            log.warn("执行后指标复测失败 tool={} traceId={}: {}", toolName, traceId, e.getMessage());
            afterResponse = Map.of("success", false, "error", e.getMessage());
        }
        Map<String, Object> after = RemediationMetricsCollector.fromResponse(
                objectMapper, toolName, afterResponse, "post-execution-probe");
        execution.put("metricsBefore", before);
        execution.put("metricsAfter", after);
        execution.put("metricsDelta", RemediationMetricsCollector.numericDelta(before, after));
        execution.put("metricsVerified", Boolean.TRUE.equals(after.get("available")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("success", false, "error", "执行前预览不可用");
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("success", false, "error", "执行前预览解析失败");
        }
    }

    private Validation validateTool(String toolName, String traceId, long start) {
        if (toolName == null || toolName.isBlank() || !mcpToolCatalog.isRegistered(toolName)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "未找到可执行工具: " + toolName);
            return new Validation(err);
        }
        Map<String, Object> platform = mcpToolCatalog.describePlatformSupport(toolName);
        if (!Boolean.TRUE.equals(platform.get("available"))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", String.valueOf(platform.getOrDefault("reason", "当前平台不可用")));
            err.put("platformSupport", platform);
            return new Validation(err);
        }
        return new Validation(null);
    }

    private String serializePreview(Map<String, Object> exec) {
        try {
            return objectMapper.writeValueAsString(exec);
        } catch (Exception e) {
            return String.valueOf(exec);
        }
    }

    private static String mapTakeStatus(McpPendingConfirmationService.TakeStatus status) {
        return switch (status) {
            case REQUESTER_MISMATCH -> "该待确认请求不属于当前用户";
            case TOKEN_MISMATCH -> "能力凭证无效或与效果指纹不匹配，请重新预览确认";
            case SESSION_MISMATCH -> "待确认请求不属于当前会话，请切回原会话后重试";
            case EXPIRED -> "待确认请求已过期，请重新发起预览";
            default -> "未找到对应的待确认请求，请重新预览";
        };
    }

    private static String newTraceId() {
        return java.util.UUID.randomUUID().toString();
    }

    private record Validation(Map<String, Object> error) {
    }

    public record PipelineResult(
            Map<String, Object> body,
            String traceId,
            long startTimeMs,
            String toolName,
            ToolEffect effect,
            String effectFingerprint,
            String dryRunPreviewJson) {

        static PipelineResult error(String message, String traceId, long start) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", message);
            body.put("traceId", traceId);
            body.put("duration", System.currentTimeMillis() - start);
            return new PipelineResult(body, traceId, start, "", null, "", "");
        }

        static PipelineResult blocked(
                GateDecision decision,
                String traceId,
                long start,
                String toolName,
                ToolEffect effect,
                String fingerprint) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("needConfirm", false);
            body.put("securityCode", decision.getCode());
            body.put("error", decision.getMessage());
            body.put("traceId", traceId);
            body.put("duration", System.currentTimeMillis() - start);
            body.put("toolEffect", effect != null ? effect.toMap() : Map.of());
            body.put("effectFingerprint", fingerprint);
            return new PipelineResult(body, traceId, start, toolName, effect, fingerprint, "");
        }
    }
}
