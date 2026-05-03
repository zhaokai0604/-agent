package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogAlarm;
import com.award.log.model.LogSeverityLevel;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import com.award.log.service.AiLogAlarmService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import com.award.log.util.EmailPushUtil;
import com.award.log.util.WeChatPushUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI日志告警服务实现类
 * 负责AI识别异常日志、生成结构化告警信息、多渠道推送
 */
@Slf4j
@Service
public class AiLogAlarmServiceImpl implements AiLogAlarmService {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private WeChatPushUtil weChatPushUtil;

    @Autowired
    private EmailPushUtil emailPushUtil;

    @Qualifier("logAnalysisExecutor")
    @Autowired
    private ThreadPoolTaskExecutor logAnalysisExecutor;

    @Autowired
    private LogAlarmMapper logAlarmMapper;

    @Autowired
    private AlarmConfigService alarmConfigService;

    @Autowired
    private AnalysisTaskManager analysisTaskManager;

    // 任务+告警指纹冷却去重缓存
    private final ConcurrentHashMap<String, Long> fingerprintCooldownMap = new ConcurrentHashMap<>();

    @Value("${alarm.aggregation.enabled:true}")
    private boolean aggregationEnabled;

    @Value("${alarm.aggregation.window-minutes:5}")
    private int aggregationWindowMinutes;

    @Value("${alarm.aggregation.dimension:task_level_rootCause}")
    private String aggregationDimension;


    
    // AI分析Prompt模板
    private static final String AI_PROMPT_TEMPLATE = """
            你是一名资深的日志分析专家，请帮我分析以下日志内容，生成结构化的告警信息。
            请按照以下格式输出：
            {
                "level": "告警级别(FATAL/ERROR/WARNING/INFO)",
                "rootCause": "根因分析",
                "solution": "解决方案"
            }
            日志内容：
            %s
            """;

    @Override
    public LogAlarm analyzeLogAndGenerateAlarm(String taskId, String logContent) {
        log.info("【告警分析】开始分析日志生成告警，任务ID: [{}]", taskId);
        
        // 1. 调用AI分析日志
        log.info("【告警分析】调用AI分析日志，日志长度: [{}]", logContent.length());
        String aiPrompt = String.format(AI_PROMPT_TEMPLATE, logContent);
        String aiResponse = null;
        try {
            aiResponse = chatClient.prompt().user(aiPrompt).call().content();
            log.info("【告警分析】AI分析完成，响应长度: [{}]", aiResponse.length());
        } catch (Exception e) {
            log.error("【告警分析】AI分析失败，异常信息: [{}]", e.getMessage(), e);
            // 创建默认告警
            LogAlarm defaultAlarm = new LogAlarm();
            // 生成唯一告警ID
            defaultAlarm.setAlarmId(UUID.randomUUID().toString());
            defaultAlarm.setTaskId(taskId);
            defaultAlarm.setLogContent(logContent);
            defaultAlarm.setCreateTime(LocalDateTime.now());
            defaultAlarm.setUpdateTime(LocalDateTime.now());
            defaultAlarm.setPushStatus("FAILED");
            defaultAlarm.setLifecycleStatus("NEW");
            defaultAlarm.setEscalationLevel(0);
            defaultAlarm.setLevel("ERROR");
            defaultAlarm.setRootCause("AI分析失败");
            defaultAlarm.setSolution("请检查AI服务配置");
            addAlarmToHistory(defaultAlarm);
            return defaultAlarm;
        }
        
        // 2. 解析AI返回结果
        LogAlarm alarm = parseAIResponse(taskId, logContent, aiResponse);
        log.info("【告警分析】解析AI返回结果完成，告警级别: [{}]", alarm.getLevel());
        
        // 3. 将告警添加到历史记录
        if (!shouldAggregate(alarm)) {
            addAlarmToHistory(alarm);
        } else {
            log.info("【告警聚合】命中5分钟聚合窗口，跳过重复写入与推送，taskId={}, level={}", alarm.getTaskId(), alarm.getLevel());
            alarm.setPushStatus("AGGREGATED");
            return alarm;
        }
        log.info("【告警分析】告警已添加到历史记录");
        
        // 4. 直接异步推送告警，频率控制在pushAlarm方法中处理
        logAnalysisExecutor.execute(() -> {
            log.info("【告警推送】开始推送告警，告警ID: [{}]", alarm.getAlarmId());
            boolean result = pushAlarm(alarm);
            log.info("【告警推送】推送完成，结果: [{}]", result);
        });
        
        return alarm;
    }
    
