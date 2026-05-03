package com.award.log.security;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionInput;
import com.award.log.decision.DecisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentRiskFilter {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "(删库|drop\\s+database|关闭防火墙|关防火墙|rm\\s+-rf\\s+/|格式化磁盘|\\bmkfs\\b|\\bfdisk\\b|shutdown|poweroff|reboot|init\\s+0|"
                    + ":mkfs|一键下载.*执行|下载.*\\|\\s*bash|curl[^\\n]{0,400}\\|\\s*sh|wget[^\\n]{0,400}\\|\\s*sh|"
                    + "iptables\\s+-F|iptables\\s+-X|echo\\s+.*>\\s+/etc/sudoers|写入.*sudoers)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MEDIUM_RISK_PATTERN = Pattern.compile(
            "(修改配置|重启服务|systemctl\\s+restart|chmod\\s+[0-7]{3,4}|chown\\s+-R|kill\\s+-9|userdel|groupdel|"
                    + "删除文件|删除临时|真实删除|彻底删除|不要预览|rm\\s+-f\\s+/|truncate\\s+-s\\s+0)",
            Pattern.CASE_INSENSITIVE
    );

    private final CollaborativeDecisionService collaborativeDecisionService;
    private final PromptInjectionGuard promptInjectionGuard;

    @Autowired(required = false)
    public IntentRiskFilter(
            CollaborativeDecisionService collaborativeDecisionService,
            PromptInjectionGuard promptInjectionGuard
    ) {
        this.collaborativeDecisionService = collaborativeDecisionService;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    public RiskLevel evaluate(String userInstruction) {
        log.info("开始风险评估，用户指令: {}", userInstruction);

        // 第一步：检测提示注入攻击
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userInstruction)) {
            log.warn("检测到提示注入攻击，直接提升为 HIGH 风险");
            return RiskLevel.HIGH;
        }

        RiskLevel riskLevel;
        if (collaborativeDecisionService != null) {
            riskLevel = evaluateWithEnsemble(userInstruction);
        } else {
            log.warn("CollaborativeDecisionService 未加载，使用规则降级评估");
            riskLevel = evaluateWithRules(userInstruction);
        }

        log.info("风险评估完成，指令: {}, 风险等级: {}", userInstruction, riskLevel);
        return riskLevel;
    }

    private RiskLevel evaluateWithEnsemble(String userInstruction) {
        try {
            RawLogEvent event = new RawLogEvent();
            event.setContent(userInstruction);
            event.setLevel("INFO");

            DecisionInput input = DecisionInput.builder()
                    .event(event)
                    .template("user_input")
                    .build();

            DecisionResult result = collaborativeDecisionService.decide(input);
            double confidence = result.getConfidence();

            RiskLevel riskLevel;
            if (confidence >= 0.8) {
                riskLevel = RiskLevel.HIGH;
            } else if (confidence >= 0.5) {
                riskLevel = RiskLevel.MEDIUM;
            } else {
                riskLevel = RiskLevel.LOW;
            }

            log.info("融合引擎评估完成，confidence: {}, 风险等级: {}", confidence, riskLevel);
            return riskLevel;

        } catch (Exception e) {
            log.error("融合引擎评估异常，降级为规则评估: {}", e.getMessage());
            return evaluateWithRules(userInstruction);
        }
    }

    private RiskLevel evaluateWithRules(String userInstruction) {
        if (HIGH_RISK_PATTERN.matcher(userInstruction).find()) {
            return RiskLevel.HIGH;
        }
        if (MEDIUM_RISK_PATTERN.matcher(userInstruction).find()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public boolean isHighRisk(String userInstruction) {
        return evaluate(userInstruction) == RiskLevel.HIGH;
    }

    public boolean requiresConfirmation(String userInstruction) {
        RiskLevel level = evaluate(userInstruction);
        return level == RiskLevel.HIGH || level == RiskLevel.MEDIUM;
    }
}