package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务级告警配置（阈值、冷却、启停），由 JSON 文件持久化，非数据库表。
 */
@Data
public class TaskAlarmConfig {
    private String taskId;
    private String alarmLevel;
    private Integer errorThreshold;
    private Boolean enabled;
    private Long cooldownMs;
    private LocalDateTime updatedAt;
}