    /**
     * 判断是否需要推送告警
     * @param alarm 告警信息
     * @return 是否需要推送
     */
    private AlarmDecision evaluatePushPolicy(LogAlarm alarm, TaskAlarmConfig config) {
        log.info("【告警推送控制】开始检查推送条件，告警ID: [{}], 级别: [{}]", alarm.getAlarmId(), alarm.getLevel());

        if (config == null) {
            return AlarmDecision.skip("CONFIG_NOT_FOUND");
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            return AlarmDecision.skip("ALARM_DISABLED");
        }

        String configAlarmLevel = config.getAlarmLevel() == null ? "ERROR" : config.getAlarmLevel();
        if (!isSeverityMatch(alarm.getLevel(), configAlarmLevel)) {
            return AlarmDecision.skip("LEVEL_BELOW_THRESHOLD");
        }

        String taskId = alarm.getTaskId();
        if (taskId == null) {
            return AlarmDecision.skip("TASK_ID_MISSING");
        }

        if (config.getErrorThreshold() != null && config.getErrorThreshold() > 1 && alarm.getLogContent() != null) {
            int eventCount = countErrorEntries(alarm.getLogContent());
            if (eventCount < config.getErrorThreshold()) {
                return AlarmDecision.skip("ERROR_THRESHOLD_NOT_REACHED");
            }
        }

        String fingerprint = generateAlarmFingerprint(alarm);
        long now = System.currentTimeMillis();
        long cooldownMs = config.getCooldownMs() == null ? 300000L : config.getCooldownMs();
        Long lastPush = fingerprintCooldownMap.get(fingerprint);
        if (lastPush != null && now - lastPush < cooldownMs) {
            return AlarmDecision.skip("COOLDOWN_ACTIVE");
        }

        return AlarmDecision.pass(fingerprint);
    }
    
    /**
     * 检查告警级别是否符合推送条件
     * @param level 告警级别
     * @param configAlarmLevel 配置的告警级别
     * @return 是否符合条件
     */
    private boolean isSeverityMatch(String level, String configAlarmLevel) {
        // 定义告警级别优先级
        Map<String, Integer> severityPriority = new HashMap<>();
        severityPriority.put("INFO", 0);
        severityPriority.put("WARNING", 1);
        severityPriority.put("ERROR", 2);
        severityPriority.put("FATAL", 3);
        
        // 处理配置的告警级别格式（如ERROR_LEVEL）
        String normalizedConfigLevel = configAlarmLevel;
        if (normalizedConfigLevel != null && normalizedConfigLevel.endsWith("_LEVEL")) {
            normalizedConfigLevel = normalizedConfigLevel.substring(0, normalizedConfigLevel.length() - 6);
        }
        
        // 获取当前告警级别和配置的最小级别优先级
        Integer currentPriority = severityPriority.getOrDefault(level, -1);
        Integer minPriority = severityPriority.getOrDefault(normalizedConfigLevel, -1);
        
        // 当前告警级别优先级大于等于配置的最小级别优先级则推送
        return currentPriority >= minPriority;
    }

