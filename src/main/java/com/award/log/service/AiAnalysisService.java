package com.award.log.service;

import java.util.List;
import java.util.Map;

/**
 * AI智能分析服务接口
 */
public interface AiAnalysisService {

    /**
     * 列出可主动调用的分析 Skill（不含 system 提示词全文）。
     */
    List<Map<String, Object>> listAnalysisSkills();

    /**
     * 按 Skill ID 主动调用（参数见 {@link com.award.log.agent.OpsAnalysisSkillCatalog}）。
     */
    String invokeSkill(String skillId, Map<String, Object> input);

    String analyzeLog(String logContent);

    String analyzePerformance(String metricName, double value);

    String analyzeAlert(String alertContent);

    String generateReport(String period);
}
