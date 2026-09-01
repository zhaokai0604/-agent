package com.award.log.agent;

import com.award.log.agent.audit.OpsAuditPhases;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.security.effect.RemediationContractFactory;
import com.award.log.security.effect.RemediationVerifyEvaluator;
import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.security.effect.WriteRemediationContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器旁路写路径与管线对齐：挂载契约并产出 {@code verifyOutcome}。
 * MCP 正式写仍应走 {@code OpsExecutionPipeline}；此处兜底五场景直接工具调用。
 */
public final class RemediationClosureSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolEffectResolver EFFECT_RESOLVER = new ToolEffectResolver();

    private RemediationClosureSupport() {
    }

    public static Map<String, Object> verifyToolJson(
            String toolName,
            String toolResultJson,
            Map<String, Object> parameters,
            String requester) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        ToolEffect effect = EFFECT_RESOLVER.resolve(toolName, params);
        WriteRemediationContract contract = RemediationContractFactory.build(
                toolName, params, effect, requester);
        Map<String, Object> body = executionBodyFromToolJson(toolResultJson);
        return RemediationVerifyEvaluator.evaluate(
                toolName, body, "", params, contract, effect);
    }

    public static void appendClosureSteps(
            AssistantAuditRecorder auditRecorder,
            List<Map<String, Object>> steps,
            Map<String, Object> verifyReport) {
        if (auditRecorder == null || steps == null || verifyReport == null) {
            return;
        }
        Object outcome = verifyReport.get("verifyOutcome");
        Object message = verifyReport.get("message");
        auditRecorder.addStep(steps, OpsAuditPhases.VERIFY,
                "verifyOutcome=" + outcome + (message == null ? "" : (" " + message)));
        Object recover = verifyReport.get("recover");
        if (recover instanceof Map<?, ?> recoverMap) {
            boolean reversible = Boolean.TRUE.equals(recoverMap.get("reversible"));
            String hint = String.valueOf(recoverMap.get("recoverHint") == null ? "" : recoverMap.get("recoverHint"));
            String note = String.valueOf(recoverMap.get("irreversibilityNote") == null
                    ? "" : recoverMap.get("irreversibilityNote"));
            Object recoverTool = recoverMap.get("recoverTool");
            if (reversible) {
                auditRecorder.addStep(steps, OpsAuditPhases.RECOVER,
                        "reversible=true tool=" + (recoverTool == null ? "" : recoverTool)
                                + (hint.isBlank() || "null".equals(hint) ? "" : (" " + hint)));
            } else if (!note.isBlank() && !"null".equals(note)) {
                auditRecorder.addStep(steps, OpsAuditPhases.RECOVER, "irreversible " + note);
            }
        }
    }

    public static String summarizeVerify(Map<String, Object> verifyReport) {
        if (verifyReport == null) {
            return "";
        }
        Object outcome = verifyReport.get("verifyOutcome");
        Object message = verifyReport.get("message");
        return "verifyOutcome=" + outcome + (message == null ? "" : (": " + message));
    }

    public static boolean verifiedOk(Map<String, Object> verifyReport) {
        return verifyReport != null && Boolean.TRUE.equals(verifyReport.get("verified"));
    }

    public static Map<String, Object> writeParams(String toolName, String pathOrService, boolean restart) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dryRun", false);
        if (restart) {
            p.put("serviceName", pathOrService);
            p.put("confirmRestart", true);
        } else if ("LogCleanupTool".equals(toolName)) {
            p.put("logPath", pathOrService);
            p.put("path", pathOrService);
            p.put("confirmDelete", true);
        } else {
            p.put("path", pathOrService);
            p.put("confirmDelete", true);
        }
        return p;
    }

    private static Map<String, Object> executionBodyFromToolJson(String toolResultJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (toolResultJson == null || toolResultJson.isBlank()) {
            body.put("success", false);
            body.put("error", "empty_tool_result");
            return body;
        }
        try {
            JsonNode root = MAPPER.readTree(toolResultJson);
            boolean success = root.has("success")
                    ? root.path("success").asBoolean(false)
                    : WriteToolResultSupport.isToolSuccess(toolResultJson);
            body.put("success", success);
            if (root.has("data")) {
                JsonNode data = root.get("data");
                if (data.isTextual()) {
                    String raw = data.asText();
                    if (raw != null && raw.trim().startsWith("{")) {
                        body.put("data", MAPPER.readValue(raw, Map.class));
                    } else {
                        body.put("data", raw);
                    }
                } else if (data.isObject()) {
                    body.put("data", MAPPER.convertValue(data, Map.class));
                } else {
                    body.put("data", data.toString());
                }
            } else if (root.isObject()) {
                body.put("data", MAPPER.convertValue(root, Map.class));
            }
            if (root.has("error")) {
                body.put("error", root.path("error").asText(""));
            }
            return body;
        } catch (Exception e) {
            body.put("success", WriteToolResultSupport.isToolSuccess(toolResultJson));
            body.put("data", Map.of("raw", toolResultJson));
            return body;
        }
    }
}
