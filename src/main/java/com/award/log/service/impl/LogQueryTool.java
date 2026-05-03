package com.award.log.service.impl;

import com.award.log.model.LogDocument;
import com.award.log.service.AiTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LogQueryTool implements AiTool {

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    @Override
    public String getName() {
        return "query_logs";
    }

    @Override
    public String getDescription() {
        return "查询Elasticsearch中的日志数据，支持按时间范围、严重级别、关键词等条件查询。可以返回日志列表、统计信息等。";
    }

    @Override
    public String getParameterDescription() {
        return "{\"timeRange\":\"时间范围，如'1小时'、'30分钟'、'今天'、'昨天'等(可选), \"severity\":\"严重级别，如'ERROR'、'WARNING'、'FATAL'等(可选)\", \"keywords\":\"搜索关键词，如'null'、'exception'、'timeout'等(可选)\", \"service\":\"服务名称，如'mysql'、'redis'、'nginx'等(可选)\", \"limit\":\"返回数量，默认50条(可选)\"}";
    }

    @Override
    public Map<String, Object> getFunctionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("name", getName());
        schema.put("description", getDescription());

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> timeRange = new HashMap<>();
        timeRange.put("type", "string");
        timeRange.put("description", "时间范围，如'1小时'、'30分钟'、'今天'、'昨天'、'过去3天'等");
        properties.put("timeRange", timeRange);

        Map<String, Object> severity = new HashMap<>();
        severity.put("type", "string");
        severity.put("description", "严重级别：ERROR、WARN、INFO、FATAL、DEBUG");
        properties.put("severity", severity);

        Map<String, Object> keywords = new HashMap<>();
        keywords.put("type", "string");
        keywords.put("description", "搜索关键词，如'null'、'exception'、'timeout'等");
        properties.put("keywords", keywords);

        Map<String, Object> service = new HashMap<>();
        service.put("type", "string");
        service.put("description", "服务名称，如'mysql'、'redis'、'nginx'等");
        properties.put("service", service);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回日志数量，默认50条");
        limit.put("default", 50);
        properties.put("limit", limit);

        schema.put("parameters", properties);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (elasticsearchOperations == null) {
            return ToolResult.error("日志查询服务暂时不可用。请确保 Elasticsearch 已启动且应用不在 dev 环境下运行。");
        }

        try {
            String timeRange = getStringParam(parameters, "timeRange", "最近1小时");
            String severity = getStringParam(parameters, "severity", null);
            String keywords = getStringParam(parameters, "keywords", null);
            String service = getStringParam(parameters, "service", null);
            int limit = getIntParam(parameters, "limit", 50);

            LocalDateTime[] timeRange2 = parseTimeRange(timeRange);
            LocalDateTime startTime = timeRange2[0];
            LocalDateTime endTime = timeRange2[1];

            List<LogDocument> logs = queryLogs(startTime, endTime, severity, keywords, service, limit);

            if (logs.isEmpty()) {
                return ToolResult.success("在指定条件下没有找到日志记录。\n" +
                        "时间范围: " + timeRange + "\n" +
                        "严重级别: " + (severity != null ? severity : "全部") + "\n" +
                        "关键词: " + (keywords != null ? keywords : "无") + "\n" +
                        "服务: " + (service != null ? service : "全部"));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("totalCount", logs.size());
            result.put("timeRange", timeRange);
            result.put("startTime", startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            result.put("endTime", endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            Map<String, Long> severityCount = logs.stream()
                    .collect(Collectors.groupingBy(l -> l.getSeverity() != null ? l.getSeverity() : "UNKNOWN", Collectors.counting()));
            result.put("severityDistribution", severityCount);

            List<Map<String, Object>> logEntries = logs.stream().limit(20).map(log -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("timestamp", log.getTimestamp() != null ? log.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "N/A");
                entry.put("severity", log.getSeverity());
                entry.put("content", log.getContent());
                entry.put("protocol", log.getProtocol());
                entry.put("source", log.getSource());
                entry.put("anomaly", log.getAnomaly());
                return entry;
            }).collect(Collectors.toList());
            result.put("logs", logEntries);

            StringBuilder response = new StringBuilder();
            response.append(String.format("查询结果：共找到 **%d** 条日志（显示前%d条）\n\n", logs.size(), Math.min(logs.size(), 20)));

            response.append("**时间范围**: ").append(startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append(" 至 ").append(endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

            response.append("**严重级别分布**:\n");
            severityCount.forEach((sev, count) ->
                    response.append(String.format("- %s: %d 条\n", sev, count)));
            response.append("\n");

            response.append("**日志详情**:\n");
            for (int i = 0; i < Math.min(logs.size(), 10); i++) {
                LogDocument log = logs.get(i);
                String content = log.getContent() != null && log.getContent().length() > 120
                        ? log.getContent().substring(0, 120) + "..."
                        : (log.getContent() != null ? log.getContent() : "N/A");
                response.append(String.format("**[%s]** %s\n", log.getSeverity(), log.getTimestamp() != null ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "N/A"));
                response.append("> ").append(content).append("\n\n");
            }

            if (logs.size() > 10) {
                response.append(String.format("_还有 %d 条日志未显示_", logs.size() - 10));
            }

            return ToolResult.success(response.toString(), result);

        } catch (Exception e) {
            log.error("日志查询工具执行失败", e);
            return ToolResult.error("查询失败: " + e.getMessage());
        }
    }

    private List<LogDocument> queryLogs(LocalDateTime startTime, LocalDateTime endTime,
                                        String severity, String keywords, String service, int limit) {
        try {
            Criteria criteria = new Criteria("timestamp").between(startTime, endTime);

            if (severity != null && !severity.isEmpty()) {
                criteria = criteria.and(new Criteria("severity").is(severity));
            }

            if (keywords != null && !keywords.isEmpty()) {
                criteria = criteria.and(new Criteria("content").contains(keywords));
            }

            if (service != null && !service.isEmpty()) {
                criteria = criteria.and(new Criteria("protocol").is(service));
            }

            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(limit);

            SearchHits<LogDocument> searchHits = elasticsearchOperations.search(query, LogDocument.class);

            List<LogDocument> logs = new ArrayList<>();
            for (SearchHit<LogDocument> hit : searchHits.getSearchHits()) {
                try {
                    logs.add(hit.getContent());
                } catch (Exception e) {
                    log.warn("跳过无法解析的日志记录: {}", e.getMessage());
                }
            }

            return logs;
        } catch (Exception e) {
            log.error("ES查询失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private LocalDateTime[] parseTimeRange(String timeRange) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime;

        if (timeRange == null || timeRange.isEmpty() || timeRange.contains("1小时")) {
            startTime = now.minusHours(1);
        } else if (timeRange.contains("30分钟")) {
            startTime = now.minusMinutes(30);
        } else if (timeRange.contains("2小时")) {
            startTime = now.minusHours(2);
        } else if (timeRange.contains("今天")) {
            startTime = now.toLocalDate().atStartOfDay();
        } else if (timeRange.contains("昨天")) {
            startTime = now.minusDays(1).toLocalDate().atStartOfDay();
        } else if (timeRange.contains("天")) {
            int days = extractNumber(timeRange);
            startTime = now.minusDays(days);
        } else if (timeRange.contains("周")) {
            startTime = now.minusWeeks(1);
        } else if (timeRange.contains("月")) {
            startTime = now.minusMonths(1);
        } else {
            startTime = now.minusHours(1);
        }

        return new LocalDateTime[]{startTime, now};
    }

    private int extractNumber(String text) {
        return text.replaceAll("[^0-9]", "").isEmpty() ? 1 :
                Integer.parseInt(text.replaceAll("[^0-9]", ""));
    }

    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}