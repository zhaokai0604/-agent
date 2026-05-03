package com.award.log.analyzer;

import com.award.log.analysis.LogAnomalyHeuristics;
import com.award.log.analyzer.LogCleaner;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogDocument;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.ElasticsearchService;
import com.award.log.service.LogAnalysisService;
import com.award.log.websocket.LogStreamWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 实时日志分析器
 * 用于实时分析通过WebSocket传输的日志数据
 * 集成了与正常引擎相同的强大分析功能
 */
@Slf4j
@Component
public class RealTimeLogAnalyzer {

    // 日志分析服务
    @Autowired
    private LogAnalysisService logAnalysisService;
    
    // Elasticsearch服务
    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;
    
    // AI告警服务
    @Autowired
    private AiLogAlarmService aiLogAlarmService;
    
    // 日志清洗器
    @Autowired
    private LogCleaner logCleaner;

    /** 与 MCP {@link com.award.log.mcp.tools.LogAnalysisTool} 共用同一套 Drain-Plus（PLUS 版）实例 */
    @Autowired
    private DrainParserFactory drainParserFactory;
    
    // 简单的消息广播服务
    private final MessageBroadcaster messageBroadcaster = new MessageBroadcaster();
    
    // 线程池用于处理实时日志分析
    private ExecutorService executorService;
    
    // 线程池核心大小
    private volatile int corePoolSize;
    
    // 日志缓冲区，用于批量分析
    private final List<Map<String, Object>> logBuffer = new ArrayList<>();
    
    // 增强的日志解析结果缓冲区
    private final List<EnhancedLogParseResultEntity> parsedLogBuffer = new ArrayList<>();
    
    // 缓冲区大小阈值，根据系统内存动态调整
    private volatile int bufferThreshold;
    
    // 分析间隔（毫秒）
    private static final long ANALYSIS_INTERVAL = 3000;
    
    // 内存使用率阈值
    private static final double MEMORY_THRESHOLD_HIGH = 0.8;
    private static final double MEMORY_THRESHOLD_MEDIUM = 0.6;
    private static final double MEMORY_THRESHOLD_LOW = 0.4;
    
    // 内存检查间隔（毫秒）
    private static final long MEMORY_CHECK_INTERVAL = 30000;
    
    // 最小缓冲区大小
    private static final int MIN_BUFFER_THRESHOLD = 20;
    
    // 最大缓冲区大小
    private static final int MAX_BUFFER_THRESHOLD = 500;

    // 标记是否应该停止分析循环
    private volatile boolean stopAnalysisLoop = false;
    
    // 内存检查任务调度器
    private ScheduledExecutorService memoryCheckScheduler;

    // --- 分析引擎配置 --- 
    private static class EngineConfig {
        final int shardSize = 1000;
        final boolean enableAnomalyDetect = true;
        final boolean enableDesensitize = true;
        final boolean enableCsvExport = false;
        final boolean enableVisualReport = false;
        final String outputDir = "output";
        final double frequencyThresholdRate = 0.01;
        final double anomalyThreshold = 0.6;
    }

    @javax.annotation.PostConstruct
    public void init() {
        // 初始化线程池和缓冲区大小
        adjustResourcesBasedOnMemory();
        
        // 启动内存检查调度器
        startMemoryCheckScheduler();
    }

