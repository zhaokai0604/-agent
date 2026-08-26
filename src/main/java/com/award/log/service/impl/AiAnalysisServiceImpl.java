package com.award.log.service.impl;

import com.award.log.agent.OpsAnalysisSkillCatalog;
import com.award.log.service.AiAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 分析服务：接入 Spring AI（DashScope 兼容接口），未配置密钥时不返回伪造结论。
 */
@Slf4j
@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public AiAnalysisServiceImpl(@Autowired(required = false) ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    private boolean aiReady() {
        return chatClient != null && apiKey != null && !apiKey.isBlank();
    }

    private String ask(String systemHint, String userContent) {
        if (!aiReady()) {
            return "AI 分析未就绪：请在环境变量或 application-local.yml 中配置 AI_API_KEY。";
        }
        try {
            return chatClient.prompt()
                    .system(systemHint)
                    .user(userContent)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("AI 分析请求失败: {}", e.getMessage());
            return "AI 分析请求失败：" + e.getMessage();
        }
    }

    @Override
    public List<Map<String, Object>> listAnalysisSkills() {
        return OpsAnalysisSkillCatalog.listViews();
    }

    @Override
    public String invokeSkill(String skillId, Map<String, Object> input) {
        return OpsAnalysisSkillCatalog.find(skillId)
                .map(def -> ask(def.systemPrompt(), def.buildUserPrompt(input)))
                .orElse("未找到 Skill：" + skillId);
    }

    @Override
    public String analyzeLog(String logContent) {
        return invokeSkill("log_analysis", Map.of("content", logContent == null ? "" : logContent));
    }

    @Override
    public String analyzePerformance(String metricName, double value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("metricName", metricName);
        input.put("metricValue", value);
        return invokeSkill("performance_analysis", input);
    }

    @Override
    public String analyzeAlert(String alertContent) {
        return invokeSkill("alert_triage", Map.of("content", alertContent == null ? "" : alertContent));
    }

    @Override
    public String generateReport(String period) {
        return invokeSkill("ops_report", Map.of(
                "period", period == null ? "24h" : period.trim().toLowerCase(Locale.ROOT)));
    }
}
