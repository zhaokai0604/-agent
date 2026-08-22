package com.award.log.model;

import lombok.Data;

/**
 * 告警规则运行时定义，由 alarm_rule 映射，供规则注册表加载与测试。
 */
@Data
public class RuleDefinition {
    private String id;
    private String name;
    private String description;
    private String ruleType;
    private String expression;
    private String severity;
    private String pushChannels;
    private boolean enabled;
}
