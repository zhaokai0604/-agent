package com.award.log.service.impl;

import com.award.log.model.RuleDefinition;
import com.award.log.rule.dsl.RuleExpressionEvaluator;
import com.award.log.service.RuleRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RuleRegistryServiceImpl implements RuleRegistryService {

    private final Map<String, RuleDefinition> cache = new ConcurrentHashMap<>();
    private final RuleExpressionEvaluator expressionEvaluator;
    private volatile boolean defaultsLoaded = false;

    @Autowired
    public RuleRegistryServiceImpl(RuleExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
        loadDefaults();
    }

    @Override
    public List<RuleDefinition> list() {
        log.debug("获取规则列表，当前规则数量: {}", cache.size());
        return new ArrayList<>(cache.values());
    }

    @Override
    public RuleDefinition save(RuleDefinition rule) {
        log.debug("保存规则: {}", rule.getName());
        try {
            if (rule.getId() == null || rule.getId().isBlank()) {
                rule.setId(UUID.randomUUID().toString().replace("-", ""));
                log.debug("为规则生成新ID: {}", rule.getId());
            }
            
            // 验证规则表达式
            if (!validateRuleExpression(rule.getExpression())) {
                throw new IllegalArgumentException("规则表达式无效");
            }
            
            // 处理空值
            if (rule.getName() == null || rule.getName().isBlank()) {
                throw new IllegalArgumentException("规则名称不能为空");
            }
            if (rule.getExpression() == null || rule.getExpression().isBlank()) {
                throw new IllegalArgumentException("规则表达式不能为空");
            }
            if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
                rule.setSeverity("ERROR");
                log.debug("规则严重程度为空，设置为默认值: ERROR");
            }
            
            cache.put(rule.getId(), rule);
            log.info("规则保存成功: {} (ID: {})", rule.getName(), rule.getId());
            return rule;
        } catch (Exception e) {
            log.error("保存规则失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public boolean delete(String id) {
        log.debug("删除规则，ID: {}", id);
        try {
            boolean removed = cache.remove(id) != null;
            if (removed) {
                log.info("规则删除成功，ID: {}", id);
            } else {
                log.warn("规则删除失败，ID不存在: {}", id);
            }
            return removed;
        } catch (Exception e) {
            log.error("删除规则失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean testRule(String id, Map<String, Object> payload) {
        log.debug("测试规则，ID: {}", id);
        try {
            RuleDefinition rule = cache.get(id);
            if (rule == null) {
                log.warn("测试规则失败，规则不存在，ID: {}", id);
                return false;
            }
            if (!rule.isEnabled()) {
                log.debug("测试规则失败，规则已禁用，ID: {}", id);
                return false;
            }
            boolean result = expressionEvaluator.evaluate(rule.getExpression(), payload);
            log.debug("规则测试结果，ID: {}, 结果: {}", id, result);
            return result;
        } catch (Exception e) {
            log.error("测试规则失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean validateRuleExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try {
            // 简单验证表达式语法
            // 实际项目中可能需要更复杂的验证
            return true;
        } catch (Exception e) {
            log.error("验证规则表达式失败: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDefaults() {
        if (defaultsLoaded) {
            return;
        }
        
        synchronized (this) {
            if (defaultsLoaded) {
                return;
            }
            
            log.info("加载默认规则");
            try (InputStream in = getClass().getResourceAsStream("/rules/default-rules.yml")) {
                if (in == null) {
                    log.warn("默认规则文件不存在");
                    defaultsLoaded = true;
                    return;
                }
                
                Yaml yaml = new Yaml();
                Object loadedObject = yaml.load(in);
                
                if (!(loadedObject instanceof Map)) {
                    log.warn("默认规则文件格式错误，根节点不是Map");
                    defaultsLoaded = true;
                    return;
                }
                
                Map<String, Object> root = (Map<String, Object>) loadedObject;
                Object rulesObject = root.getOrDefault("rules", null);
                
                if (!(rulesObject instanceof List)) {
                    log.warn("默认规则文件格式错误，rules节点不是List");
                    defaultsLoaded = true;
                    return;
                }
                
                List<?> rulesList = (List<?>) rulesObject;
                for (Object item : rulesList) {
                    if (!(item instanceof Map)) {
                        log.warn("默认规则文件格式错误，规则项不是Map");
                        continue;
                    }
                    
                    Map<String, Object> ruleMap = (Map<String, Object>) item;
                    RuleDefinition r = new RuleDefinition();
                    
                    // 处理空值
                    Object idObj = ruleMap.get("id");
                    if (idObj != null) {
                        r.setId(String.valueOf(idObj));
                    } else {
                        r.setId(UUID.randomUUID().toString().replace("-", ""));
                        log.debug("规则ID为空，生成新ID: {}", r.getId());
                    }
                    
                    Object nameObj = ruleMap.get("name");
                    if (nameObj != null) {
                        r.setName(String.valueOf(nameObj));
                    } else {
                        r.setName("未命名规则");
                        log.debug("规则名称为空，设置为默认值: 未命名规则");
                    }
                    
                    Object expressionObj = ruleMap.get("expression");
                    if (expressionObj != null) {
                        r.setExpression(String.valueOf(expressionObj));
                    } else {
                        log.warn("规则表达式为空，跳过该规则");
                        continue;
                    }
                    
                    Object severityObj = ruleMap.getOrDefault("severity", "ERROR");
                    r.setSeverity(String.valueOf(severityObj));
                    
                    Object enabledObj = ruleMap.getOrDefault("enabled", true);
                    r.setEnabled(Boolean.parseBoolean(String.valueOf(enabledObj)));
                    
                    // 验证规则
                    if (validateRuleExpression(r.getExpression())) {
                        cache.put(r.getId(), r);
                        log.debug("加载默认规则: {}", r.getName());
                    } else {
                        log.warn("默认规则表达式无效，跳过该规则: {}", r.getName());
                    }
                }
                
                log.info("默认规则加载完成，加载规则数量: {}", cache.size());
            } catch (Exception e) {
                log.error("加载默认规则失败: {}", e.getMessage(), e);
            } finally {
                defaultsLoaded = true;
            }
        }
    }
}
