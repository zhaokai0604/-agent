package com.award.log.agent;

import com.award.log.model.LogAlarm;
import com.award.log.security.ReadOnlySurfaceDenylist;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.KnowledgeBaseService;
import com.award.log.service.OpsOpenIncidentService;
import com.award.log.service.OpsPatrolService;
import com.award.log.service.StatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为统一调度助手拼装「多角色 Agent」只读摘要：感知 / 清理策略提示 / 诊断线索 / 调度说明。
 * 不包含真实多进程；通过结构化 JSON 约束大模型分角色推理。
 */
@Slf4j
@Component
public class MultiAgentContextBuilder {

    private final StatisticsService statisticsService;
    private final AiLogAlarmService aiLogAlarmService;
    private final DrainTemplateNoveltyTracker noveltyTracker;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired(required = false)
    private OpsPatrolService opsPatrolService;

    @Autowired(required = false)
    private OpsOpenIncidentService opsOpenIncidentService;

    @Autowired(required = false)
    private ReadOnlySurfaceDenylist readOnlySurfaceDenylist;

    public MultiAgentContextBuilder(
            StatisticsService statisticsService,
            AiLogAlarmService aiLogAlarmService,
            DrainTemplateNoveltyTracker noveltyTracker) {
        this.statisticsService = statisticsService;
        this.aiLogAlarmService = aiLogAlarmService;
        this.noveltyTracker = noveltyTracker;
    }

    public Map<String, Object> build() {
        return build(null);
    }

