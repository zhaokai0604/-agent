package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则实体，对应 alarm_rule 表（表达式、等级、推送渠道与启停）。
 */
@Data
public class AlarmRuleEntity {
    private Long id;
    private String name;
    private String description;
    private String ruleType;
    private String ruleExpression;
    private String severity;
    private String pushChannels;
    private Boolean enabled;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
