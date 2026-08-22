package com.award.log.security.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归一化后的安全信号：来源类型、严重度、主机/IP 与原始载荷。
 * 供窗口聚合与威胁摘要使用，不绑定某一厂商事件格式。
 */
public record SecuritySignal(
        String signalId,
        String sourceType,
        String eventType,
        String title,
        String severity,
        int severityScore,
        double confidence,
        String sensorId,
        String host,
        String srcIp,
        String dstIp,
        String serviceName,
        String processName,
        String filePath,
        long eventTimeMs,
        long ingestTimeMs,
        boolean blocked,
        List<String> tags,
        String detail,
        String rawPayload) {

    /** 严重度分 ≥90 或 CRITICAL。 */
    public boolean isCritical() {
        return severityScore >= 90 || "CRITICAL".equalsIgnoreCase(severity);
    }

    /** 严重度分 ≥70 或 HIGH/CRITICAL。 */
    public boolean isHighOrAbove() {
        return severityScore >= 70
                || "HIGH".equalsIgnoreCase(severity)
                || "CRITICAL".equalsIgnoreCase(severity);
    }

    /** 事件时间优先，缺失则用摄入时间。 */
    public long effectiveTimeMs() {
        return eventTimeMs > 0 ? eventTimeMs : ingestTimeMs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signalId", signalId);
        out.put("sourceType", sourceType);
        out.put("eventType", eventType);
        out.put("title", title);
        out.put("severity", severity);
        out.put("severityScore", severityScore);
        out.put("confidence", confidence);
        out.put("sensorId", sensorId);
        out.put("host", host);
        out.put("srcIp", srcIp);
        out.put("dstIp", dstIp);
        out.put("serviceName", serviceName);
        out.put("processName", processName);
        out.put("filePath", filePath);
        out.put("eventTimeMs", eventTimeMs);
        out.put("ingestTimeMs", ingestTimeMs);
        out.put("blocked", blocked);
        out.put("tags", tags);
        out.put("detail", detail);
        out.put("rawPayload", rawPayload);
        return out;
    }
}
