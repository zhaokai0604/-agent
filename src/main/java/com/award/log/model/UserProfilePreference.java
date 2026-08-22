package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户通知偏好（邮件/短信/任务告警开关），对应 user_profile_preference 表。
 */
@Data
public class UserProfilePreference {
    private Integer userId;
    private Boolean emailEnabled;
    private Boolean smsEnabled;
    private Boolean taskAlerts;
    private LocalDateTime updateTime;
}