    /**
     * @param userQuery 当前用户问题，用于知识库 RAG 检索相似运维案例
     */
    public Map<String, Object> build(String userQuery) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("perceptionAgent", buildPerception());
        root.put("cleanupAgentPolicy", buildCleanupPolicy());
        root.put("diagnosisAgent", buildDiagnosis(userQuery));
        root.put("orchestratorAgent", buildOrchestratorHints());
        root.put("correlationDigest", buildCorrelationDigest());
        if (opsOpenIncidentService != null) {
            root.put("openIncident", opsOpenIncidentService.buildOpenIncidentContext());
        }
        return root;
    }

    private Map<String, Object> buildPerception() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("performance", statisticsService.getSystemPerformance(null));
        } catch (Exception e) {
            log.debug("感知 Agent：性能采集失败 {}", e.getMessage());
            m.put("performance", Map.of("unavailable", true));
        }
        try {
            m.put("taskStats", statisticsService.getTaskStatusStatistics());
        } catch (Exception e) {
            m.put("taskStats", Map.of("unavailable", true));
        }
        m.put("drainTemplateSignal", noveltyTracker.snapshotForContext());
        if (opsPatrolService != null) {
            m.put("lastPatrolFindings", opsPatrolService.getLastFindingsSnapshot());
        }
        return m;
    }

    private Map<String, Object> buildCleanupPolicy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readOnlyToolExamples", List.of(
                "DiskInsightTool", "DiskAnalyzeTool", "LogAnalysisTool", "ProcessTool",
                "SystemLoadTool", "NetworkTool", "ConfigCheckTool", "OsInsightTool"));
        m.put("writeToolsRequireConfirm", List.of(
                "CleanTempTool", "LogCleanupTool", "ServiceRestartTool", "PrivilegeTool"));
        m.put("pathPolicyVersion", "见 agent.paths.policy-version 与 OpsPathPolicy");
        if (readOnlySurfaceDenylist != null) {
            ArrayList<String> names = new ArrayList<>(readOnlySurfaceDenylist.snapshot());
            Collections.sort(names);
            m.put("readOnlyDeniedToolNames", names);
        }
        m.put("rules", List.of(
                "所有写路径必须在白名单内",
                "Destructive 操作需控制台二次确认",
                "当上下文 sessionSecurityPolicy.toolSurface=READ_ONLY 时，模型不得规划写类 MCP 工具",
                "当 toolSurface=FULL 时，可在 cleanupAgentPolicy 与路径白名单内调用写类 MCP，并在答复中写明范围与回滚",
                "只读面禁止工具集可由服务端 agent.security.read-only-extra-denied-tool-beans 追加（与 Bean 短名一致）",
                "高危 utterance 风险分时，优先只读观测与人工升级路径"
        ));
        return m;
    }

    private Map<String, Object> buildDiagnosis(String userQuery) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> alarms = aiLogAlarmService.getAlarmStatistics(1, null, null);
            m.put("alarmStats24h", alarms != null ? alarms : Map.of());
            Map<String, Object> recent = aiLogAlarmService.getAlarmHistory(1, 8, null, null);
            m.put("recentAlarmTitles", extractAlarmTitles(recent));
        } catch (Exception e) {
            log.debug("诊断 Agent：告警摘要失败 {}", e.getMessage());
            m.put("alarmStats24h", Map.of("unavailable", true));
        }
        try {
            Map<String, Object> anomaly = statisticsService.getAnomalyLogStatistics(1);
            m.put("anomalyLogDay1", anomaly != null ? anomaly : Map.of());
        } catch (Exception e) {
            m.put("anomalyLogDay1", Map.of("unavailable", true));
        }
        m.put("similarHistoricalCases", searchSimilarCases(userQuery));
        return m;
    }

    private List<String> extractAlarmTitles(Map<String, Object> recent) {
        List<String> titles = new ArrayList<>();
        if (recent == null) {
            return titles;
        }
        Object list = recent.get("list");
        if (!(list instanceof List<?> raw)) {
            return titles;
        }
        for (Object row : raw) {
            if (row instanceof LogAlarm la) {
                String s = la.getRootCause() != null && !la.getRootCause().isBlank()
                        ? la.getRootCause()
                        : la.getLogContent();
                if (s != null && !s.isBlank()) {
                    if (s.length() > 160) {
                        s = s.substring(0, 160) + "…";
                    }
                    titles.add(s);
                }
            } else if (row instanceof Map<?, ?> map) {
                Object msg = map.get("message");
                if (msg == null) {
                    msg = map.get("title");
                }
                if (msg != null) {
                    String s = String.valueOf(msg);
                    if (s.length() > 160) {
                        s = s.substring(0, 160) + "…";
                    }
                    titles.add(s);
                }
            }
        }
        return titles;
    }

    private List<Map<String, Object>> searchSimilarCases(String userQuery) {
        if (knowledgeBaseService == null) {
            return List.of();
        }
        StringBuilder q = new StringBuilder();
        if (userQuery != null && !userQuery.isBlank()) {
            q.append(userQuery.trim());
        } else {
            q.append("运维 异常 日志 磁盘 内存 CPU 告警 服务");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> perf = (Map<String, Object>) statisticsService.getSystemPerformance(null);
            q.append(' ').append(perf.getOrDefault("cpuUsage", ""));
            q.append(' ').append(perf.getOrDefault("diskUsage", ""));
        } catch (Exception ignored) {
            // ignore
        }
        try {
            return knowledgeBaseService.search(q.toString(), 5);
        } catch (Exception e) {
            log.debug("知识库 RAG 检索跳过: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildOrchestratorHints() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "调度 Agent（UnifiedAssistantService）");
        m.put("workflow", List.of(
                "1. 用 perceptionAgent 事实陈述环境",
                "2. 用 diagnosisAgent 做证据链；引用 similarHistoricalCases（知识库 RAG）时须标注来源标题",
                "3. 涉及写操作前引用 cleanupAgentPolicy 并提示二次确认",
                "4. 用户意图高危时明确拒绝并引用安全结论"
        ));
        m.put("toolRiskGuidance", Map.of(
                "LOW", "可读可分析；低风险写操作仍须白名单",
                "MEDIUM", "默认仅预览 / Dry-Run；执行需确认",
                "HIGH", "禁止执行写与提权；只读诊断与上报"
        ));
        return m;
    }

    private Map<String, Object> buildCorrelationDigest() {
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> perf = (Map<String, Object>) statisticsService.getSystemPerformance(null);
            d.put("cpuUsagePct", perf.get("cpuUsage"));
            d.put("memoryUsagePct", perf.get("memoryUsage"));
            d.put("diskUsagePct", perf.get("diskUsage"));
            d.put("networkUsagePct", perf.get("networkUsage"));
        } catch (Exception e) {
            d.put("performance", "unavailable");
        }
        try {
            Map<String, Object> alarms = aiLogAlarmService.getAlarmStatistics(1, null, null);
            if (alarms != null) {
                d.put("alarmTotal24h", alarms.get("totalAlarms"));
                d.put("alarmSuccessRate", alarms.get("successRate"));
            }
        } catch (Exception ignored) {
            d.put("alarms", "unavailable");
        }
        try {
            d.put("anomalyLogDay1", statisticsService.getAnomalyLogStatistics(1));
        } catch (Exception ignored) {
            d.put("anomalyLogDay1", "unavailable");
        }
        d.put("drainTemplateSignal", noveltyTracker.snapshotForContext());
        if (opsPatrolService != null) {
            d.put("lastPatrolFindings", opsPatrolService.getLastFindingsSnapshot());
        }
        d.put("note", "多维摘要：将磁盘/告警/异常日志/Drain 新模板与上一轮巡检结论联动，避免孤立指标");
        return d;
    }
}
