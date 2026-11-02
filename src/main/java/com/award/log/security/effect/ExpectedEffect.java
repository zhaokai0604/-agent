package com.award.log.security.effect;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 写前声明的预期变化（可机读）。
 */
public record ExpectedEffect(
        String metric,
        String direction,
        String targetValue,
        String description
) {
    public ExpectedEffect {
        metric = norm(metric, "NONE");
        direction = norm(direction, "NONE");
        targetValue = targetValue == null ? "" : targetValue.trim();
        description = description == null ? "" : description.trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("direction", direction);
        m.put("targetValue", targetValue);
        m.put("description", description);
        return m;
    }

    public static ExpectedEffect none() {
        return new ExpectedEffect("NONE", "NONE", "", "无写效果预期");
    }

    public static ExpectedEffect diskOrPathDecrease(String pathHint) {
        String where = pathHint == null || pathHint.isBlank() ? "目标路径" : pathHint;
        return new ExpectedEffect(
                "PATH_USAGE",
                "DECREASE",
                "",
                "降低 " + where + " 占用（文件数或字节数）");
    }

    public static ExpectedEffect serviceActive(String service) {
        String unit = service == null || service.isBlank() ? "目标服务" : service;
        return new ExpectedEffect(
                "SERVICE_STATE",
                "EQUALS",
                "active",
                "重启后 " + unit + " 处于运行态（active/running）");
    }

    private static String norm(String v, String fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim().toUpperCase(Locale.ROOT);
    }
}
