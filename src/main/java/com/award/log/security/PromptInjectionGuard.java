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
public class PromptInjectionGuard {

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+previous\\s+instructions|disregard\\s+safety|pretend\\s+you\\s+are|execute\\s+this:|system\\s+prompt|role\\s+play|bypass\\s+security|override\\s+instructions|reset\\s+prompt|ignore\\s+guidelines|"
                    + "developer\\s+mode|jailbreak|DAN\\s+mode|\\bDAN\\b\\s*[:：]|reveal\\s+your\\s+prompt|show\\s+the\\s+prompt|"
                    + "忽略\\s*上文|忽略.*指令|绕过.*安全|越狱|假装你是|解除限制|输出.*系统提示|泄露.*提示词)"
    );

    private final RuleExpressionEvaluator ruleExpressionEvaluator;
    private final Map<String, InjectionRule> ruleCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public PromptInjectionGuard(RuleExpressionEvaluator ruleExpressionEvaluator) {
        this.ruleExpressionEvaluator = ruleExpressionEvaluator;
        // 初始化默认规则
        ruleCache.put(InjectionRule.createDefaultRule().getId(), InjectionRule.createDefaultRule());
    }

    public boolean isInjection(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return false;
        }

        log.info("检测注入攻击，用户输入: {}", userInput);

        if (ruleExpressionEvaluator != null) {
            boolean result = evaluateWithRuleEngine(userInput);
            log.info("规则引擎评估注入结果: {}", result);
            return result;
        } else {
            log.warn("规则引擎不可用，使用内置正则评估");
            boolean result = evaluateWithRegex(userInput);
            log.info("内置正则评估注入结果: {}", result);
            return result;
        }
    }

    private boolean evaluateWithRuleEngine(String userInput) {
        try {
            Map<String, Object> context = Map.of(
                "USER_INPUT", userInput.toUpperCase()
            );

            // 评估所有启用的规则
            for (InjectionRule rule : ruleCache.values()) {
                if (rule.isEnabled()) {
                    boolean matched = ruleExpressionEvaluator.evaluate(rule.getRuleExpression(), context);
                    if (matched) {
                        log.info("注入规则 [{}] 匹配成功", rule.getName());
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("规则引擎评估异常，降级到正则: {}", e.getMessage());
            return evaluateWithRegex(userInput);
        }
    }

    private boolean evaluateWithRegex(String userInput) {
        return INJECTION_PATTERN.matcher(userInput).find();
    }

    public void registerRule(InjectionRule rule) {
        if (rule != null && rule.isEnabled()) {
            ruleCache.put(rule.getId(), rule);
            log.info("注册注入检测规则: {}", rule.getName());
        }
    }

    public void unregisterRule(String ruleId) {
        ruleCache.remove(ruleId);
        log.info("移除注入检测规则: {}", ruleId);
    }

    public void clearRules() {
        ruleCache.clear();
        // 保留默认规则
        ruleCache.put(InjectionRule.createDefaultRule().getId(), InjectionRule.createDefaultRule());
        log.info("清空并重置默认注入规则");
    }

    public int getRuleCount() {
        return ruleCache.size();
    }

    public void loadRulesFromConfig(String ruleExpression) {
        if (ruleExpression != null && !ruleExpression.trim().isEmpty()) {
            InjectionRule configRule = InjectionRule.builder()
                    .id("CONFIG_INJECTION_RULE")
                    .name("配置文件注入规则")
                    .description("从配置文件加载的注入检测规则")
                    .ruleExpression(ruleExpression)
                    .enabled(true)
                    .build();
            registerRule(configRule);
        }
    }

    public static class InjectionRule {
        private String id;
        private String name;
        private String description;
        private String ruleExpression;
        private boolean enabled;

        public static InjectionRule createDefaultRule() {
            InjectionRule rule = new InjectionRule();
            rule.id = "DEFAULT_INJECTION_RULE";
            rule.name = "默认注入检测规则";
            rule.description = "检测常见的提示注入攻击";
            rule.ruleExpression = "(USER_INPUT CONTAINS 'IGNORE PREVIOUS INSTRUCTIONS') OR (USER_INPUT CONTAINS 'DISREGARD SAFETY') OR (USER_INPUT CONTAINS 'PRETEND YOU ARE') OR (USER_INPUT CONTAINS 'EXECUTE THIS:') OR (USER_INPUT CONTAINS 'SYSTEM PROMPT') OR (USER_INPUT CONTAINS 'ROLE PLAY') OR (USER_INPUT CONTAINS 'BYPASS SECURITY') OR (USER_INPUT CONTAINS 'OVERRIDE INSTRUCTIONS') OR (USER_INPUT CONTAINS 'RESET PROMPT') OR (USER_INPUT CONTAINS 'IGNORE GUIDELINES')";
            rule.enabled = true;
            return rule;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getRuleExpression() {
            return ruleExpression;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public static class Builder {
            private final InjectionRule rule = new InjectionRule();

            public Builder id(String id) {
                rule.id = id;
                return this;
            }

            public Builder name(String name) {
                rule.name = name;
                return this;
            }

            public Builder description(String description) {
                rule.description = description;
                return this;
            }

            public Builder ruleExpression(String ruleExpression) {
                rule.ruleExpression = ruleExpression;
                return this;
            }

            public Builder enabled(boolean enabled) {
                rule.enabled = enabled;
                return this;
            }

            public InjectionRule build() {
                return rule;
            }
        }
    }
}