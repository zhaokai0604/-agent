package com.award.log.service;

import com.award.log.model.TaskAlarmConfig;

import java.util.Map;

public interface AlarmConfigService {
    TaskAlarmConfig getDefaultConfig();

    TaskAlarmConfig getTaskConfig(String taskId);

    TaskAlarmConfig getEffectiveConfig(String taskId);

    TaskAlarmConfig saveTaskConfig(String taskId, Map<String, Object> rawConfig);
}
