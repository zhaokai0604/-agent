package com.award.log.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * AI 接口审计日志：启动时建表，记录调用方、路径、耗时与状态码。
 */
@Slf4j
@Service
public class AiAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public AiAuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    public void save(Object userId, Object role, String remoteIp, String method, String path, int status, long durationMs, int requestBytes) {
        jdbcTemplate.update(
                "INSERT INTO ai_audit_log(user_id,user_role,remote_ip,method,path,status,duration_ms,request_bytes,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId == null ? null : String.valueOf(userId),
                role == null ? null : String.valueOf(role),
                remoteIp,
                method,
                path,
                status,
                durationMs,
                requestBytes,
                new Timestamp(System.currentTimeMillis())
        );
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList("SELECT * FROM ai_audit_log ORDER BY id DESC LIMIT ?", size);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM ai_audit_log WHERE id = ? LIMIT 1", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 个人中心「登录 / 请求足迹」：按用户过滤 HTTP 审计记录 */
    public List<Map<String, Object>> listRecentByUserId(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT id, method, path, status, duration_ms, remote_ip, created_at FROM ai_audit_log "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                userId,
                size);
    }

    public List<Map<String, Object>> listRecentByUserId(String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safePageSize;
        return jdbcTemplate.queryForList(
                "SELECT id, method, path, status, duration_ms, remote_ip, created_at FROM ai_audit_log "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                userId,
                safePageSize,
                offset);
    }

    public long countByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_log WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** 访问审计聚合：保持 SQL 方言无关，适配 MySQL 与测试 H2。 */
    public Map<String, Object> summarizeAccess(int hours, String granularity) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 90));
        String unit = "week".equalsIgnoreCase(granularity) ? "week"
                : "day".equalsIgnoreCase(granularity) ? "day" : "hour";
        Instant since = Instant.now().minus(safeHours, ChronoUnit.HOURS);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT method,path,status,duration_ms,remote_ip,user_id,created_at FROM ai_audit_log "
                        + "WHERE created_at >= ? ORDER BY created_at DESC LIMIT 10000",
                Timestamp.from(since));

        Map<String, MutableAggregate> byType = new LinkedHashMap<>();
        Map<String, MutableAggregate> byBucket = new LinkedHashMap<>();
        Map<String, Integer> endpoints = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> remoteIps = new LinkedHashSet<>();
        int slowRequestCount = 0;
        for (Map<String, Object> row : rows) {
            String type = accessType(String.valueOf(row.getOrDefault("method", "GET")),
                    String.valueOf(row.getOrDefault("path", "")));
            MutableAggregate typeAgg = byType.computeIfAbsent(type, ignored -> new MutableAggregate());
            typeAgg.accept(row);
            String bucket = bucket(row.get("created_at"), unit);
            byBucket.computeIfAbsent(bucket, ignored -> new MutableAggregate()).accept(row);
            String endpoint = String.valueOf(row.getOrDefault("path", ""));
            endpoints.merge(endpoint, 1, Integer::sum);
            String status = String.valueOf(row.getOrDefault("status", 0));
            statusCounts.merge(status, 1, Integer::sum);
            String userId = String.valueOf(row.getOrDefault("user_id", ""));
            if (!userId.isBlank() && !"null".equalsIgnoreCase(userId)) userIds.add(userId);
            String remoteIp = String.valueOf(row.getOrDefault("remote_ip", ""));
            if (!remoteIp.isBlank() && !"null".equalsIgnoreCase(remoteIp)) remoteIps.add(remoteIp);
            if (row.get("duration_ms") instanceof Number duration && duration.longValue() >= 1000L) slowRequestCount++;
        }
        MutableAggregate total = new MutableAggregate();
        rows.forEach(total::accept);
        List<Map<String, Object>> topEndpoints = endpoints.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .map(e -> Map.<String, Object>of("path", e.getKey(), "requestCount", e.getValue()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rangeHours", safeHours);
        result.put("granularity", unit);
        result.put("summary", total.toMap());
        result.put("groups", byType.entrySet().stream()
                .map(e -> aggregateRow(e.getKey(), e.getValue())).toList());
        result.put("series", byBucket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> value = new LinkedHashMap<>(e.getValue().toMap());
                    value.put("bucket", e.getKey());
                    return value;
                }).toList());
        result.put("topEndpoints", topEndpoints);
        result.put("statusCounts", statusCounts);
        result.put("uniqueUserCount", userIds.size());
        result.put("uniqueIpCount", remoteIps.size());
        result.put("slowRequestCount", slowRequestCount);
        return result;
    }

    private static Map<String, Object> aggregateRow(String type, MutableAggregate value) {
        Map<String, Object> row = new LinkedHashMap<>(value.toMap());
        row.put("type", type);
        row.put("label", switch (type) {
            case "login" -> "登录";
            case "dialogue" -> "运维对话";
            case "tool" -> "运维工具";
            case "security" -> "安全策略";
            case "automation" -> "自动化任务";
            default -> "接口访问";
        });
        return row;
    }

    private static String accessType(String method, String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if ("LOGIN".equalsIgnoreCase(method) || "KEY_LOGIN".equalsIgnoreCase(method) || normalized.contains("/login")) return "login";
        if (normalized.contains("/assistant/") || normalized.contains("/llm/")) return "dialogue";
        if (normalized.contains("/mcp/") || normalized.contains("/tools")) return "tool";
        if (normalized.contains("/security/") || normalized.contains("/policy")) return "security";
        if (normalized.contains("/patrol/") || normalized.contains("/schedule") || normalized.contains("/workflow")) return "automation";
        return "api";
    }

    private static String bucket(Object timestamp, String unit) {
        Instant instant = timestamp instanceof Timestamp t ? t.toInstant() : Instant.now();
        if ("week".equals(unit)) return instant.truncatedTo(ChronoUnit.DAYS).minus(instant.atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() - 1L, ChronoUnit.DAYS).toString();
        return ("day".equals(unit) ? instant.truncatedTo(ChronoUnit.DAYS) : instant.truncatedTo(ChronoUnit.HOURS)).toString();
    }

    private static final class MutableAggregate {
        int total;
        int success;
        int failed;
        long duration;
        long maxDuration;

        void accept(Map<String, Object> row) {
            total++;
            int status = row.get("status") instanceof Number n ? n.intValue() : 0;
            if (status >= 400 || status == 0) failed++; else success++;
            long currentDuration = row.get("duration_ms") instanceof Number n ? n.longValue() : 0L;
            duration += currentDuration;
            maxDuration = Math.max(maxDuration, currentDuration);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestCount", total);
            out.put("successCount", success);
            out.put("failureCount", failed);
            out.put("successRate", total == 0 ? 0D : Math.round(success * 10000D / total) / 100D);
            out.put("avgDurationMs", total == 0 ? 0L : Math.round((double) duration / total));
            out.put("maxDurationMs", maxDuration);
            return out;
        }
    }
}
