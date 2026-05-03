package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AiLogAlarmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警历史Controller
 * 用于处理告警历史分析的请求
 */
@Slf4j
@Tag(name = "AlarmHistory")
@RestController
@RequestMapping("/api/alarm/history")
public class AlarmHistoryController {

    @Autowired
    private AiLogAlarmService aiLogAlarmService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Operation(summary = "获取告警历史列表")
    @GetMapping("/list")
    public Result<Map<String, Object>> getAlarmHistory(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String taskId) {
        log.info("获取告警历史列表，页码: [{}], 每页大小: [{}], 告警级别: [{}], 任务ID: [{}]", pageNum, pageSize, level, taskId);
        Map<String, Object> history = aiLogAlarmService.getAlarmHistory(pageNum, pageSize, level, taskId);
        return Result.success(history);
    }

    @Operation(summary = "获取告警统计分析")
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getAlarmStatistics(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String taskId) {
        log.info("获取告警统计分析，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        Map<String, Object> statistics = aiLogAlarmService.getAlarmStatistics(days, level, taskId);
        return Result.success(statistics);
    }

    @Operation(summary = "获取告警趋势")
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> getAlarmTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String taskId) {
        log.info("获取告警趋势，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        List<Map<String, Object>> trend = aiLogAlarmService.getAlarmTrend(days, level, taskId);
        return Result.success(trend);
    }

    @Operation(summary = "获取告警级别分布")
    @GetMapping("/level-distribution")
    public Result<List<Map<String, Object>>> getAlarmLevelDistribution(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String taskId) {
        log.info("获取告警级别分布，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        List<Map<String, Object>> distribution = aiLogAlarmService.getAlarmLevelDistribution(days, level, taskId);
        return Result.success(distribution);
    }

    @Operation(summary = "获取告警根因分析统计")
    @GetMapping("/root-cause-statistics")
    public Result<List<Map<String, Object>>> getAlarmRootCauseStatistics(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String taskId) {
        log.info("获取告警根因分析统计，天数: [{}], 级别: [{}], 任务: [{}]", days, level, taskId);
        List<Map<String, Object>> statistics = aiLogAlarmService.getAlarmRootCauseStatistics(days, level, taskId);
        return Result.success(statistics);
    }

    @Operation(summary = "根据任务ID处理告警")
    @GetMapping("/process-by-task")
    public Result<Void> processAlarmsByTaskId(
            @RequestParam String taskId) {
        log.info("根据任务ID处理告警，任务ID: [{}]", taskId);
        aiLogAlarmService.processAlarmsByTaskId(taskId);
        return Result.success();
    }

    @Operation(summary = "告警历史调试信息")
    @GetMapping("/debug/summary")
    public Result<Map<String, Object>> debugSummary() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM log_alarm", Long.class);
        String currentDb = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("alarmTotal", total == null ? 0 : total);
        data.put("currentDatabase", currentDb);
        data.put("datasourceUrl", datasourceUrl);
        return Result.success(data);
    }

}
