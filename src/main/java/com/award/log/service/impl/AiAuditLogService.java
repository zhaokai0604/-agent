package com.award.log.service.impl;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class AiAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public AiAuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ai_audit_log (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "user_id VARCHAR(64)," +
                        "user_role VARCHAR(32)," +
                        "remote_ip VARCHAR(64)," +
                        "method VARCHAR(16)," +
                        "path VARCHAR(255)," +
                        "status INT," +
                        "duration_ms BIGINT," +
                        "request_bytes INT," +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")"
        );
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
}
