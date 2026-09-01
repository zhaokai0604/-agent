package com.award.log.security.effect;

import com.award.log.mcp.WriteToolResultSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 业务验证：写落地证据 + 预期变化对照，产出 {@link VerifyOutcome}。
 */
public final class RemediationVerifyEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RemediationVerifyEvaluator() {
    }

    public static Map<String, Object> evaluate(
            String toolName,
            Map<String, Object> executionBody,
            String dryRunPreviewJson,
            Map<String, Object> requestParameters,
            WriteRemediationContract contract,
            ToolEffect effect) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", "verify");
        report.put("toolName", toolName == null ? "" : toolName);

        WriteRemediationContract c = contract != null
                ? contract
                : RemediationContractFactory.build(toolName, requestParameters, effect, "");
        report.put("expectedEffect", c.expectedEffect().toMap());
        report.put("recover", c.recover().toMap());
        report.put("approval", c.approval().toMap());
        report.put("reversible", c.recover().reversible());

        Map<String, Object> body = executionBody == null ? Map.of() : executionBody;
        boolean success = Boolean.TRUE.equals(body.get("success"));
        String execJson = serialize(body);
        boolean requestedWrite = WriteToolResultSupport.requestedRealWrite(
                requestParameters == null ? Map.of() : requestParameters);

        if (!requestedWrite || (effect != null && !effect.writeEffect())) {
            report.put("verified", success);
            report.put("verifyOutcome", success ? VerifyOutcome.VERIFY_SKIPPED.name() : VerifyOutcome.VERIFY_FAILED.name());
            report.put("requestedWrite", false);
            report.put("message", success ? "非真实写或只读路径，跳过业务验证" : "执行未成功");
            attachPreview(report, dryRunPreviewJson);
            return report;
        }

        report.put("requestedWrite", true);
        boolean realWriteEvidence = WriteToolResultSupport.isConfirmedRealWrite(execJson);
        report.put("realWriteEvidence", realWriteEvidence);

        if (Boolean.TRUE.equals(body.get("writeMismatch"))) {
            return fail(report, VerifyOutcome.VERIFY_FAILED, "写操作与预览不一致（writeMismatch）", dryRunPreviewJson);
        }
        if (!success) {
            return fail(report, VerifyOutcome.VERIFY_FAILED, "执行失败，业务验证未通过", dryRunPreviewJson);
        }
        if (Boolean.FALSE.equals(extractBoolean(extractData(execJson), "protectedFilesPreserved"))) {
            return fail(report, VerifyOutcome.VERIFY_FAILED,
                    "受保护文件保全校验失败，已阻止将本次清理记为成功", dryRunPreviewJson);
        }

        EvidenceContractValidator.ValidationResult evidence =
                EvidenceContractValidator.validateRequestedWrite(effect, true, execJson);
        report.put("evidenceComplete", evidence.complete());
        report.put("evidenceContractId", evidence.contractId());
        if (!evidence.missingFields().isEmpty()) {
            report.put("evidenceMissingFields", evidence.missingFields());
        }
        if (!evidence.complete() || !realWriteEvidence) {
            String msg = evidence.message() == null || evidence.message().isBlank()
                    ? "执行成功但缺少写落地证据，请复核工具返回"
                    : evidence.message();
            return fail(report, VerifyOutcome.EVIDENCE_INCOMPLETE, msg, dryRunPreviewJson);
        }

        BusinessCheck business = checkBusinessExpectation(c.expectedEffect(), execJson);
        report.put("businessCheck", business.toMap());
        List<String> handles = extractRecycleHandles(execJson);
        if (!handles.isEmpty()) {
            report.put("recover", c.recover().withHandles(handles).toMap());
        }
        if (business.outcome() != VerifyOutcome.VERIFIED) {
            return fail(report, business.outcome(), business.message(), dryRunPreviewJson);
        }

        BusinessCheck measured = checkMeasuredEffect(body, c.expectedEffect());
        report.put("measuredEffectCheck", measured.toMap());
        if (measured.outcome() != VerifyOutcome.VERIFIED) {
            return fail(report, measured.outcome(), measured.message(), dryRunPreviewJson);
        }

        report.put("verified", true);
        report.put("verifyOutcome", VerifyOutcome.VERIFIED.name());
        report.put("message", business.message().isBlank() ? "证据与预期变化校验通过" : business.message());
        attachPreview(report, dryRunPreviewJson);
        return report;
    }

    private static Map<String, Object> fail(
            Map<String, Object> report,
            VerifyOutcome outcome,
            String message,
            String dryRunPreviewJson) {
        report.put("verified", false);
        report.put("verifyOutcome", outcome.name());
        report.put("message", message);
        attachPreview(report, dryRunPreviewJson);
        return report;
    }

    private static void attachPreview(Map<String, Object> report, String dryRunPreviewJson) {
        if (dryRunPreviewJson != null && !dryRunPreviewJson.isBlank()) {
            report.put("previewPresent", true);
            report.put("previewHash", ConfirmTokenHmac.hashPreview(dryRunPreviewJson));
        }
    }

    private static BusinessCheck checkBusinessExpectation(ExpectedEffect expected, String execJson) {
        if (expected == null || "NONE".equals(expected.metric())) {
            return BusinessCheck.ok("无业务预期，仅校验写证据");
        }
        JsonNode data = extractData(execJson);
        String metric = expected.metric();
        if ("PATH_USAGE".equals(metric)) {
            long deleted = number(data, "filesDeleted", "deletedCount", "removedCount");
            long freed = number(data, "bytesFreed", "bytesDeleted", "bytesSelected");
            long recycled = number(data, "recycledCount", "recycleCount");
            boolean removedFlag = bool(data, "removed");
            boolean hasHandles = !extractRecycleHandles(execJson).isEmpty();
            if (deleted > 0 || freed > 0 || recycled > 0 || removedFlag || hasHandles) {
                return BusinessCheck.ok("路径占用相关指标已下降或已回收: deleted="
                        + deleted + ", freed=" + freed + ", recycled=" + recycled);
            }
            return BusinessCheck.weak("缺少 PATH_USAGE 下降硬证据（filesDeleted/bytesFreed/recycle）；不得记为处置成功");
        }
        if ("SERVICE_STATE".equals(metric)) {
            String after = text(data, "afterState", "status", "state", "ActiveState");
            String lower = after.toLowerCase(Locale.ROOT);
            if (lower.contains("active") || lower.contains("running") || lower.contains("ok")) {
                return BusinessCheck.ok("服务状态满足预期: " + after);
            }
            return BusinessCheck.weak("缺少 afterState=active/running 硬证据；不得记为处置成功，实际=" + after);
        }
        return BusinessCheck.ok("通用写效果已具备落地证据");
    }

    private static List<String> extractRecycleHandles(String execJson) {
        List<String> out = new ArrayList<>();
        JsonNode data = extractData(execJson);
        if (data == null) {
            return out;
        }
        if (data.has("recycleHandle") && data.get("recycleHandle").isTextual()) {
            String h = data.get("recycleHandle").asText("");
            if (!h.isBlank()) {
                out.add(h);
            }
        }
        JsonNode handles = data.get("recycleHandles");
        if (handles != null && handles.isArray()) {
            handles.forEach(n -> {
                if (n != null && n.isTextual() && !n.asText("").isBlank()) {
                    out.add(n.asText());
                }
            });
        }
        JsonNode preview = data.get("preview");
        if (preview != null && preview.isArray()) {
            preview.forEach(n -> {
                if (n != null && n.has("recycleHandle")) {
                    String h = n.path("recycleHandle").asText("");
                    if (!h.isBlank()) {
                        out.add(h);
                    }
                }
            });
        }
        return List.copyOf(out);
    }

    private static JsonNode extractData(String toolResultJson) {
        return WriteToolResultSupport.businessDataFromToolPayload(toolResultJson);
    }

    private static long number(JsonNode data, String... fields) {
        if (data == null) {
            return 0L;
        }
        for (String f : fields) {
            if (data.has(f) && data.get(f).isNumber()) {
                return data.get(f).asLong(0L);
            }
            if (data.has(f) && data.get(f).isTextual()) {
                try {
                    return Long.parseLong(data.get(f).asText("0").trim());
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return 0L;
    }

    private static String text(JsonNode data, String... fields) {
        if (data == null) {
            return "";
        }
        for (String f : fields) {
            if (data.has(f) && !data.get(f).asText("").isBlank()) {
                return data.get(f).asText("");
            }
        }
        return "";
    }

    private static boolean bool(JsonNode data, String field) {
        return data != null && data.path(field).asBoolean(false);
    }

    private static Boolean extractBoolean(JsonNode data, String field) {
        if (data == null || !data.has(field)) {
            return null;
        }
        return data.path(field).asBoolean(false);
    }

    /** 执行后只读复测：候选数必须下降，且复测本身必须可用。 */
    private static BusinessCheck checkMeasuredEffect(
            Map<String, Object> executionBody, ExpectedEffect expected) {
        if (executionBody == null || expected == null || !"PATH_USAGE".equals(expected.metric())
                || !(executionBody.get("metricsBefore") instanceof Map<?, ?> before)
                || !(executionBody.get("metricsAfter") instanceof Map<?, ?> after)) {
            return BusinessCheck.ok("未配置路径复测指标，沿用工具写证据校验");
        }
        if (!Boolean.TRUE.equals(after.get("available"))) {
            return BusinessCheck.incomplete("执行后路径指标复测不可用，不得将清理记为已验证");
        }
        long beforeCount = metricNumber(before, "deletableCount", "filesFound");
        long afterCount = metricNumber(after, "deletableCount", "filesFound");
        long deleted = number(extractData(serialize(executionBody)), "filesDeleted", "deletedCount");
        if (deleted > 0 && afterCount < beforeCount) {
            return BusinessCheck.ok("执行后复测通过：候选数 " + beforeCount + " -> " + afterCount);
        }
        if (deleted == 0 && beforeCount == 0 && afterCount == 0) {
            return BusinessCheck.ok("执行前后均无可清理候选");
        }
        return BusinessCheck.weak("执行后复测未证明路径占用下降：候选数 "
                + beforeCount + " -> " + afterCount);
    }

    private static long metricNumber(Map<?, ?> values, String... fields) {
        for (String field : fields) {
            Object value = values.get(field);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // 尝试下一个候选字段。
                }
            }
        }
        return 0L;
    }

    private static String serialize(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    private record BusinessCheck(VerifyOutcome outcome, String message) {
        static BusinessCheck ok(String message) {
            return new BusinessCheck(VerifyOutcome.VERIFIED, message == null ? "" : message);
        }

        static BusinessCheck weak(String message) {
            return new BusinessCheck(VerifyOutcome.VERIFY_WEAK, message == null ? "" : message);
        }

        static BusinessCheck incomplete(String message) {
            return new BusinessCheck(VerifyOutcome.EVIDENCE_INCOMPLETE, message == null ? "" : message);
        }

        static BusinessCheck fail(String message) {
            return new BusinessCheck(VerifyOutcome.VERIFY_FAILED, message == null ? "" : message);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", outcome == VerifyOutcome.VERIFIED);
            m.put("outcome", outcome.name());
            m.put("message", message);
            return m;
        }
    }
}
