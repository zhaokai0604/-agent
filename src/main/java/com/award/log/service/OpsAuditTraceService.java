package com.award.log.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维 Agent 全链路审计落库（与 TraceService 配合，面向赛题「可追溯」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAuditTraceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ops_audit_trace (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "trace_id VARCHAR(64) NOT NULL," +
                        "channel VARCHAR(32) NOT NULL," +
                        "user_input TEXT," +
                        "risk_level VARCHAR(32)," +
                        "security_outcome VARCHAR(64)," +
                        "tool_name VARCHAR(128)," +
                        "execution_ok TINYINT(1) DEFAULT 0," +
                        "result_summary TEXT," +
                        "steps_json LONGTEXT," +
                        "duration_ms BIGINT," +
                        "operator_user_id VARCHAR(64)," +
                        "policy_version VARCHAR(64)," +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "KEY idx_trace_id (trace_id)," +
                        "KEY idx_created_at (created_at)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
        migrateLegacyTable();
    }

    /** 已有库升级列（忽略重复列错误）。 */
    private void migrateLegacyTable() {
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN operator_user_id VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN policy_version VARCHAR(64) NULL");
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("migrate skip: {}", e.getMessage());
        }
    }

    public void save(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion
    ) {
        try {
            String stepsJson = steps == null || steps.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(steps);
            String summary = resultSummary == null ? "" : resultSummary;
            if (summary.length() > 4000) {
                summary = summary.substring(0, 4000) + "...";
            }
            jdbcTemplate.update(
                    "INSERT INTO ops_audit_trace(trace_id,channel,user_input,risk_level,security_outcome," +
                            "tool_name,execution_ok,result_summary,steps_json,duration_ms,operator_user_id,policy_version,created_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    traceId,
                    channel,
                    userInput,
                    riskLevel,
                    securityOutcome,
                    toolName,
                    executionOk ? 1 : 0,
                    summary,
                    stepsJson,
                    durationMs,
                    operatorUserId,
                    policyVersion,
                    new Timestamp(System.currentTimeMillis())
            );
        } catch (JsonProcessingException e) {
            log.warn("审计序列化失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("审计落库失败（不阻断业务）: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        try {
            return jdbcTemplate.query(
                    "SELECT id, trace_id, channel, user_input, risk_level, security_outcome, tool_name, " +
                            "execution_ok, LEFT(result_summary, 512) AS result_summary, duration_ms, " +
                            "operator_user_id, policy_version, created_at " +
                            "FROM ops_audit_trace ORDER BY id DESC LIMIT ?",
                    (rs, rowNum) -> mapTraceRow(rs, false),
                    cap
            );
        } catch (Exception e) {
            log.warn("查询审计列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 单条追溯：含完整摘要与 steps_json（思维链审计）。
     */
    public Map<String, Object> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT id, trace_id, channel, user_input, risk_level, security_outcome, tool_name, " +
                            "execution_ok, result_summary, duration_ms, operator_user_id, policy_version, " +
                            "created_at, steps_json FROM ops_audit_trace WHERE trace_id = ? ORDER BY id DESC LIMIT 1",
                    (rs, rowNum) -> mapTraceRow(rs, true),
                    traceId.trim()
            );
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            log.warn("查询审计详情失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> mapTraceRow(ResultSet rs, boolean includeStepsJson) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("traceId", rs.getString("trace_id"));
        m.put("channel", rs.getString("channel"));
        m.put("userInput", rs.getString("user_input"));
        m.put("riskLevel", rs.getString("risk_level"));
        m.put("securityOutcome", rs.getString("security_outcome"));
        m.put("toolName", rs.getString("tool_name"));
        m.put("executionOk", rs.getInt("execution_ok") == 1);
        m.put("durationMs", rs.getLong("duration_ms"));
        m.put("operatorUserId", rs.getString("operator_user_id"));
        m.put("policyVersion", rs.getString("policy_version"));
        m.put("createdAt", rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant().toString()
                : null);
        if (includeStepsJson) {
            m.put("resultSummary", rs.getString("result_summary"));
            String stepsRaw = rs.getString("steps_json");
            m.put("steps", parseStepsJson(stepsRaw));
            m.put("stepsJsonRaw", stepsRaw);
        } else {
            m.put("resultSummary", rs.getString("result_summary"));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseStepsJson(String stepsRaw) {
        if (stepsRaw == null || stepsRaw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stepsRaw, List.class);
        } catch (Exception e) {
            log.debug("steps_json 解析降级为字符串: {}", e.getMessage());
            return List.of(Map.of("phase", "raw", "detail", stepsRaw));
        }
    }
}
