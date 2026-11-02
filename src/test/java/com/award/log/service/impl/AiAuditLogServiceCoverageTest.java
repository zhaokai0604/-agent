package com.award.log.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAuditLogServiceCoverageTest {

    @Test
    void summarizeAccessAggregatesAllBusinessAccessTypesAndGranularities() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiAuditLogService service = new AiAuditLogService(jdbc);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("LOGIN", "/api/login", 200, 10L, "u1", "10.0.0.1"));
        rows.add(row("GET", "/api/assistant/chat", 200, 1200L, "u1", "10.0.0.1"));
        rows.add(row("POST", "/api/mcp/execute", 500, 2000L, "u2", "10.0.0.2"));
        rows.add(row("GET", "/api/security/policy", 403, 20L, "", ""));
        rows.add(row("GET", "/api/patrol/workflows", 200, 30L, "null", "null"));
        rows.add(row("GET", "/api/health", 0, "unknown", null, null));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

        Map<String, Object> hourly = service.summarizeAccess(0, "hour");
        assertEquals(1, hourly.get("rangeHours"));
        assertEquals("hour", hourly.get("granularity"));
        assertEquals(6, ((Map<?, ?>) hourly.get("summary")).get("requestCount"));
        assertEquals(6, ((List<?>) hourly.get("groups")).size());
        assertEquals(2, hourly.get("slowRequestCount"));
        assertEquals(2, hourly.get("uniqueUserCount"));
        assertEquals(2, hourly.get("uniqueIpCount"));
        assertTrue(((Map<?, ?>) hourly.get("statusCounts")).containsKey("403"));

        Map<String, Object> daily = service.summarizeAccess(9000, "day");
        assertEquals(24 * 90, daily.get("rangeHours"));
        assertEquals("day", daily.get("granularity"));
        Map<String, Object> weekly = service.summarizeAccess(5, "week");
        assertEquals("week", weekly.get("granularity"));
    }

    @Test
    void blankUserAndEmptyQueriesRemainSafe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiAuditLogService service = new AiAuditLogService(jdbc);
        service.save(null, null, null, "GET", "/", 200, 0L, 0);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertTrue(service.findById(1).isEmpty());
        assertTrue(service.listRecent(-1).isEmpty());
        assertTrue(service.listRecentByUserId(null, 10).isEmpty());
        assertTrue(service.listRecentByUserId(" ", 1, 1).isEmpty());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
        assertEquals(0L, service.countByUserId("u1"));
    }

    private static Map<String, Object> row(String method, String path, int status,
                                           Object duration, String userId, String remoteIp) {
        Map<String, Object> row = new HashMap<>();
        row.put("method", method);
        row.put("path", path);
        row.put("status", status);
        row.put("duration_ms", duration);
        row.put("user_id", userId);
        row.put("remote_ip", remoteIp);
        row.put("created_at", Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")));
        return row;
    }
}
