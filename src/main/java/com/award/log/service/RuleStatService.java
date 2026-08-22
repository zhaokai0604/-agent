package com.award.log.service;

import java.util.Map;

/**
 * 规则命中统计：进程内累加 hit/miss，定时刷库供仪表盘读取。
 */
public interface RuleStatService {
    void record(String ruleId, String ruleName, boolean hit);
    Map<String, Object> summary();
}
