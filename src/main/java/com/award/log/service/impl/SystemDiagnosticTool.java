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
public class SystemDiagnosticTool implements AiTool {

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    @Override
    public String getName() {
        return "diagnose_system";
    }

    @Override
    public String getDescription() {
        return "对系统进行综合诊断分析，包括CPU、内存、磁盘等性能指标，以及错误日志趋势、异常检测等，帮助定位系统问题根因。";
    }

    @Override
    public String getParameterDescription() {
        return "{\"timeRange\":\"诊断时间范围，如'1小时'、'30分钟'、'今天'等(可选，默认1小时)\", \"focusArea\":\"诊断重点，可选值:'performance'(性能)、'errors'(错误)、'anomaly'(异常)、'all'(全部，默认all)\"}";
    }

    @Override
    public Map<String, Object> getFunctionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("name", getName());
        schema.put("description", getDescription());

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> timeRange = new HashMap<>();
        timeRange.put("type", "string");
        timeRange.put("description", "诊断时间范围，如'1小时'、'30分钟'、'今天'、'昨天'、'过去3天'等");
        properties.put("timeRange", timeRange);

        Map<String, Object> focusArea = new HashMap<>();
        focusArea.put("type", "string");
        focusArea.put("description", "诊断重点：performance(性能)、errors(错误)、anomaly(异常)、all(全部)");
        focusArea.put("default", "all");
        properties.put("focusArea", focusArea);

        schema.put("parameters", properties);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (elasticsearchOperations == null) {
            return ToolResult.error("系统诊断服务暂时不可用。请确保 Elasticsearch 已启动且应用不在 dev 环境下运行。");
        }

