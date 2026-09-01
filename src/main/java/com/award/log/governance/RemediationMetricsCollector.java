package com.award.log.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 受控写操作的最小指标采集器。
 *
 * <p>只保留用于证明处置效果的稳定字段，避免把工具的完整 preview 列表
 * 再复制进审计记录。指标来源分为执行前预览和执行后只读复测。</p>
 */
public final class RemediationMetricsCollector {

    private static final Set<String> METRIC_FIELDS = Set.of(
            "path", "service", "serviceName", "unit", "mode", "status", "state", "afterState",
            "filesFound", "deletableCount", "protectedSkipped", "filesDeleted", "lockedSkipped",
            "bytesFreed", "recycledCount", "recycleCount", "removed", "changed", "success",
            "protectedFilesPreserved");

    private RemediationMetricsCollector() {
    }

    public static Map<String, Object> fromResponse(
            ObjectMapper mapper, String toolName, Map<String, Object> response, String source) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("toolName", toolName == null ? "" : toolName);
        metrics.put("source", source == null ? "" : source);
        metrics.put("capturedAtMs", System.currentTimeMillis());
        if (response == null) {
            metrics.put("available", false);
            return metrics;
        }
        metrics.put("available", Boolean.TRUE.equals(response.get("success")));
        if (response.get("status") != null) {
            metrics.put("responseStatus", response.get("status"));
        }
        JsonNode data = dataNode(mapper, response.get("businessData"), response.get("data"));
        if (data == null || !data.isObject()) {
            return metrics;
        }
        METRIC_FIELDS.forEach(field -> {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    metrics.put(field, value.numberValue());
                } else if (value.isBoolean()) {
                    metrics.put(field, value.booleanValue());
                } else if (value.isTextual() && !value.asText().isBlank()) {
                    metrics.put(field, value.asText());
                }
            }
        });
        return metrics;
    }

    public static Map<String, Object> numericDelta(
            Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (before == null || after == null) {
            return delta;
        }
        Set<String> fields = Set.of(
                "filesFound", "deletableCount", "protectedSkipped", "filesDeleted", "lockedSkipped",
                "bytesFreed", "recycledCount", "recycleCount");
        for (String field : fields) {
            Number b = number(before.get(field));
            Number a = number(after.get(field));
            if (b != null || a != null) {
                delta.put(field, (a == null ? 0L : a.longValue()) - (b == null ? 0L : b.longValue()));
            }
        }
        return delta;
    }

    private static JsonNode dataNode(ObjectMapper mapper, Object businessData, Object rawData) {
        try {
            if (businessData instanceof Map<?, ?> map) {
                return mapper.valueToTree(map);
            }
            if (businessData != null && !(businessData instanceof String)) {
                return mapper.valueToTree(businessData);
            }
            if (rawData == null) {
                return null;
            }
            JsonNode raw = rawData instanceof String
                    ? mapper.readTree((String) rawData)
                    : mapper.valueToTree(rawData);
            if (raw != null && raw.isObject() && raw.has("data")) {
                JsonNode nested = raw.get("data");
                return nested.isTextual() ? mapper.readTree(nested.asText()) : nested;
            }
            return raw;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Number number(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 指标字段不是数字时不参与差值计算。
            }
        }
        return null;
    }
}
