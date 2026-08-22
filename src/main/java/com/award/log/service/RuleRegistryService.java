package com.award.log.service;

import com.award.log.model.RuleDefinition;

import java.util.List;
import java.util.Map;

/**
 * 告警规则注册表：启动加载默认 YAML，运行时以数据库为准并缓存。
 */
public interface RuleRegistryService {
    List<RuleDefinition> list();

    RuleDefinition save(RuleDefinition rule);

    boolean delete(String id);

    boolean testRule(String id, Map<String, Object> payload);
}
