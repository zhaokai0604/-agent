package com.award.log.mcp.tools;

import com.award.log.analysis.LogAnomalyHeuristics;
import com.award.log.analyzer.DrainPlusParser;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.award.log.service.AiDiagnosisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class LogAnalysisTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final DrainPlusParser drainPlusParser;
    private final AiDiagnosisService aiDiagnosisService;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public LogAnalysisTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            DrainPlusParser drainPlusParser,
            AiDiagnosisService aiDiagnosisService,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.drainPlusParser = drainPlusParser;
        this.aiDiagnosisService = aiDiagnosisService;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "analyzeLogs", description = "分析系统日志文件，检测异常和错误，生成摘要和根因分析建议。默认分析最近 100 行系统日志")
    @Cacheable(value = McpToolsConfig.CACHE_LOG_ANALYSIS, key = "#logPath + ':' + #lines", cacheManager = "mcpCacheManager")
    public String analyzeLogs(
            @ToolParam(description = "日志文件路径（默认 /var/log/syslog 或 /var/log/messages）", required = false) String logPath,
            @ToolParam(description = "分析的行数（默认 100）", required = false) Integer lines
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = logPath != null && !logPath.isBlank() ? logPath.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\setupact.log" : "/var/log/syslog");
        int maxLines = lines != null && lines > 0 ? Math.min(lines, 1000) : 100;

        log.info("开始分析日志文件，路径: {}, 行数: {}", targetPath, maxLines);

        try {
            if (!opsPathPolicy.isAllowedLogReadPath(targetPath)) {
                return buildErrorResponse(opsPathPolicy.rejectReason("日志读取路径"), startTime);
            }

            List<String> logEntries = readLogFile(targetPath, maxLines);

            if (logEntries.isEmpty()) {
                Map<String, Object> emptyPayload = new LinkedHashMap<>();
                emptyPayload.put("logPath", targetPath);
                emptyPayload.put("status", "EMPTY");
                emptyPayload.put("linesAnalyzed", 0);
                emptyPayload.put("summary", "日志文件为空或不存在");
                emptyPayload.put("recommendation", "请检查日志文件路径是否正确");
                String data = objectMapper.writeValueAsString(emptyPayload);
                long duration = System.currentTimeMillis() - startTime;
                return buildSuccessResponse(data, duration, false);
            }

            // 使用Drain-Plus解析日志
            List<EnhancedLogParseResultEntity> parsedLogs = parseLogsWithDrainPlus(logEntries);
            
            // 执行异常检测
            LogAnomalyHeuristics.applyKeywordDetection(parsedLogs);
            LogAnomalyHeuristics.applyFrequencyDetection(parsedLogs);
            LogAnomalyHeuristics.calcComprehensiveAnomalyScore(parsedLogs);
            
            List<EnhancedLogParseResultEntity> anomalies = filterAnomalies(parsedLogs);
            
            // 生成AI诊断
            String diagnosis = generateAiDiagnosis(anomalies);
            String recommendation = generateFixRecommendation(anomalies);
            
            // 提取模板信息
            List<Map<String, Object>> templates = extractTemplates(parsedLogs);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("logPath", targetPath);
            payload.put("status", anomalies.isEmpty() ? "HEALTHY" : "ISSUES_DETECTED");
            payload.put("linesAnalyzed", logEntries.size());
            payload.put("errorCount", anomalies.size());
            payload.put("templates", templates);
            payload.put("rootCause", diagnosis);
            payload.put("recommendation", recommendation);
            payload.put("integrationStatus", "INTEGRATED");
            String data = objectMapper.writeValueAsString(payload);

            long duration = System.currentTimeMillis() - startTime;
            log.info("日志分析完成，错误: {} 个, 模板数: {}, 状态: {}, 耗时: {}ms",
                anomalies.size(), templates.size(), anomalies.isEmpty() ? "HEALTHY" : "ISSUES_DETECTED", duration);

            return buildSuccessResponse(data, duration, false);

        } catch (Exception e) {
            log.error("分析日志时发生异常", e);
            return buildErrorResponse("分析日志时发生异常: " + e.getMessage(), startTime);
        }
    }

    private List<String> readLogFile(String path, int maxLines) {
        List<String> lines = new ArrayList<>();
        try {
            if (!opsPathPolicy.isAllowedLogReadPath(path)) {
                return lines;
            }
            java.io.File file = new java.io.File(path);
            if (file.exists() && file.canRead()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                    String line;
                    while ((line = reader.readLine()) != null && lines.size() < maxLines) {
                        lines.add(line);
                    }
                }
            } else {
                AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                    List.of("tail", "-n", String.valueOf(maxLines), path)
                );
                if (result.success() && result.output() != null) {
                    for (String line : result.output().split("\n")) {
                        if (!line.isBlank() && lines.size() < maxLines) {
                            lines.add(line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取日志文件失败: {}", e.getMessage());
        }
        return lines;
    }

    private List<EnhancedLogParseResultEntity> parseLogsWithDrainPlus(List<String> logEntries) {
        List<EnhancedLogParseResultEntity> parsedLogs = new ArrayList<>();
        for (String entry : logEntries) {
            try {
                String templateId = drainPlusParser.parse(entry);
                EnhancedLogParseResultEntity logEntity = new EnhancedLogParseResultEntity(entry);
                logEntity.setTemplateId(templateId);
                logEntity.setSeverity(LogAnomalyHeuristics.inferSeverityFromLine(entry));
                logEntity.setAnomalyScore(0.0); // 初始化异常得分
                parsedLogs.add(logEntity);
            } catch (Exception e) {
                log.warn("解析日志条目失败: {}", e.getMessage());
            }
        }
        return parsedLogs;
    }

    private List<EnhancedLogParseResultEntity> filterAnomalies(List<EnhancedLogParseResultEntity> parsedLogs) {
        List<EnhancedLogParseResultEntity> anomalies = new ArrayList<>();
        for (EnhancedLogParseResultEntity log : parsedLogs) {
            if (log.isAnomaly()) {
                anomalies.add(log);
            }
        }
        return anomalies;
    }

    private String generateAiDiagnosis(List<EnhancedLogParseResultEntity> anomalies) {
        if (anomalies.isEmpty()) {
            return "未检测到明显异常，系统运行正常";
        }
        
        try {
            return aiDiagnosisService.generateDiagnosis(anomalies);
        } catch (Exception e) {
            log.warn("AI诊断失败，使用默认诊断: {}", e.getMessage());
            return "检测到系统异常，需要进一步排查";
        }
    }

    private String generateFixRecommendation(List<EnhancedLogParseResultEntity> anomalies) {
        if (anomalies.isEmpty()) {
            return "系统运行正常，无需特殊处理";
        }
        
        // 基于异常类型生成修复建议
        StringBuilder recommendation = new StringBuilder();
        recommendation.append("建议：");
        
        boolean hasMemoryIssue = false;
        boolean hasConnectionIssue = false;
        boolean hasDiskIssue = false;
        boolean hasPermissionIssue = false;
        
        for (EnhancedLogParseResultEntity anomaly : anomalies) {
            String logContent = anomaly.getDesensitizedLog().toLowerCase();
            if (logContent.contains("memory") || logContent.contains("oom")) {
                hasMemoryIssue = true;
            } else if (logContent.contains("connection") || logContent.contains("refused")) {
                hasConnectionIssue = true;
            } else if (logContent.contains("disk") || logContent.contains("space")) {
                hasDiskIssue = true;
            } else if (logContent.contains("permission") || logContent.contains("denied")) {
                hasPermissionIssue = true;
            }
        }
        
        int count = 1;
        if (hasMemoryIssue) {
            recommendation.append(count++).append(") 检查进程内存使用: ps aux --sort=-%mem | head -5\n");
        }
        if (hasConnectionIssue) {
            recommendation.append(count++).append(") 检查网络连通性: ping -c 4 localhost\n");
        }
        if (hasDiskIssue) {
            recommendation.append(count++).append(") 检查磁盘空间: df -h\n");
        }
        if (hasPermissionIssue) {
            recommendation.append(count++).append(") 检查文件权限: ls -la\n");
        }
        
        if (count == 1) {
            recommendation.append("1) 查看完整错误日志: tail -n 100 /var/log/syslog\n");
            recommendation.append("2) 检查相关服务状态: systemctl status\n");
        }
        
        return recommendation.toString();
    }

    private List<Map<String, Object>> extractTemplates(List<EnhancedLogParseResultEntity> parsedLogs) {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (EnhancedLogParseResultEntity log : parsedLogs) {
            Map<String, Object> templateInfo = new HashMap<>();
            templateInfo.put("templateId", log.getTemplateId());
            templateInfo.put("isAnomaly", log.isAnomaly());
            templateInfo.put("anomalyScore", log.getAnomalyScore());
            templateInfo.put("anomalyReasons", log.getAnomalyReasons());
            templateInfo.put("logSample", log.getDesensitizedLog().substring(0, Math.min(100, log.getDesensitizedLog().length())));
            if (log.getSeverity() != null) {
                templateInfo.put("severity", log.getSeverity().name());
            }
            templates.add(templateInfo);
        }
        return templates;
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<>(true, data, null, duration, cacheHit));
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<String>(false, null, error, duration, false));
    }
}