package com.award.log.controller;

import com.award.log.analyzer.LogCleaner;
import com.award.log.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志清洗控制器
 * 用于处理日志清洗相关的请求
 */
@Slf4j
@RestController
@RequestMapping("/api/log/clean")
public class LogCleanController {

    @Resource
    private LogCleaner logCleaner;

    // 存储清洗规则的内存存储
    private final Map<String, LogCleaner.CleanRule> cleanRulesMap = new ConcurrentHashMap<>();

    /**
     * 获取所有清洗规则
     */
    @GetMapping("/rules")
    public Result<List<LogCleaner.CleanRule>> getCleanRules() {
        try {
            List<LogCleaner.CleanRule> rules = cleanRulesMap.values().stream().toList();
            return Result.success(rules);
        } catch (Exception e) {
            log.error("获取清洗规则失败: {}", e.getMessage(), e);
            return Result.error("获取清洗规则失败");
        }
    }

    /**
     * 保存清洗规则
     */
    @PostMapping("/rules")
    public Result<Void> saveCleanRules(@RequestBody List<LogCleaner.CleanRule> rules) {
        try {
            // 清空现有规则
            cleanRulesMap.clear();
            // 保存新规则
            for (LogCleaner.CleanRule rule : rules) {
                cleanRulesMap.put(rule.getId(), rule);
            }
            log.info("保存清洗规则成功，共 {} 条规则", rules.size());
            return Result.success();
        } catch (Exception e) {
            log.error("保存清洗规则失败: {}", e.getMessage(), e);
            return Result.error("保存清洗规则失败");
        }
    }

    /**
     * 执行日志清洗
     */
    @PostMapping
    public Result<String> cleanLog(@RequestBody Map<String, Object> request) {
        try {
            String logContent = (String) request.get("logContent");
            List<LogCleaner.CleanRule> rules = (List<LogCleaner.CleanRule>) request.get("rules");

            if (logContent == null) {
                return Result.error("日志内容不能为空");
            }

            String cleanedContent = logCleaner.cleanLog(logContent, rules);
            return Result.success(cleanedContent);
        } catch (Exception e) {
            log.error("清洗日志失败: {}", e.getMessage(), e);
            return Result.error("清洗日志失败");
        }
    }
}