    @javax.annotation.PreDestroy
    public void destroy() {
        // 关闭线程池
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 关闭内存检查调度器
        if (memoryCheckScheduler != null) {
            memoryCheckScheduler.shutdown();
            try {
                if (!memoryCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    memoryCheckScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                memoryCheckScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 检查当前内存使用情况
     */
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }

    /**
     * 根据内存使用情况调整资源配置
     */
    private void adjustResourcesBasedOnMemory() {
        double memoryUsage = getMemoryUsage();
        int processors = Runtime.getRuntime().availableProcessors();
        
        // 调整线程池大小
        int newCoreSize;
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少线程数
            newCoreSize = Math.max(1, processors / 4);
            log.info("内存使用率高 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度线程数
            newCoreSize = Math.min(4, processors / 2);
            log.info("内存使用率中等 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        } else {
            // 内存使用低，增加线程数
            newCoreSize = Math.min(8, processors);
            log.info("内存使用率低 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        }
        
        // 只有当线程池大小发生变化时才重新创建
        if (corePoolSize != newCoreSize) {
            corePoolSize = newCoreSize;
            
            // 关闭旧线程池
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // 创建新线程池
            executorService = Executors.newFixedThreadPool(corePoolSize, new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "realtime-analyzer-executor-" + threadNumber.getAndIncrement());
                }
            });
            log.info("线程池已重新创建，核心大小: {}", corePoolSize);
        }
        
        // 调整缓冲区大小
        int newBufferThreshold;
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少缓冲区大小
            newBufferThreshold = MIN_BUFFER_THRESHOLD;
            log.info("内存使用率高 ({}), 缓冲区大小调整为: {}", String.format("%.2f", memoryUsage), newBufferThreshold);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度缓冲区大小
            newBufferThreshold = 100;
            log.info("内存使用率中等 ({}), 缓冲区大小调整为: {}", String.format("%.2f", memoryUsage), newBufferThreshold);
        } else {
            // 内存使用低，增加缓冲区大小
            newBufferThreshold = Math.min(MAX_BUFFER_THRESHOLD, processors * 25);
            log.info("内存使用率低 ({}), 缓冲区大小调整为: {}", String.format("%.2f", memoryUsage), newBufferThreshold);
        }
        
        bufferThreshold = newBufferThreshold;
    }

    /**
     * 启动内存检查调度器
     */
    private void startMemoryCheckScheduler() {
        memoryCheckScheduler = Executors.newSingleThreadScheduledExecutor();
        memoryCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                adjustResourcesBasedOnMemory();
            } catch (Exception e) {
                log.error("内存检查任务执行失败: {}", e.getMessage(), e);
            }
        }, 0, MEMORY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        log.info("内存检查调度器已启动，检查间隔: {}ms", MEMORY_CHECK_INTERVAL);
    }

    /**
     * 启动实时分析任务
     */
    public void startRealTimeAnalysis() {
        // 启动定期分析任务
        executorService.submit(() -> {
            try {
                while (!stopAnalysisLoop) {
                    // 定期分析缓冲区中的日志
                    analyzeBufferedLogs();
                    // 休眠指定时间
                    TimeUnit.MILLISECONDS.sleep(ANALYSIS_INTERVAL);
                }
                log.info("实时分析任务已停止");
            } catch (Exception e) {
                log.error("实时分析任务执行失败: {}", e.getMessage(), e);
            }
        });
        
        log.info("实时日志分析器已启动");
    }

    /**
     * 停止实时分析任务
     */
    public void stopRealTimeAnalysis() {
        stopAnalysisLoop = true;
        log.info("请求停止实时分析任务");
    }

    /**
     * 重置分析状态，允许重新开始分析
     */
    public void resetAnalysisState() {
        stopAnalysisLoop = false;
        logBuffer.clear();
        parsedLogBuffer.clear();
        log.info("分析状态已重置");
    }

    /**
     * 添加日志到缓冲区
     * @param logEntry 日志数据
     */
    public synchronized void addLog(Map<String, Object> logEntry) {
        // 检查内存使用情况，如果内存使用过高，直接处理而不加入缓冲区
        double memoryUsage = getMemoryUsage();
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用过高，立即处理单条日志
            List<Map<String, Object>> singleLogList = new ArrayList<>();
            singleLogList.add(logEntry);
            executorService.submit(() -> {
                try {
                    Map<String, Object> analysisResult = analyzeLogs(singleLogList);
                    sendAnalysisResult(analysisResult);
                } catch (Exception e) {
                    log.error("分析单条日志失败: {}", e.getMessage(), e);
                }
            });
            return;
        }
        
        logBuffer.add(logEntry);
        
