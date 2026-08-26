package com.award.log.mcp.tools;

import com.award.log.agent.OpsAnalysisSkillCatalog;
import com.award.log.mcp.McpTool;
import com.award.log.mcp.McpToolResponses;
import com.award.log.service.AiAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话 Agent 可调用的 AI 分析 Skill 入口：纯 LLM 推理，不执行主机命令。
 * 与 MCP 观测/写工具并列，由模型按场景自行选择。
 */
@Slf4j
@McpTool(value = "OpsAnalysisSkillTool", httpAllowed = true, readOnlyObservation = true, defaultRiskScore = 0.5)
@Component
public class OpsAnalysisSkillTool {

    private final AiAnalysisService aiAnalysisService;
    private final ObjectMapper objectMapper;

    public OpsAnalysisSkillTool(AiAnalysisService aiAnalysisService, ObjectMapper objectMapper) {
        this.aiAnalysisService = aiAnalysisService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "listOpsAnalysisSkills",
            description = "List AI analysis skills (prompt-only reasoning). Use before invokeOpsAnalysisSkill when unsure which skillId fits.")
    public String listOpsAnalysisSkills() throws JsonProcessingException {
        long start = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skills", aiAnalysisService.listAnalysisSkills());
        payload.put("hint", "Call invokeOpsAnalysisSkill with skillId and required params.");
        return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(payload),
                System.currentTimeMillis() - start);
    }

    @Tool(name = "invokeOpsAnalysisSkill",
            description = """
                    Invoke an AI analysis skill for log/alert/report/RCA/SLO reasoning without running host commands.
                    skillId examples: log_analysis, performance_analysis, alert_triage, ops_report, incident_rca,
                    runbook_draft, security_log_review, capacity_review, postmortem_draft, slo_burn_review.
                    Prefer this when the user pasted logs/alerts/incidents or needs structured expert analysis;
                    prefer DiskTool/ProcessTool/etc. when live host metrics or file operations are needed.""")
    public String invokeOpsAnalysisSkill(
            @ToolParam(description = "Skill ID from listOpsAnalysisSkills") String skillId,
            @ToolParam(description = "Primary text: logs, alerts, incident description", required = false) String content,
            @ToolParam(description = "Metric name for performance_analysis", required = false) String metricName,
            @ToolParam(description = "Metric numeric value for performance_analysis", required = false) Double metricValue,
            @ToolParam(description = "Time period for ops_report: 1h/24h/7d/30d", required = false) String period,
            @ToolParam(description = "Environment for runbook_draft: Linux/Windows/Kubernetes", required = false)
            String environment,
            @ToolParam(description = "SLO target for slo_burn_review, e.g. 99.9%", required = false) String sloTarget
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (skillId == null || skillId.isBlank()) {
            return McpToolResponses.error(objectMapper, "skillId 不能为空", start);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        putIfPresent(input, "content", content);
        putIfPresent(input, "metricName", metricName);
        if (metricValue != null) {
            input.put("metricValue", metricValue);
        }
        putIfPresent(input, "period", period);
        putIfPresent(input, "environment", environment);
        putIfPresent(input, "sloTarget", sloTarget);

        return OpsAnalysisSkillCatalog.find(skillId.trim())
                .map(def -> {
                    try {
                        String answer = aiAnalysisService.invokeSkill(def.id(), input);
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("skillId", def.id());
                        payload.put("skillName", def.name());
                        payload.put("outputSections", def.outputSections());
                        payload.put("answer", answer);
                        return McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(payload),
                                System.currentTimeMillis() - start);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElseGet(() -> {
                    try {
                        List<String> known = aiAnalysisService.listAnalysisSkills().stream()
                                .map(m -> String.valueOf(m.get("id")))
                                .toList();
                        return McpToolResponses.error(objectMapper,
                                "未知 skillId: " + skillId + "；可用: " + String.join(", ", known), start);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void putIfPresent(Map<String, Object> input, String key, String value) {
        if (value != null && !value.isBlank()) {
            input.put(key, value.trim());
        }
    }
}
