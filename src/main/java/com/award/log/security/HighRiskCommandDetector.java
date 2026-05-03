package com.award.log.security;

import com.award.log.rule.dsl.RuleExpressionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HighRiskCommandDetector {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
        "(rm\\s+-rf\\s+/|rm\\s+-rf\\s+/*|chmod\\s+777\\s+/|dd\\s+if=/dev/(zero|random)|mkfs|fork\\s+bomb|sudo\\s+(rm|mkfs|dd|shutdown|reboot))",
        Pattern.CASE_INSENSITIVE
    );

    private final RuleExpressionEvaluator ruleExpressionEvaluator;
    private final Map<String, HighRiskCommandRule> ruleCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public HighRiskCommandDetector(RuleExpressionEvaluator ruleExpressionEvaluator) {
        this.ruleExpressionEvaluator = ruleExpressionEvaluator;
        // 初始化默认规则
        ruleCache.put(HighRiskCommandRule.createDefaultRule().getId(), HighRiskCommandRule.createDefaultRule());
    }

    public boolean isHighRiskCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        log.info("检测高危命令: {}", command);

        if (ruleExpressionEvaluator != null) {
            boolean result = evaluateWithRuleEngine(command);
            log.info("规则引擎评估结果: {}", result);
            return result;
        } else {
            log.warn("规则引擎不可用，使用内置正则评估");
            boolean result = evaluateWithRegex(command);
            log.info("内置正则评估结果: {}", result);
            return result;
        }
    }

    private boolean evaluateWithRuleEngine(String command) {
        try {
            Map<String, Object> context = Map.of(
                "COMMAND", command.toUpperCase()
            );

            // 评估所有启用的规则
            for (HighRiskCommandRule rule : ruleCache.values()) {
                if (rule.isEnabled()) {
                    boolean matched = ruleExpressionEvaluator.evaluate(rule.getRuleExpression(), context);
                    if (matched) {
                        log.info("规则 [{}] 匹配成功", rule.getName());
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("规则引擎评估异常，降级到正则: {}", e.getMessage());
            return evaluateWithRegex(command);
        }
    }

    private boolean evaluateWithRegex(String command) {
        return HIGH_RISK_PATTERN.matcher(command).find();
    }

    public void registerRule(HighRiskCommandRule rule) {
        if (rule != null && rule.isEnabled()) {
            ruleCache.put(rule.getId(), rule);
            log.info("注册高危命令规则: {}", rule.getName());
        }
    }

    public void unregisterRule(String ruleId) {
        ruleCache.remove(ruleId);
        log.info("移除高危命令规则: {}", ruleId);
    }

    public void clearRules() {
        ruleCache.clear();
        // 保留默认规则
        ruleCache.put(HighRiskCommandRule.createDefaultRule().getId(), HighRiskCommandRule.createDefaultRule());
        log.info("清空并重置默认规则");
    }

    public int getRuleCount() {
        return ruleCache.size();
    }

    public void loadRulesFromConfig(String ruleExpression) {
        if (ruleExpression != null && !ruleExpression.trim().isEmpty()) {
            HighRiskCommandRule configRule = HighRiskCommandRule.builder()
                    .id("CONFIG_RULE")
                    .name("配置文件规则")
                    .description("从配置文件加载的规则")
                    .ruleExpression(ruleExpression)
                    .enabled(true)
                    .build();
            registerRule(configRule);
        }
    }
}