    @Override
    public boolean pushAlarm(LogAlarm alarm) {
        TaskAlarmConfig taskConfig = alarmConfigService.getEffectiveConfig(alarm.getTaskId());
        AlarmDecision decision = evaluatePushPolicy(alarm, taskConfig);
        if (!decision.allowed()) {
            log.info("【告警推送】告警不符合推送条件，跳过推送，告警ID: [{}]", alarm.getAlarmId());
            alarm.setPushStatus("SKIPPED:" + decision.reason());
            alarm.setUpdateTime(LocalDateTime.now());
            updateAlarm(alarm);
            return false;
        }
        
        log.info("【告警推送】开始推送告警，告警ID: [{}], 级别: [{}]", alarm.getAlarmId(), alarm.getLevel());

        List<String> channels = taskConfig.getPushChannels() == null ? Collections.emptyList() : taskConfig.getPushChannels();
        String wechatUserId = taskConfig.getWechatUserId();
        String emailAddress = taskConfig.getEmailAddress();
        boolean canWechat = channels.contains("WECHAT") && wechatUserId != null && !wechatUserId.isBlank();
        boolean canEmail = channels.contains("EMAIL") && emailAddress != null && !emailAddress.isBlank();
        if (!canWechat && !canEmail) {
            log.warn("【告警推送】未配置可用推送渠道（请在告警配置中启用 EMAIL 或 WECHAT 并填写收件人），告警ID: [{}]", alarm.getAlarmId());
            alarm.setPushStatus("SKIPPED:NO_CHANNELS");
            alarm.setUpdateTime(LocalDateTime.now());
            updateAlarm(alarm);
            return false;
        }

        boolean pushSuccess = false;
        int retryCount = 0;
        final int maxRetry = 3;
        final int retryInterval = 5000; // 5秒
        
        // 标记是否已经发送过邮件，确保每种渠道只发送一次
        boolean emailSent = false;
        boolean wechatSent = false;
        
        // 重试逻辑
        while (retryCount < maxRetry && !pushSuccess) {
            try {
                // 优先使用企业微信推送（如果配置了企业微信用户ID且未发送过）
                if (!wechatSent && canWechat) {
                    log.info("【告警推送】尝试企业微信推送，用户ID: [{}]", wechatUserId);
                    pushSuccess = weChatPushUtil.sendAlarm(alarm, wechatUserId);
                    wechatSent = true;
                    log.info("【告警推送】企业微信推送结果: [{}]", pushSuccess);
                }
                
                // 如果企业微信推送失败或未配置，尝试邮箱推送（如果配置了邮箱且未发送过）
                if (!pushSuccess && !emailSent && canEmail) {
                    log.info("【告警推送】尝试邮箱推送，地址: [{}]", emailAddress);
                    pushSuccess = emailPushUtil.sendAlarm(alarm, emailAddress);
                    emailSent = true;
                    log.info("【告警推送】邮箱推送结果: [{}]", pushSuccess);
                }
                
                // 如果推送成功，更新状态
                if (pushSuccess) {
                    alarm.setPushStatus("SUCCESS");
                    log.info("【告警推送】告警推送成功，告警ID: [{}]", alarm.getAlarmId());
                    fingerprintCooldownMap.put(decision.fingerprint(), System.currentTimeMillis());
                    break; // 推送成功，跳出重试循环
                } else {
                    retryCount++;
                    alarm.setPushStatus("RETRYING");
                    log.warn("【告警推送】告警推送失败，正在重试，告警ID: [{}], 重试次数: [{}]/[{}]", 
                            alarm.getAlarmId(), retryCount, maxRetry);
                    
                    // 等待重试间隔，达到最大重试次数则不再等待
                    if (retryCount < maxRetry) {
                        log.info("【告警推送】等待重试，间隔: [{}]秒", retryInterval / 1000);
                        Thread.sleep(retryInterval);
                    }
                }
            } catch (Exception e) {
                retryCount++;
                log.error("【告警推送】告警推送异常，告警ID: [{}], 重试次数: [{}]/[{}], 异常信息: [{}]", 
                        alarm.getAlarmId(), retryCount, maxRetry, e.getMessage(), e);
                
                try {
                    if (retryCount < maxRetry) {
                        log.info("【告警推送】等待重试，间隔: [{}]秒", retryInterval / 1000);
                        Thread.sleep(retryInterval);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("【告警推送】线程被中断，停止推送", ie);
                    break; // 线程被中断，立即停止推送
                }
            }
        }
        
        // 如果最终推送失败，更新状态为FAILED并停止推送
        if (!pushSuccess) {
            alarm.setPushStatus("FAILED");
            log.error("【告警推送】告警推送达到最大重试次数，已停止推送，告警ID: [{}]", alarm.getAlarmId());
        }
        
        // 更新告警时间
        alarm.setUpdateTime(LocalDateTime.now());
        
        updateAlarm(alarm);
        
        log.info("【告警推送】推送完成，告警ID: [{}], 结果: [{}], 状态: [{}]", 
                alarm.getAlarmId(), pushSuccess, alarm.getPushStatus());
        
        return pushSuccess;
    }
    
    @Override
    public boolean retryPushAlarm(Integer alarmId, int retryCount) {
        log.info("手动重试推送告警，告警ID: [{}], 重试次数: [{}]", alarmId, retryCount);
        
        // 从数据库获取告警信息
        LogAlarm alarm = logAlarmMapper.selectById(alarmId != null ? Long.valueOf(alarmId) : null);
        if (alarm == null) {
            log.warn("告警不存在，告警ID: [{}]", alarmId);
            return false;
        }
        
        // 重置推送状态为PENDING
        alarm.setPushStatus("PENDING");
        alarm.setLifecycleStatus("NEW");
        alarm.setEscalationLevel(0);
        alarm.setUpdateTime(LocalDateTime.now());
        
        // 调用pushAlarm方法进行推送
        boolean success = pushAlarm(alarm);
        log.info("手动重试推送告警结果，告警ID: [{}], 成功: [{}]", alarmId, success);
        return success;
    }

    @Override
    public void processAlarmsByTaskId(String taskId) {
        log.info("根据任务ID处理告警，任务ID: [{}]", taskId);
        
        // 从数据库查询该任务的所有告警
        List<LogAlarm> alarms = logAlarmMapper.selectByTaskId(taskId);

        // 若该任务没有历史告警，基于任务分析结果补偿生成
        if (alarms == null || alarms.isEmpty()) {
            TaskInfo task = analysisTaskManager.getTask(taskId);
            if (task == null || task.getResult() == null || task.getResult().isEmpty()) {
                log.warn("任务 [{}] 不存在或无分析结果，无法生成告警", taskId);
                return;
            }

            List<String> samples = new ArrayList<>();
            Set<String> reasons = new LinkedHashSet<>();
            for (EnhancedLogParseResultEntity item : task.getResult()) {
                boolean highSeverity = item.getSeverity() == LogSeverityLevel.ERROR_LEVEL
                        || item.getSeverity() == LogSeverityLevel.FATAL_LEVEL;
                if (!item.isAnomaly() && !highSeverity) {
                    continue;
                }
                if (item.getAnomalyReasons() != null) {
                    reasons.addAll(item.getAnomalyReasons());
                }
                String content = item.getDesensitizedLog() == null ? "" : item.getDesensitizedLog();
                samples.add(content.substring(0, Math.min(180, content.length())));
            }

            if (samples.isEmpty()) {
                log.info("任务 [{}] 未发现可告警异常，跳过补偿生成", taskId);
                return;
            }

            StringBuilder alarmContent = new StringBuilder();
            alarmContent.append("【任务告警补偿生成】\n")
                    .append("任务ID: ").append(taskId).append("\n")
                    .append("异常条数: ").append(samples.size()).append("\n")
                    .append("异常原因: ").append(reasons.isEmpty() ? "未提取到明确原因" : String.join(" | ", reasons)).append("\n")
                    .append("异常样例:\n");
            samples.stream().limit(10).forEach(s -> alarmContent.append("- ").append(s).append("\n"));

            analyzeLogAndGenerateAlarm(taskId, alarmContent.toString());
            log.info("任务 [{}] 已补偿生成告警并写入历史", taskId);
            return;
        }

        // 已有告警时执行推送补偿
        for (LogAlarm alarm : alarms) {
            if (!"SUCCESS".equals(alarm.getPushStatus())) {
                pushAlarm(alarm);
            }
        }
    }

    @Override
    public Map<String, Object> getAlarmHistory(int pageNum, int pageSize, String level, String taskId) {
        log.info("获取告警历史列表，页码: [{}], 每页大小: [{}], 告警级别: [{}], 任务ID: [{}]", pageNum, pageSize, level, taskId);
        
        Map<String, Object> result = new HashMap<>();
        
        // 计算偏移量
        int offset = (pageNum - 1) * pageSize;
        
        // 从数据库查询告警列表
        List<LogAlarm> alarms = logAlarmMapper.selectPage(pageNum, pageSize, offset, level, taskId);
        
        // 查询总数
        long total = logAlarmMapper.count(level, taskId);
        
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("list", alarms);
        
        return result;
    }

    @Override
    public Map<String, Object> getAlarmStatistics(int days, String level, String taskId) {
        log.info("获取告警统计分析，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        
        Map<String, Object> statistics = new HashMap<>();
        
        // 从数据库查询告警统计
        Map<String, Object> dbStatistics = logAlarmMapper.selectAlarmStatistics(days, emptyToNull(level), emptyToNull(taskId));
        
        if (dbStatistics != null) {
            long totalAlarms = 0;
            long successAlarms = 0;
            long failedAlarms = 0;
            
            // 处理total_alarms字段，可能是BigDecimal类型
            Object totalObj = dbStatistics.get("total_alarms");
            if (totalObj != null) {
                if (totalObj instanceof BigDecimal) {
                    totalAlarms = ((BigDecimal) totalObj).longValue();
                } else if (totalObj instanceof Long) {
                    totalAlarms = (Long) totalObj;
                } else if (totalObj instanceof Integer) {
                    totalAlarms = (Integer) totalObj;
                }
            }
            
            // 处理success_alarms字段，可能是BigDecimal类型
            Object successObj = dbStatistics.get("success_alarms");
            if (successObj != null) {
                if (successObj instanceof BigDecimal) {
                    successAlarms = ((BigDecimal) successObj).longValue();
                } else if (successObj instanceof Long) {
                    successAlarms = (Long) successObj;
                } else if (successObj instanceof Integer) {
                    successAlarms = (Integer) successObj;
                }
            }
            
            // 处理failed_alarms字段，可能是BigDecimal类型
            Object failedObj = dbStatistics.get("failed_alarms");
            if (failedObj != null) {
                if (failedObj instanceof BigDecimal) {
                    failedAlarms = ((BigDecimal) failedObj).longValue();
                } else if (failedObj instanceof Long) {
                    failedAlarms = (Long) failedObj;
                } else if (failedObj instanceof Integer) {
                    failedAlarms = (Integer) failedObj;
                }
            }
            
            double successRate = totalAlarms > 0 ? (double) successAlarms / totalAlarms * 100 : 0;
            
            statistics.put("totalAlarms", totalAlarms);
            statistics.put("successAlarms", successAlarms);
            statistics.put("failedAlarms", failedAlarms);
            statistics.put("successRate", successRate);
        } else {
            statistics.put("totalAlarms", 0);
            statistics.put("successAlarms", 0);
            statistics.put("failedAlarms", 0);
            statistics.put("successRate", 0);
        }
        
        // 添加其他统计数据
        statistics.put("alarmTrend", getAlarmTrend(days, level, taskId));
        statistics.put("levelDistribution", getAlarmLevelDistribution(days, level, taskId));
        statistics.put("rootCauseStatistics", getAlarmRootCauseStatistics(days, level, taskId));
        
        return statistics;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Override
    public List<Map<String, Object>> getAlarmTrend(int days, String level, String taskId) {
        log.info("获取告警趋势，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        return logAlarmMapper.selectAlarmTrend(days, emptyToNull(level), emptyToNull(taskId));
    }

    @Override
    public List<Map<String, Object>> getAlarmLevelDistribution(int days, String level, String taskId) {
        log.info("获取告警级别分布，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        return logAlarmMapper.selectAlarmLevelDistribution(days, emptyToNull(level), emptyToNull(taskId));
    }

    @Override
    public List<Map<String, Object>> getAlarmRootCauseStatistics(int days, String level, String taskId) {
        log.info("获取告警根因分析统计，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        return logAlarmMapper.selectAlarmRootCauseStatistics(days, emptyToNull(level), emptyToNull(taskId));
    }

    // 在推送告警后将告警添加到历史记录
    private void addAlarmToHistory(LogAlarm alarm) {
        if (alarm != null) {
            // 确保创建时间和更新时间不为null
            if (alarm.getCreateTime() == null) {
                alarm.setCreateTime(LocalDateTime.now());
            }
            if (alarm.getUpdateTime() == null) {
                alarm.setUpdateTime(LocalDateTime.now());
            }
            
            // 插入到数据库
            try {
                int result = logAlarmMapper.insert(alarm);
                log.info("【告警分析】告警添加到历史记录成功，结果: [{}]", result);
            } catch (Exception e) {
                log.error("【告警分析】告警添加到历史记录失败，异常信息: [{}]", e.getMessage(), e);
            }
        } else {
            log.warn("【告警分析】告警对象为null，无法添加到历史记录");
        }
    }

    private boolean shouldAggregate(LogAlarm alarm) {
        if (!aggregationEnabled) {
            return false;
        }
        if (!"task_level_rootCause".equalsIgnoreCase(aggregationDimension)) {
            return false;
        }
        String prefix = alarm.getRootCause() == null ? "" : alarm.getRootCause().substring(0, Math.min(20, alarm.getRootCause().length()));
        LogAlarm latest = logAlarmMapper.selectLatestByFingerprint(alarm.getTaskId(), alarm.getLevel(), prefix, aggregationWindowMinutes);
        return latest != null;
    }

    private String generateAlarmFingerprint(LogAlarm alarm) {
        String rootCause = alarm.getRootCause() == null ? "" : alarm.getRootCause().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String source = (alarm.getTaskId() == null ? "unknownTask" : alarm.getTaskId()) + "|" + (alarm.getLevel() == null ? "UNKNOWN" : alarm.getLevel()) + "|" + rootCause;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(source.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("生成告警指纹失败，使用退化指纹", e);
            return source;
        }
    }

    private int countErrorEntries(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return 0;
        }
        int count = 0;
        String[] lines = logContent.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("fatal") || lower.contains("exception")) {
                count++;
            }
        }
        return count;
    }

    private void updateAlarm(LogAlarm alarm) {
        try {
            int updateResult = logAlarmMapper.update(alarm);
            log.info("【告警推送】数据库状态更新成功，告警ID: [{}], 结果: [{}]", alarm.getAlarmId(), updateResult);
        } catch (Exception e) {
            log.error("【告警推送】数据库状态更新失败，告警ID: [{}], 异常信息: [{}]",
                    alarm.getAlarmId(), e.getMessage(), e);
        }
    }

    private record AlarmDecision(boolean allowed, String reason, String fingerprint) {
        static AlarmDecision skip(String reason) {
            return new AlarmDecision(false, reason, "");
        }

        static AlarmDecision pass(String fingerprint) {
            return new AlarmDecision(true, "PASS", fingerprint);
        }
    }

    /**
     * 解析AI返回的结果，生成结构化的告警信息
     * @param taskId 任务ID
     * @param logContent 日志内容
     * @param aiResponse AI返回的结果
     * @return 结构化的告警信息
     */
    private LogAlarm parseAIResponse(String taskId, String logContent, String aiResponse) {
        LogAlarm alarm = new LogAlarm();
        // 生成唯一告警ID
        alarm.setAlarmId(UUID.randomUUID().toString());
        alarm.setTaskId(taskId);
        alarm.setLogContent(logContent);
        alarm.setCreateTime(LocalDateTime.now());
        alarm.setUpdateTime(LocalDateTime.now());
        alarm.setPushStatus("PENDING");
        
        try {
            // 从AI响应中提取纯JSON部分
            String jsonOnlyResponse = extractJsonFromResponse(aiResponse);
            
            // 使用FastJSON解析AI返回的JSON结果
            // AI返回格式: {"level": "ERROR", "rootCause": "根因", "solution": "解决方案"}
            com.alibaba.fastjson.JSONObject aiResult = com.alibaba.fastjson.JSON.parseObject(jsonOnlyResponse);
            
            // 提取告警级别，默认ERROR
            String level = aiResult.getString("level");
            if (level == null || level.isEmpty()) {
                level = "ERROR";
            }
            alarm.setLevel(level);
            
            // 提取根因分析，默认"AI分析的根因"
            String rootCause = aiResult.getString("rootCause");
            if (rootCause == null || rootCause.isEmpty()) {
                rootCause = "AI分析的根因";
            }
            alarm.setRootCause(rootCause);
            
            // 提取解决方案，默认"AI提供的解决方案"
            String solution = aiResult.getString("solution");
            if (solution == null || solution.isEmpty()) {
                solution = "AI提供的解决方案";
            }
            alarm.setSolution(solution);
            
            log.info("解析AI返回结果成功，告警级别: [{}], 根因: [{}]", level, rootCause);
        } catch (com.alibaba.fastjson.JSONException e) {
            log.error("解析AI返回结果JSON格式异常，AI返回内容: [{}], 异常信息: [{}]", 
                    aiResponse, e.getMessage(), e);
            // 如果解析失败，使用默认值
            setDefaultAlarmValues(alarm, "AI返回结果格式异常");
        } catch (Exception e) {
            log.error("解析AI返回结果异常，AI返回内容: [{}], 异常信息: [{}]", 
                    aiResponse, e.getMessage(), e);
            // 如果解析失败，使用默认值
            setDefaultAlarmValues(alarm, "AI分析失败");
        }
        
        return alarm;
    }
    
    /**
     * 从AI响应中提取纯JSON部分
     * AI响应可能包含描述性文字和JSON标记，例如：
     * "根据提供的日志内容，以下是结构化的告警信息分析：
     * ```json
     * {"level": "ERROR", "rootCause": "根因", "solution": "解决方案"}
     * ```"n     * 该方法会提取出其中的纯JSON内容
     */
    private String extractJsonFromResponse(String aiResponse) {
        // 查找JSON标记
        int jsonStart = aiResponse.indexOf("```json");
        int jsonEnd = aiResponse.indexOf("```", jsonStart + 1);
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            // 提取JSON标记之间的内容，去除前后空白
            return aiResponse.substring(jsonStart + 7, jsonEnd).trim();
        } else {
            // 如果没有JSON标记，尝试直接返回原始响应
            return aiResponse.trim();
        }
    }
    
    /**
     * 设置告警默认值
     * @param alarm 告警对象
     * @param rootCause 根因
     */
    private void setDefaultAlarmValues(LogAlarm alarm, String rootCause) {
        alarm.setLevel("ERROR");
        alarm.setRootCause(rootCause);
        alarm.setSolution("请查看原始日志进行分析");
    }
}