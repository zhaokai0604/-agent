package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则命中/未命中统计窗口，对应 rule_hit_stat 表。
 */
@Data
public class RuleHitStat {
    private Long id;
    private String ruleId;
    private String ruleName;
    private Long hitCount;
    private Long missCount;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private LocalDateTime createTime;
}
