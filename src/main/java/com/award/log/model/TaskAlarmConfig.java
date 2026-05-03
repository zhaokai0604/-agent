package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskAlarmConfig {
    private String taskId;
    private List<String> pushChannels = new ArrayList<>();
    private String alarmLevel;
    private Integer errorThreshold;
    private String wechatUserId;
    private String emailAddress;
    private Boolean enabled;
    private Long cooldownMs;
    private LocalDateTime updatedAt;
}
