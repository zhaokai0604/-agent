package com.award.log.service;

import com.alibaba.fastjson.JSON;
import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检历史持久化（ops_patrol_history）。
 */
@Slf4j
@Service
public class PatrolHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public PatrolHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureTable() {
        try {
            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ops_patrol_history (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      patrol_time DATETIME NOT NULL,
                      finding_count INT NOT NULL DEFAULT 0,
                      correlation_json LONGTEXT,
                      findings_json LONGTEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_patrol_time (patrol_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维巡检历史'
                    """);
        } catch (Exception e) {
            log.warn("创建 ops_patrol_history 表失败（可能无库权限）: {}", e.getMessage());
        }
    }

    public void saveRun(PatrolCorrelationSnapshot correlation, List<PatrolFinding> findings) {
        try {
            String corrJson = JSON.toJSONString(correlation != null ? correlation.toMap() : Map.of());
            List<Map<String, Object>> findingMaps = findings == null
                    ? List.of()
                    : findings.stream().map(PatrolFinding::toMap).toList();
            String findingsJson = JSON.toJSONString(findingMaps);
            jdbcTemplate.update(
                    "INSERT INTO ops_patrol_history(patrol_time, finding_count, correlation_json, findings_json) VALUES (?,?,?,?)",
                    LocalDateTime.now(),
                    findingMaps.size(),
                    corrJson,
                    findingsJson);
        } catch (Exception e) {
            log.warn("持久化巡检历史失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listHistory(int days, int limit) {
        int d = Math.min(30, Math.max(1, days));
        int n = Math.min(200, Math.max(1, limit));
        try {
            return jdbcTemplate.query(
                    """
                    SELECT id, patrol_time, finding_count, correlation_json, findings_json, created_at
                    FROM ops_patrol_history
                    WHERE patrol_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    ORDER BY patrol_time DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("patrolTime", rs.getTimestamp("patrol_time").toLocalDateTime().toString());
                        row.put("findingCount", rs.getInt("finding_count"));
                        row.put("correlation", JSON.parseObject(rs.getString("correlation_json")));
                        row.put("findings", JSON.parseArray(rs.getString("findings_json")));
                        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
                        return row;
                    },
                    d,
                    n);
        } catch (Exception e) {
            log.warn("查询巡检历史失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> countByDay(int days) {
        int d = Math.min(30, Math.max(1, days));
        try {
            return jdbcTemplate.query(
                    """
                    SELECT DATE(patrol_time) AS day, SUM(finding_count) AS alert_count, COUNT(*) AS run_count
                    FROM ops_patrol_history
                    WHERE patrol_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    GROUP BY DATE(patrol_time)
                    ORDER BY day ASC
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("day", rs.getDate("day").toString());
                        row.put("alertCount", rs.getLong("alert_count"));
                        row.put("runCount", rs.getLong("run_count"));
                        return row;
                    },
                    d);
        } catch (Exception e) {
            log.warn("巡检趋势统计失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
