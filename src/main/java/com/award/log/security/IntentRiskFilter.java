package com.award.log.security;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionInput;
import com.award.log.decision.DecisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentRiskFilter {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "(删库|drop\\s+database|关闭防火墙|关防火墙|rm\\s+-rf\\s*/?|格式化磁盘|\\bmkfs\\b|\\bfdisk\\b|shutdown|poweroff|reboot|init\\s+0|"
                    + ":mkfs|一键下载.*执行|下载.*\\|\\s*bash|curl[^\\n]{0,400}\\|\\s*sh|wget[^\\n]{0,400}\\|\\s*sh|"
                    + "iptables\\s+-F|iptables\\s+-X|echo\\s+.*>\\s+/etc/sudoers|写入.*sudoers|"
                    + "删除.{0,16}(整个|全部|所有|根|系统)|删.{0,4}(整个|全部|所有).{0,12}(系统|根|盘|文件)|清空.{0,8}(系统|根|盘))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MEDIUM_RISK_PATTERN = Pattern.compile(
            "(修改配置|重启服务|systemctl\\s+restart|chmod\\s+[0-7]{3,4}|chown\\s+-R|kill\\s+-9|userdel|groupdel|"
                    + "删除文件|删除临时|真实删除|彻底删除|不要预览|rm\\s+-f\\s+/|truncate\\s+-s\\s+0)",
            Pattern.CASE_INSENSITIVE
    );

    /** 与高危意图语义对齐的短种子（Jaccard 词袋相似度命中即提升为 HIGH，缓解「换说法绕过」） */
    private static final String[] HIGH_RISK_SEED_PHRASES = {
            "删库", "删除根目录", "删除整个系统文件", "删除全部系统文件", "rm -rf /", "drop database",
            "关闭防火墙", "清空防火墙规则", "格式化磁盘", "一键下载执行", "curl bash", "wget sh", "shutdown now"
    };

    private final CollaborativeDecisionService collaborativeDecisionService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final boolean intentUseMl;

    @Autowired(required = false)
    public IntentRiskFilter(
            CollaborativeDecisionService collaborativeDecisionService,
            PromptInjectionGuard promptInjectionGuard,
            @org.springframework.beans.factory.annotation.Value("${agent.security.intent-use-ml:false}") boolean intentUseMl
    ) {
        this.collaborativeDecisionService = collaborativeDecisionService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.intentUseMl = intentUseMl;
    }

    public RiskLevel evaluate(String userInstruction) {
        userInstruction = AgenticRiskScoreEngine.normalizeUtterance(userInstruction);
        log.info("开始风险评估，用户指令: {}", userInstruction);

        // 第一步：检测提示注入攻击
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userInstruction)) {
            log.warn("检测到提示注入攻击，直接提升为 HIGH 风险");
            return RiskLevel.HIGH;
        }

        if (looksSemanticallyHighRisk(userInstruction)) {
            log.warn("语义相似度命中高危种子短语，提升为 HIGH 风险");
            return RiskLevel.HIGH;
        }

        RiskLevel riskLevel;
        if (intentUseMl && collaborativeDecisionService != null) {
            riskLevel = evaluateWithEnsemble(userInstruction);
        } else {
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

    private boolean looksSemanticallyHighRisk(String text) {
        if (text == null || text.length() < 4) {
            return false;
        }
        Set<String> doc = wordTokens(text);
        if (doc.size() < 2) {
            return false;
        }
        for (String seed : HIGH_RISK_SEED_PHRASES) {
            double jac = jaccard(doc, wordTokens(seed));
            if (jac >= 0.34) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> wordTokens(String s) {
        Set<String> out = new HashSet<>();
        String lower = s.toLowerCase();
        for (String p : lower.split("[^\\p{L}\\p{N}]+")) {
            if (p.length() >= 2) {
                out.add(p);
            }
        }
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                han.append(c);
            }
        }
        String h = han.toString();
        for (int i = 0; i + 1 < h.length(); i++) {
            out.add(h.substring(i, i + 2));
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }
}