        try {
            String timeRange = getStringParam(parameters, "timeRange", "1小时");
            String focusArea = getStringParam(parameters, "focusArea", "all");

            LocalDateTime[] timeRange2 = parseTimeRange(timeRange);
            LocalDateTime startTime = timeRange2[0];
            LocalDateTime endTime = timeRange2[1];

            StringBuilder diagnosis = new StringBuilder();
            diagnosis.append("## 系统诊断报告\n\n");
            diagnosis.append(String.format("**诊断时间**: %s 至 %s\n\n",
                    startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            if ("all".equals(focusArea) || "errors".equals(focusArea)) {
                diagnosis.append("### 错误日志分析\n");
                diagnosis.append(getErrorDiagnosis(startTime, endTime));
                diagnosis.append("\n\n");
            }

            if ("all".equals(focusArea) || "anomaly".equals(focusArea)) {
                diagnosis.append("### 异常检测分析\n");
                diagnosis.append(getAnomalyDiagnosis(startTime, endTime));
                diagnosis.append("\n\n");
            }

            diagnosis.append("### 诊断结论\n");
            diagnosis.append(generateConclusion(startTime, endTime));

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("timeRange", timeRange);
            resultData.put("startTime", startTime);
            resultData.put("endTime", endTime);
            resultData.put("focusArea", focusArea);

            return ToolResult.success(diagnosis.toString(), resultData);

        } catch (Exception e) {
            log.error("系统诊断工具执行失败", e);
            return ToolResult.error("诊断失败: " + e.getMessage());
        }
    }

    private String getErrorDiagnosis(LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder result = new StringBuilder();

        try {
            List<LogDocument> allLogs = queryLogs(startTime, endTime);

            List<LogDocument> fatalLogs = allLogs.stream()
                    .filter(l -> "FATAL".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(10)
                    .collect(Collectors.toList());

            List<LogDocument> errorLogs = allLogs.stream()
                    .filter(l -> "ERROR".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(20)
                    .collect(Collectors.toList());

            if (fatalLogs.isEmpty() && errorLogs.isEmpty()) {
                result.append("✓ 未检测到 ERROR 或 FATAL 级别日志，系统运行正常。\n");
                return result.toString();
            }

            if (!fatalLogs.isEmpty()) {
                result.append(String.format("⚠️ **%d 条 FATAL 级别错误**:\n", fatalLogs.size()));
                for (LogDocument log : fatalLogs.stream().limit(3).collect(Collectors.toList())) {
                    String content = truncate(log.getContent(), 100);
                    result.append(String.format("- [%s] %s\n",
                            log.getTimestamp() != null ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "N/A",
                            content));
                }
                if (fatalLogs.size() > 3) {
                    result.append(String.format("- ...还有 %d 条FATAL日志\n", fatalLogs.size() - 3));
                }
                result.append("\n");
            }

            if (!errorLogs.isEmpty()) {
                result.append(String.format("⚠️ **%d 条 ERROR 级别错误**:\n", errorLogs.size()));

                Map<String, Long> errorDistribution = errorLogs.stream()
                        .filter(log -> log.getContent() != null)
                        .collect(Collectors.groupingBy(
                                log -> extractErrorType(log.getContent()),
                                Collectors.counting()
                        ));

                errorDistribution.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(entry -> result.append(String.format("- %s: %d 次\n", entry.getKey(), entry.getValue())));

                result.append("\n最近5条ERROR日志:\n");
                for (LogDocument log : errorLogs.stream().limit(5).collect(Collectors.toList())) {
                    String content = truncate(log.getContent(), 80);
                    result.append(String.format("- [%s] %s\n",
                            log.getTimestamp() != null ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "N/A",
                            content));
                }
            }

        } catch (Exception e) {
            result.append("错误日志分析失败: ").append(e.getMessage());
        }

        return result.toString();
    }

    private String getAnomalyDiagnosis(LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder result = new StringBuilder();

        try {
            List<LogDocument> anomalyLogs = queryAnomalyLogs(startTime, endTime);

            if (anomalyLogs.isEmpty()) {
                result.append("✓ 未检测到异常行为日志。\n");
                return result.toString();
            }

            result.append(String.format("检测到 **%d 条异常日志**:\n\n", anomalyLogs.size()));

            anomalyLogs.stream().limit(10).forEach(log -> {
                String content = truncate(log.getContent(), 100);
                result.append(String.format("- **[异常 %s]** [%s] %s\n",
                        log.getAnomalyScore() != null ? String.format("%.2f", log.getAnomalyScore()) : "N/A",
                        log.getTimestamp() != null ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "N/A",
                        content));
            });

            if (anomalyLogs.size() > 10) {
                result.append(String.format("\n_还有 %d 条异常日志_", anomalyLogs.size() - 10));
            }

        } catch (Exception e) {
            result.append("异常检测分析失败: ").append(e.getMessage());
        }

        return result.toString();
    }

    private String generateConclusion(LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder conclusion = new StringBuilder();

        try {
            List<LogDocument> allLogs = queryLogs(startTime, endTime);

            List<LogDocument> fatalLogs = allLogs.stream()
                    .filter(l -> "FATAL".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(1)
                    .collect(Collectors.toList());

            List<LogDocument> errorLogs = allLogs.stream()
                    .filter(l -> "ERROR".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(10)
                    .collect(Collectors.toList());

            List<LogDocument> anomalyLogs = allLogs.stream()
                    .filter(l -> l.getAnomaly() != null && l.getAnomaly())
                    .limit(1)
                    .collect(Collectors.toList());

            boolean hasIssues = !fatalLogs.isEmpty() || errorLogs.size() > 5 || !anomalyLogs.isEmpty();

            if (!hasIssues) {
                conclusion.append("✓ **系统运行状态: 正常**\n\n");
                conclusion.append("在诊断时间范围内，未检测到严重错误或异常行为。建议：\n");
                conclusion.append("- 继续保持当前系统运行状态\n");
                conclusion.append("- 定期查看系统健康报告\n");
            } else {
                conclusion.append("⚠️ **系统存在潜在问题，建议关注**\n\n");

                if (!fatalLogs.isEmpty()) {
                    conclusion.append("**紧急事项**: 检测到 ").append(fatalLogs.size())
                            .append(" 条FATAL级别错误，需要立即处理！\n");
                }

                if (errorLogs.size() > 5) {
                    conclusion.append("**警告**: ERROR级别错误数量较多（共 ").append(errorLogs.size())
                            .append(" 条），建议进行详细分析。\n");
                }

                if (!anomalyLogs.isEmpty()) {
                    conclusion.append("**注意**: 检测到 ").append(anomalyLogs.size())
                            .append(" 条异常日志，可能存在潜在风险。\n");
                }

                conclusion.append("\n**建议操作**:\n");
                conclusion.append("1. 详细查看上述错误日志分析\n");
                conclusion.append("2. 使用「帮我分析这些错误的原因」获取详细诊断\n");
                conclusion.append("3. 检查相关服务的运行状态\n");
            }

        } catch (Exception e) {
            conclusion.append("无法生成诊断结论: ").append(e.getMessage());
        }

        return conclusion.toString();
    }

    private List<LogDocument> queryLogs(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            Criteria criteria = new Criteria("timestamp").between(startTime, endTime);
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(500);

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

    private List<LogDocument> queryAnomalyLogs(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            Criteria criteria = new Criteria("timestamp").between(startTime, endTime)
                    .and(new Criteria("anomaly").is(true));
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(20);

            SearchHits<LogDocument> searchHits = elasticsearchOperations.search(query, LogDocument.class);

            List<LogDocument> logs = new ArrayList<>();
            for (SearchHit<LogDocument> hit : searchHits.getSearchHits()) {
                try {
                    logs.add(hit.getContent());
                } catch (Exception e) {
                    log.warn("跳过无法解析的异常日志记录: {}", e.getMessage());
                }
            }
            return logs;
        } catch (Exception e) {
            log.error("ES异常日志查询失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private String extractSeverity(String severity) {
        if (severity == null) return "UNKNOWN";
        if (severity.contains("FATAL")) return "FATAL";
        if (severity.contains("ERROR")) return "ERROR";
        if (severity.contains("WARN")) return "WARNING";
        if (severity.contains("INFO")) return "INFO";
        return "UNKNOWN";
    }

    private String extractErrorType(String content) {
        if (content == null) return "未知错误";

        String[] patterns = {
                "NullPointerException", "空指针异常",
                "Connection refused", "连接被拒绝",
                "Timeout", "超时",
                "OutOfMemory", "内存溢出",
                "FileNotFound", "文件未找到",
                "Permission denied", "权限拒绝",
                "SQLException", "数据库异常",
                "IOException", "IO异常"
        };

        for (int i = 0; i < patterns.length; i += 2) {
            if (content.contains(patterns[i])) {
                return patterns[i + 1];
            }
        }

        return content.length() > 30 ? content.substring(0, 30) + "..." : content;
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

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}