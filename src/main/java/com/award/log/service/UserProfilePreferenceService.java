package com.award.log.service;

import java.util.Map;

/**
 * 用户通知偏好：邮件/短信/任务告警开关；缺记录时用安全默认值。
 */
public interface UserProfilePreferenceService {

    Map<String, Object> getEffectivePreference(Integer userId);

    Map<String, Object> savePreference(Integer userId, Map<String, Object> body);
}