        // 当缓冲区达到阈值时，立即分析
        if (logBuffer.size() >= bufferThreshold) {
            analyzeBufferedLogs();
        }
    }

    /**
     * 分析缓冲区中的日志
     */
    private synchronized void analyzeBufferedLogs() {
        if (logBuffer.isEmpty()) {
            return;
        }
        
        // 检查内存使用情况，如果内存使用过高，减少处理批次
        double memoryUsage = getMemoryUsage();
        int batchSize = logBuffer.size();
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用过高，只处理一半的日志
            batchSize = Math.max(1, batchSize / 2);
            log.info("内存使用率高 ({}), 减少处理批次大小为: {}", String.format("%.2f", memoryUsage), batchSize);
        }
        
        // 复制并清空缓冲区
        List<Map<String, Object>> logsToAnalyze = new ArrayList<>(logBuffer.subList(0, batchSize));
        logBuffer.subList(0, batchSize).clear();
        
        // 提交分析任务
        executorService.submit(() -> {
            try {
                // 分析日志
                Map<String, Object> analysisResult = analyzeLogs(logsToAnalyze);
                
                // 发送分析结果到前端
                sendAnalysisResult(analysisResult);
            } catch (Exception e) {
                log.error("分析日志失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 分析日志数据
     * @param logs 日志数据列表
     * @return 分析结果
     */
    private Map<String, Object> analyzeLogs(List<Map<String, Object>> logs) {
        EngineConfig config = new EngineConfig();
        DrainParser drainParser = drainParserFactory.getPlusParser();

        // 1. 解析日志（Drain-Plus：与批量任务 / MCP 日志分析同源）
        List<EnhancedLogParseResultEntity> parsedLogs = processLogBatch(logs, drainParser, config);
        
        // 2. 异常检测
        if (config.enableAnomalyDetect) {
            detectTemporalAnomaly(parsedLogs, config);
            detectKeywordAnomaly(parsedLogs);
            detectFrequencyAnomaly(parsedLogs, config);
            calcComprehensiveAnomalyScore(parsedLogs);
        }
        
        // 3. AI告警处理
        if (config.enableAnomalyDetect) {
            processAiAlarms(parsedLogs);
        }
        
        // 4. 统计分析结果
        Map<String, Object> result = new HashMap<>();
        int totalLogs = parsedLogs.size();
        int anomalyLogs = (int) parsedLogs.stream().filter(EnhancedLogParseResultEntity::isAnomaly).count();
        
        result.put("totalLogs", totalLogs);
        result.put("anomalyLogs", anomalyLogs);
        result.put("anomalyRate", totalLogs > 0 ? (double) anomalyLogs / totalLogs * 100 : 0);
        result.put("analysisTime", System.currentTimeMillis());
        
        // 统计异常类型
        Map<String, Integer> anomalyTypes = new HashMap<>();
        for (EnhancedLogParseResultEntity log : parsedLogs) {
            if (log.isAnomaly()) {
                String severity = log.getSeverity().name();
                anomalyTypes.put(severity, anomalyTypes.getOrDefault(severity, 0) + 1);
            }
        }
        result.put("anomalyTypes", anomalyTypes);
        
        // 添加强化分析结果
        result.put("enhancedAnalysis", true);
        result.put("parsedLogsCount", parsedLogs.size());
        
        log.info("分析完成: 总日志数={}, 异常日志数={}, 异常率={}%", totalLogs, anomalyLogs, result.get("anomalyRate"));
        
        return result;
    }

    /**
     * 处理日志批次
     */
    private List<EnhancedLogParseResultEntity> processLogBatch(
            List<Map<String, Object>> logs,
            DrainParser drainParser,
            EngineConfig config) {
        return logs.stream().map(log -> {
            String logContent = (String) log.get("content");
            if (logContent == null) {
                logContent = (String) log.get("message");
            }
            if (logContent == null) {
                logContent = "无内容";
            }
            
            // 清洗日志内容（使用默认清洗规则）
            String cleanedContent = logCleaner.cleanLog(logContent, DefaultCleanRules.getDefaultRules());
            
            LogSeverityLevel severity = identifySeverity(cleanedContent);
            LogProtocolType protocol = identifyProtocol(cleanedContent);
            
            String desensitized = config.enableDesensitize ? desensitizeLog(cleanedContent) : cleanedContent;
            EnhancedLogParseResultEntity res = new EnhancedLogParseResultEntity(desensitized);
            
            res.setProtocol(protocol);
            res.setSeverity(severity);
            res.setTemplateId(drainParser.parse(cleanedContent));
            res.setLogTime(extractLogTime(cleanedContent, protocol));
            res.setTimestamp(parseTimestamp(res.getLogTime()));
            res.setPid(extractPid(cleanedContent));
            res.setUid(extractUid(cleanedContent));
            res.setTraceId(extractTraceId(cleanedContent));
            res.setStackTrace(extractStackTrace(cleanedContent));
            
            // 如果是 Error 且是 Windows 日志，自动添加 AI 建议模拟
            if (severity == LogSeverityLevel.ERROR_LEVEL || severity == LogSeverityLevel.FATAL_LEVEL) {
                res.getAnomalyReasons().add("系统组件异常，建议检查驱动或最近安装的软件");
            }
            
            // 将日志添加到实时分析器的缓冲区
            parsedLogBuffer.add(res);
            
            // 同时索引到Elasticsearch
            try {
                if (elasticsearchService != null) {
                    LogDocument logDocument = convertToLogDocument(log);
                    logDocument.setAnomaly(res.isAnomaly());
                    elasticsearchService.indexLog(logDocument);
                    RealTimeLogAnalyzer.this.log.debug("日志已索引到Elasticsearch: {}", logDocument.getId());
                } else {
                    RealTimeLogAnalyzer.this.log.debug("Elasticsearch服务未启用，跳过日志索引");
                }
            } catch (Exception e) {
                RealTimeLogAnalyzer.this.log.warn("索引日志到Elasticsearch失败: {}", e.getMessage());
            }
            
            return res;
        }).collect(Collectors.toList());
    }

    /**
     * 时序异常检测
     */
    private void detectTemporalAnomaly(List<EnhancedLogParseResultEntity> resultList, EngineConfig config) {
        if (resultList.size() < 5) return;
        
        // 1. 特征编码
        Map<String, Integer> tempCodeMap = new HashMap<>();
        int codeId = 0;
        List<double[]> featureList = new ArrayList<>();
        for (EnhancedLogParseResultEntity res : resultList) {
            tempCodeMap.putIfAbsent(res.getTemplateId(), codeId++);
            double[] feature = {
                    res.getTimestamp() / 1000.0,
                    tempCodeMap.get(res.getTemplateId()),
                    res.getSeverity().getSeverityScore(),
                    res.getProtocol().getProtocolCode(),
                    0.0 // 风险权重得分
            };
            featureList.add(feature);
        }
        
        // 2. 全局特征中心分析
        double[] meanVec = new double[5];
        featureList.forEach(vec -> {
            for (int i = 0; i < 5; i++) meanVec[i] += vec[i];
        });
        Arrays.setAll(meanVec, i -> meanVec[i] / featureList.size());
        
        // 3. 时间窗口分析 (窗口大小为10)
        int windowSize = 10;
        
        for (int i = 0; i < resultList.size(); i++) {
            EnhancedLogParseResultEntity res = resultList.get(i);
            double[] vec = featureList.get(i);
            
            // 计算全局距离
            double globalDist = 0.0;
            for (int j = 0; j < 5; j++) globalDist += Math.pow(vec[j] - meanVec[j], 2);
            globalDist = Math.sqrt(globalDist);
            
            // 计算时间窗口内的局部距离
            double localDist = 0.0;
            int start = Math.max(0, i - windowSize + 1);
            int end = Math.min(resultList.size(), i + 1);
            
            if (end - start >= 3) {
                double[] localMean = new double[5];
                for (int k = start; k < end; k++) {
                    double[] localVec = featureList.get(k);
                    for (int j = 0; j < 5; j++) localMean[j] += localVec[j];
                }
                Arrays.setAll(localMean, j -> localMean[j] / (end - start));
                
                for (int j = 0; j < 5; j++) localDist += Math.pow(vec[j] - localMean[j], 2);
                localDist = Math.sqrt(localDist);
            }
            
            // 综合全局和局部距离
            double combinedDist = globalDist * 0.6 + (localDist > 0 ? localDist * 0.4 : globalDist * 0.4);
            double score = Math.min(1.0, combinedDist / 100.0);
            
            res.setAnomalyScore(score);
            if (score > config.anomalyThreshold) {
                res.setAnomaly(true);
                res.getAnomalyReasons().add("时序聚类异常：距离特征中心过远（加权得分）");
            }
        }
    }

    /**
     * 关键词异常检测
     */
    private void detectKeywordAnomaly(List<EnhancedLogParseResultEntity> resultList) {
        LogAnomalyHeuristics.applyKeywordDetection(resultList);
    }

    /**
     * 频次异常检测（config.frequencyThresholdRate 历史上用于「全局高频」误判规则，已由共用启发式替代）
     */
    private void detectFrequencyAnomaly(List<EnhancedLogParseResultEntity> resultList, EngineConfig config) {
        Objects.requireNonNull(config, "engine config");
        LogAnomalyHeuristics.applyFrequencyDetection(resultList);
    }

    /**
     * 计算综合异常得分
     */
    private void calcComprehensiveAnomalyScore(List<EnhancedLogParseResultEntity> resultList) {
        LogAnomalyHeuristics.calcComprehensiveAnomalyScore(resultList);
    }

    /**
     * 处理AI告警
     */
    private void processAiAlarms(List<EnhancedLogParseResultEntity> parsedLogs) {
        // 收集所有异常日志
        List<EnhancedLogParseResultEntity> anomalyLogs = parsedLogs.stream()
            .filter(EnhancedLogParseResultEntity::isAnomaly)
            .collect(Collectors.toList());
        
        // 如果有异常日志，生成一个综合告警
        if (!anomalyLogs.isEmpty()) {
            try {
                // 构建综合告警消息
                StringBuilder alarmMessage = new StringBuilder();
                alarmMessage.append("实时日志分析检测到异常:\n");
                alarmMessage.append("异常日志数量: " + anomalyLogs.size() + "\n");
                
                // 添加前3条异常日志的摘要
                int maxLogs = Math.min(3, anomalyLogs.size());
                for (int i = 0; i < maxLogs; i++) {
                    EnhancedLogParseResultEntity anomalyLog = anomalyLogs.get(i);
                    String logContent = anomalyLog.getDesensitizedLog();
                    String truncatedContent = logContent.length() > 100 ? logContent.substring(0, 100) + "..." : logContent;
                    alarmMessage.append((i + 1) + ". " + truncatedContent + "\n");
                    
                    // 添加异常原因
                    if (!anomalyLog.getAnomalyReasons().isEmpty()) {
                        alarmMessage.append("   异常原因: " + String.join(", ", anomalyLog.getAnomalyReasons()) + "\n");
                    }
                }
                
                // 如果还有更多异常日志，添加提示
                if (anomalyLogs.size() > maxLogs) {
                    alarmMessage.append("... 等" + (anomalyLogs.size() - maxLogs) + "条异常日志\n");
                }
                
                // 调用AI告警服务，生成并推送一个综合告警
                aiLogAlarmService.analyzeLogAndGenerateAlarm(
                    "realtime-analysis",
                    alarmMessage.toString()
                );
                
                log.info("已生成综合告警，包含 {} 条异常日志", anomalyLogs.size());
            } catch (Exception e) {
                log.error("生成综合告警失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 脱敏所需的静态正则模式
     */
    private static final Pattern IP_PATTERN = Pattern.compile("(\\d{1,3}\\.\\d{1,3})(\\.\\d{1,3}\\.\\d{1,3})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(\\w)\\w{2,}(\\w)@(\\w+\\.\\w+)");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("([a-zA-Z0-9])[a-zA-Z0-9_]{2,18}([a-zA-Z0-9])");

    /**
     * 对日志内容进行脱敏处理
     */
    private String desensitizeLog(String rawLog) {
        if (rawLog == null || rawLog.trim().isEmpty()) return "";
        String desensitized = rawLog;

        // 1. 分级IP脱敏
        Matcher ipMatcher = IP_PATTERN.matcher(desensitized);
        StringBuffer ipSb = new StringBuffer();
        while (ipMatcher.find()) {
            String ipPrefix = ipMatcher.group(1);
            String replacement;
            if (ipPrefix.startsWith("192.168") || ipPrefix.startsWith("10.") ||
                    (ipPrefix.startsWith("172.") && Integer.parseInt(ipPrefix.split("\\.")[1]) >= 16 && Integer.parseInt(ipPrefix.split("\\.")[1]) <= 31)) {
                replacement = ipPrefix + ".*.*";
            } else {
                replacement = ipPrefix.split("\\.")[0] + ".*.*.*";
            }
            ipMatcher.appendReplacement(ipSb, replacement);
        }
        ipMatcher.appendTail(ipSb);
        desensitized = ipSb.toString();

        // 2. 手机号脱敏
        desensitized = PHONE_PATTERN.matcher(desensitized).replaceAll("$1****$2");
        // 3. 邮箱脱敏
        desensitized = EMAIL_PATTERN.matcher(desensitized).replaceAll("$1***$2@$3");
        // 4. 账号脱敏
        desensitized = ACCOUNT_PATTERN.matcher(desensitized).replaceAll("$1***$2");

        return desensitized;
    }

    /**
     * 智能识别日志协议类型
     */
    private LogProtocolType identifyProtocol(String logLine) {
        String lowerLog = logLine.toLowerCase();
        // 检查 Windows 结构: 以级别关键字开头，后跟日期
        if (logLine.matches("^(信息|警告|错误|关键|Information|Warning|Error|Critical)\\s+\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}.*")) {
            return LogProtocolType.WINDOWS_EVENT_LOG;
        }

        if (lowerLog.contains("kernel") || lowerLog.contains("sshd") || lowerLog.contains("cron") || lowerLog.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}.*")) {
            return LogProtocolType.LINUX_SYSTEM_LOG;
        } else if (lowerLog.contains("<event") || lowerLog.contains("microsoft-windows") || lowerLog.contains("事件id")) {
            return LogProtocolType.WINDOWS_EVENT_LOG;
        } else if (lowerLog.contains("traceid") || lowerLog.contains("exception") || lowerLog.contains("controller") || lowerLog.contains("requestid")) {
            return LogProtocolType.APPLICATION_LOG;
        } else if (lowerLog.contains("sql") || lowerLog.contains("mysql") || lowerLog.contains("oracle") || lowerLog.contains("select|insert|update|delete")) {
            return LogProtocolType.DATABASE_LOG;
        } else if (lowerLog.contains("ip:") || lowerLog.contains("port:") || lowerLog.contains("tcp") || lowerLog.contains("udp") || lowerLog.contains("packet")) {
            return LogProtocolType.NETWORK_DEVICE_LOG;
        } else {
            return LogProtocolType.UNIVERSAL_TEXT_LOG;
        }
    }

    /**
     * 识别日志严重程度等级
     */
    private LogSeverityLevel identifySeverity(String logLine) {
        String lowerLog = logLine.toLowerCase();
        // Windows 事件日志等级
        if (lowerLog.startsWith("关键") || lowerLog.contains("critical")) return LogSeverityLevel.FATAL_LEVEL;
        if (lowerLog.startsWith("错误") || lowerLog.contains("error")) return LogSeverityLevel.ERROR_LEVEL;
        if (lowerLog.startsWith("警告") || lowerLog.contains("warning")) return LogSeverityLevel.WARNING_LEVEL;
        if (lowerLog.startsWith("信息") || lowerLog.contains("information")) return LogSeverityLevel.INFO_LEVEL;
        if (lowerLog.startsWith("详细") || lowerLog.contains("verbose")) return LogSeverityLevel.DEBUG_LEVEL;

        if (lowerLog.contains("fatal") || lowerLog.contains("致命")) return LogSeverityLevel.FATAL_LEVEL;
        else if (lowerLog.contains("error") || lowerLog.contains("错误") || lowerLog.contains("failed") || lowerLog.contains("失败")) return LogSeverityLevel.ERROR_LEVEL;
        else if (lowerLog.contains("warn") || lowerLog.contains("警告") || lowerLog.contains("timeout") || lowerLog.contains("超时")) return LogSeverityLevel.WARNING_LEVEL;
        else if (lowerLog.contains("info") || lowerLog.contains("信息") || lowerLog.contains("started") || lowerLog.contains("启动")) return LogSeverityLevel.INFO_LEVEL;
        else if (lowerLog.contains("debug") || lowerLog.contains("调试")) return LogSeverityLevel.DEBUG_LEVEL;
        else return LogSeverityLevel.UNKNOWN_LEVEL;
    }

    /**
     * 提取日志发生时间
     */
    private String extractLogTime(String logLine, LogProtocolType protocol) {
        Pattern stdPattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}");
        Matcher stdM = stdPattern.matcher(logLine);
        if (stdM.find()) return stdM.group();
        Pattern winPattern = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}:\\d{2}");
        Matcher winM = winPattern.matcher(logLine);
        if (winM.find()) return winM.group();
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    /**
     * 解析时间戳
     */
    private long parseTimestamp(String timeStr) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timeStr).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 提取进程 ID
     */
    private String extractPid(String logLine) {
        Matcher m1 = Pattern.compile("PID[:=]\\s*(\\d+)").matcher(logLine);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("\\[(\\d+)\\]").matcher(logLine);
        if (m2.find()) return m2.group(1);
        return "未提取";
    }

    /**
     * 提取用户 ID
     */
    private String extractUid(String logLine) {
        Matcher m1 = Pattern.compile("UID[:=]\\s*(\\d+)").matcher(logLine);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("user[:=]\\s*(\\w+)").matcher(logLine);
        if (m2.find()) return m2.group(1);
        return "未提取";
    }

    /**
     * 提取调用链 ID (Trace ID)
     */
    private String extractTraceId(String logLine) {
        Matcher m = Pattern.compile("trace[id_]{0,2}[:=]\\s*(\\w+)").matcher(logLine.toLowerCase());
        return m.find() ? m.group(1) : "未提取";
    }

    /**
     * 提取异常堆栈信息
     */
    private String extractStackTrace(String logLine) {
        int idx = Math.max(Math.max(logLine.indexOf("Exception"), logLine.indexOf("Error")), Math.max(logLine.indexOf("堆栈"), logLine.indexOf("stack trace")));
        return idx > -1 ? logLine.substring(idx) : "无异常栈";
    }

    /**
     * 将日志Map转换为LogDocument对象
     */
    private LogDocument convertToLogDocument(Map<String, Object> log) {
        LogDocument document = new LogDocument();
        document.setId(java.util.UUID.randomUUID().toString());
        document.setTimestamp(java.time.LocalDateTime.now());
        
        // 设置日志级别
        String level = (String) log.get("level");
        if (level != null) {
            document.setSeverity(level);
        } else {
            document.setSeverity("INFO");
        }
        
        // 设置日志内容
        String content = (String) log.get("content");
        if (content != null) {
            document.setContent(content);
        } else if (log.get("message") != null) {
            document.setContent((String) log.get("message"));
        } else {
            document.setContent("无内容");
        }
        
        // 设置日志来源
        document.setProtocol("ANALYSIS_STREAM");
        
        return document;
    }

    /**
     * 发送分析结果到前端
     */
    private void sendAnalysisResult(Map<String, Object> analysisResult) {
        try {
            // 转换为JSON格式
            String jsonResult = com.alibaba.fastjson.JSON.toJSONString(analysisResult);
            String message = "{\"type\": \"analysis\", \"data\": " + jsonResult + "}";
            
            // 广播分析结果到所有注册的WebSocket处理器
            messageBroadcaster.broadcast(message);
        } catch (Exception e) {
            log.error("发送分析结果失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 注册WebSocket处理器到消息广播器
     * @param handler WebSocket处理器实例
     */
    public void registerWebSocketHandler(LogStreamWebSocketHandler handler) {
        messageBroadcaster.registerHandler(handler);
    }

    /**
     * 关闭分析器
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("实时日志分析器已关闭");
    }
    
    /**
     * 简单的消息广播服务
     * 用于广播分析结果到所有活跃的WebSocket会话
     */
    public static class MessageBroadcaster {
        private final List<LogStreamWebSocketHandler> handlers = new ArrayList<>();
        
        /**
         * 注册WebSocket处理器
         * @param handler WebSocket处理器实例
         */
        public void registerHandler(LogStreamWebSocketHandler handler) {
            if (handler != null && !handlers.contains(handler)) {
                handlers.add(handler);
            }
        }
        
        /**
         * 广播消息到所有注册的处理器
         * @param message 消息内容
         */
        public void broadcast(String message) {
            for (LogStreamWebSocketHandler handler : handlers) {
                try {
                    handler.broadcastLogData(message);
                } catch (Exception e) {
                    log.warn("广播消息失败: {}", e.getMessage());
                }
            }
        }
    }
}
