package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 告警配置控制器
 * 用于处理全局告警配置的接口
 */
@Slf4j
@RestController
@RequestMapping("/api/alarm")
public class AlarmConfigController {
    @Autowired
    private AlarmConfigService alarmConfigService;

    /**
     * 获取告警配置
     * @return 操作结果
     */
    @GetMapping("/config")
    public Result<TaskAlarmConfig> getAlarmConfig() {
        try {
            return Result.success(alarmConfigService.getDefaultConfig(), "获取默认告警配置成功");
        } catch (Exception e) {
            log.error("获取告警配置失败", e);
            return Result.error("获取告警配置失败: " + e.getMessage());
        }
    }

    /**
     * 保存告警配置
     * @param config 告警配置
     * @return 操作结果
     */
    @PostMapping("/config")
    public Result<TaskAlarmConfig> saveAlarmConfig(@RequestBody Map<String, Object> config) {
        return saveTaskAlarmConfig("default", config);
    }

    @GetMapping("/config/{taskId}")
    public Result<TaskAlarmConfig> getTaskAlarmConfig(@PathVariable String taskId) {
        try {
            return Result.success(alarmConfigService.getEffectiveConfig(taskId), "获取任务告警配置成功");
        } catch (Exception e) {
            log.error("获取任务告警配置失败, taskId={}", taskId, e);
            return Result.error("获取任务告警配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/config/{taskId}")
    public Result<TaskAlarmConfig> saveTaskAlarmConfig(@PathVariable String taskId, @RequestBody Map<String, Object> config) {
        try {
            TaskAlarmConfig saved = alarmConfigService.saveTaskConfig(taskId, config);
            return Result.success(saved, "保存任务告警配置成功");
        } catch (Exception e) {
            log.error("保存告警配置失败", e);
            return Result.error("保存告警配置失败: " + e.getMessage());
        }
    }
}
