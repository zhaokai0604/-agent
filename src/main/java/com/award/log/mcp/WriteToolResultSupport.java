package com.award.log.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 解析 MCP 工具 JSON 返回中的执行模式，供审计与防幻觉校验共用。
 */
public final class WriteToolResultSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WriteToolResultSupport() {
    }

    /** 从工具 JSON 提取 data.mode（DELETE/EXECUTED/DRY-RUN 等）。 */
    public static String extractMode(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(toolResultJson);
            JsonNode data = parseDataNode(root);
            if (data != null && data.has("mode")) {
                return data.path("mode").asText("");
            }
            if (root.path("success").isBoolean() && !root.path("success").asBoolean(false)) {
                return "ERROR";
            }
            if (data == null) {
                return "";
            }
            return "";
        } catch (Exception e) {
            return extractModeFallback(toolResultJson);
        }
    }

    public static boolean isToolSuccess(String toolResultJson) {
        JsonNode root = parseRoot(toolResultJson);
        if (root == null) {
            return false;
        }
        if (root.isObject() && root.has("success")) {
            return root.path("success").asBoolean(false);
        }
        return true;
    }

    public static boolean isRealWriteMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String m = mode.trim().toUpperCase();
        return "DELETE".equals(m) || "EXECUTED".equals(m);
    }

    /** 请求参数是否明确要求真实写入（dryRun=false 或 confirm*=true）。 */
    public static boolean requestedRealWrite(Map<String, Object> parameters) {
        if (parameters == null) {
            return false;
        }
        if (Boolean.FALSE.equals(coerceBool(parameters.get("dryRun")))) {
            return true;
        }
        return Boolean.TRUE.equals(coerceBool(parameters.get("confirmDelete")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmRestart")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmKill")))
                || Boolean.TRUE.equals(coerceBool(parameters.get("confirmStop")));
    }

    private static Boolean coerceBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) {
            return null;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
            return false;
        }
        return null;
    }

    /** 成功且有写落地证据（非空预览、非 dry-run）才视为确认真写。 */
    public static boolean isConfirmedRealWrite(String toolResultJson) {
        if (!isToolSuccess(toolResultJson)) {
            return false;
        }
        JsonNode root = parseRoot(toolResultJson);
        if (root == null) {
            return false;
        }
        // A bare success envelope is only an acknowledgement; it contains no
        // evidence that a write actually reached the target system.
        if (bareSuccessfulEnvelope(root)) {
            return false;
        }
        JsonNode data = parseDataNode(root);
        if (data == null || data.isNull()) {
            // 无业务 data 时：不再把空信封 {"success":true} 当成写落地
            return false;
        }
        String mode = data.path("mode").asText(extractMode(toolResultJson));
        if (!isRealWriteMode(mode)) {
            if (looksLikePreviewOnly(mode, data)) {
                return false;
            }
            if (hasWriteEffectEvidence(data)) {
                return true;
            }
            return false;
        }
        if (hasWriteEffectEvidence(data)) {
            return true;
        }
        JsonNode nestedSuccess = data.get("success");
        if (nestedSuccess != null && nestedSuccess.isBoolean()) {
            return nestedSuccess.asBoolean(false);
        }
        // mode=DELETE/EXECUTED 但无效果字段：杀进程/重启至少应有目标标识
        if (data.has("pid") || data.has("service") || data.has("containerName") || data.has("command")) {
            return !"DRY-RUN".equalsIgnoreCase(mode);
        }
        // 空删除（filesDeleted 缺失且为 0）不当成真写成功
        if (data.has("filesDeleted") && data.path("filesDeleted").asInt(0) <= 0) {
            return false;
        }
        return false;
    }

    private static boolean hasWriteEffectEvidence(JsonNode data) {
        if (data == null || data.isNull()) {
            return false;
        }
        JsonNode nestedSuccess = data.get("success");
        boolean nestedOk = nestedSuccess != null && nestedSuccess.isBoolean() && nestedSuccess.asBoolean(false);
        if (data.has("filesDeleted")) {
            return data.path("filesDeleted").asInt(0) > 0;
        }
        if (data.has("removed")) {
            return data.path("removed").asBoolean(false);
        }
        if (data.has("bytesFreed")) {
            return data.path("bytesFreed").asLong(0L) > 0L
                    || data.path("filesDeleted").asInt(0) > 0;
        }
        if (data.has("pid") && nestedOk) {
            return true;
        }
        if (data.has("service")) {
            return !looksLikePreviewOnly("", data) && (nestedOk || !data.path("service").asText("").isBlank());
        }
        if (data.has("containerName") && nestedOk) {
            return true;
        }
        return nestedOk;
    }

    private static boolean looksLikePreviewOnly(String mode, JsonNode data) {
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toUpperCase();
            if ("DRY-RUN".equals(normalized) || "PREVIEW".equals(normalized) || "SCAN".equals(normalized)) {
                return true;
            }
        }
        if (data == null || data.isNull() || !data.isObject()) {
            return false;
        }
        if (data.has("preview") || data.has("previewCount") || data.has("plan")) {
            return true;
        }
        return (data.has("filesFound") || data.has("deletableCount") || data.has("totalFilesFound"))
                && !data.has("filesDeleted")
                && !data.has("removed")
                && !data.has("bytesFreed");
    }

    private static boolean bareSuccessfulEnvelope(JsonNode root) {
        return root != null
                && root.isObject()
                && root.path("success").asBoolean(false)
                && !root.has("data")
                && root.path("error").asText("").isBlank();
    }

    public static String errorMessage(String toolResultJson) {
        JsonNode root = parseRoot(toolResultJson);
        if (root == null) {
            return "工具返回无法解析";
        }
        if (root.has("error") && !root.path("error").asText("").isBlank()) {
            return root.path("error").asText();
        }
        if (root.has("message") && !root.path("message").asText("").isBlank()) {
            return root.path("message").asText();
        }
        JsonNode data = parseDataNode(root);
        if (data != null) {
            if (data.has("error") && !data.path("error").asText("").isBlank()) {
                return data.path("error").asText();
            }
            if (data.has("stderr") && !data.path("stderr").asText("").isBlank()) {
                return data.path("stderr").asText();
            }
            if (data.has("stderrOrOutput") && !data.path("stderrOrOutput").asText("").isBlank()) {
                return data.path("stderrOrOutput").asText();
            }
        }
        return "工具执行失败";
    }

    /**
     * 用户/界面已请求真实写，但工具返回仍为预览时生成明确告警（附在 data 内或日志）。
     */
    public static String mismatchWarning(String toolName, String mode) {
        return "写操作未落地：工具 " + toolName + " 返回 mode=" + mode
                + "（仍为预览或失败）。请检查全局演练模式、路径权限，或确认已点击「确认执行」。";
    }

    private static String extractModeFallback(String json) {
        int idx = json.indexOf("\"mode\"");
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return "";
        }
        return json.substring(q1 + 1, q2);
    }

    private static JsonNode parseRoot(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(toolResultJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode parseDataNode(JsonNode root) {
        return unwrapBusinessData(root);
    }

    /**
     * 解开 ToolResult 双层信封：{@code {success,data:"{mode:...}"}} → 业务 data 对象。
     * 管线里常见把整段 ToolResult JSON 字符串塞进 exec.data，验证器必须二次展开。
     */
    public static JsonNode unwrapBusinessData(JsonNode node) {
        JsonNode cur = node;
        for (int depth = 0; depth < 5 && cur != null; depth++) {
            if (cur.isTextual()) {
                String raw = cur.asText();
                if (raw == null || raw.isBlank()) {
                    return cur;
                }
                try {
                    cur = MAPPER.readTree(raw.trim());
                    continue;
                } catch (Exception e) {
                    return cur;
                }
            }
            if (!cur.isObject()) {
                return cur;
            }
            boolean looksLikeToolEnvelope = cur.has("success")
                    && cur.has("data")
                    && !cur.has("mode")
                    && !cur.has("filesDeleted")
                    && !cur.has("filesFound")
                    && !cur.has("service")
                    && !cur.has("unit");
            if (looksLikeToolEnvelope) {
                cur = cur.get("data");
                continue;
            }
            return cur;
        }
        return cur;
    }

    /** 从任意工具回执（字符串或已解析节点）取出业务 data 对象。 */
    public static JsonNode businessDataFromToolPayload(String toolPayload) {
        JsonNode root = parseRoot(toolPayload);
        return unwrapBusinessData(root);
    }
}
