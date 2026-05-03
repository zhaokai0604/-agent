package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.KafkaMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/platform")
public class PlatformReadinessController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private KafkaMonitorService kafkaMonitorService;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    @GetMapping("/readiness")
    public Result<Map<String, Object>> readiness() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpSession session = attrs == null ? null : attrs.getRequest().getSession(false);
        Object role = session == null ? null : session.getAttribute(com.award.log.security.AuthInterceptor.SESSION_USER_ROLE);
        if (!(role instanceof Number) || ((Number) role).intValue() != 1) {
            return Result.error(403, "仅管理员可访问平台就绪检查");
        }
        Map<String, Object> checks = new HashMap<>();
        checks.put("database", checkDatabase());
        checks.put("redis", checkRedis());
        checks.put("kafka", checkKafka());
        checks.put("ai", checkAiConfig());

        boolean allUp = checks.values().stream()
                .allMatch(v -> v instanceof Map && "UP".equals(((Map<?, ?>) v).get("status")));

        Map<String, Object> result = new HashMap<>();
        result.put("status", allUp ? "UP" : "DEGRADED");
        result.put("checks", checks);
        result.put("timestamp", System.currentTimeMillis());
        return Result.success(result);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> r = new HashMap<>();
        try {
            Integer v = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            r.put("status", (v != null && v == 1) ? "UP" : "DOWN");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkRedis() {
        Map<String, Object> r = new HashMap<>();
        if (stringRedisTemplate == null) {
            r.put("status", "DOWN");
            r.put("error", "Redis template unavailable");
            return r;
        }
        try {
            String pong = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            r.put("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkKafka() {
        Map<String, Object> r = new HashMap<>();
        if (kafkaMonitorService == null) {
            r.put("status", "DOWN");
            r.put("error", "Kafka monitor unavailable");
            return r;
        }
        try {
            Map<String, Object> snapshot = kafkaMonitorService.snapshot();
            boolean online = Boolean.TRUE.equals(snapshot.get("online"));
            r.put("status", online ? "UP" : "DOWN");
            r.put("brokers", snapshot.get("brokers"));
            if (!online) {
                r.put("error", snapshot.get("error"));
            }
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkAiConfig() {
        Map<String, Object> r = new HashMap<>();
        boolean configured = aiApiKey != null && !aiApiKey.isBlank();
        r.put("status", configured ? "UP" : "DOWN");
        r.put("configured", configured);
        return r;
    }
}
