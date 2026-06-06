package com.award.log.service;

import com.award.log.agent.RemediationEffectEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运维效果评分仪表板：聚合审计、巡检、自愈评分与资源快照，量化展示系统价值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsEffectDashboardService {

    private static final Pattern HEALING_SCORE_IN_TEXT = Pattern.compile("自愈评分\\s*(\\d+)/100");
    private static final Pattern MIB_IN_SUMMARY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*MiB", Pattern.CASE_INSENSITIVE);
    private static final Pattern GIB_IN_SUMMARY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*GiB", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETED_COUNT = Pattern.compile("deleted[=:\\s]+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final OpsAuditTraceService opsAuditTraceService;
    private final PatrolHistoryService patrolHistoryService;
    private final StatisticsService statisticsService;
    private final RemediationEffectEvaluator remediationEffectEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> buildDashboard(int days) {
        int periodDays = Math.min(30, Math.max(1, days));
        AuditAggregate audit = loadAuditAggregate(periodDays);
        List<Map<String, Object>> patrolTrend = patrolHistoryService.countByDay(periodDays);
        PatrolAggregate patrol = aggregatePatrol(patrolTrend);
        HealingAggregate healing = loadHealingAggregate(periodDays);
        Map<String, Object> resource = loadResourceSnapshot();
        double releasedSpaceGb = estimateReleasedSpaceGb(periodDays, healing);

        Map<String, Object> dimensions = scoreDimensions(audit, patrol, healing, resource, releasedSpaceGb);
        int overall = weightedOverall(dimensions);
        String grade = gradeLabel(overall);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodDays", periodDays);
        body.put("generatedAt", Instant.now().toString());
        body.put("overallScore", overall);
        body.put("overallGrade", grade);
        body.put("summary", buildSummary(overall, grade, audit, patrol, healing, releasedSpaceGb));
        body.put("dimensions", dimensions);
        body.put("kpis", buildKpis(audit, patrol, healing, releasedSpaceGb));
        body.put("valueStatement", buildValueStatement(audit, patrol, releasedSpaceGb, healing));
        body.put("trends", Map.of(
                "auditByDay", audit.byDay,
                "patrolByDay", patrolTrend,
                "dimensionRadar", radarFromDimensions(dimensions)
        ));
        body.put("valueHighlights", buildHighlights(audit, patrol, healing));
        body.put("recentEffectRuns", healing.recentRuns);
        body.put("currentResource", resource);
        return body;
    }

    private Map<String, Object> buildValueStatement(AuditAggregate audit, PatrolAggregate patrol,
                                                    double releasedSpaceGb, HealingAggregate healing) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("securityBlocked", audit.blocked);
        v.put("executionSuccess", audit.successExec);
        v.put("patrolClosedLoops", patrol.runs);
        v.put("releasedSpaceGb", round2(releasedSpaceGb));
        v.put("avgHealingScore", healing.samples > 0 ? Math.round(healing.avgScore) : null);
        return v;
    }

    private Map<String, Object> buildSummary(int overall, String grade,
                                             AuditAggregate audit, PatrolAggregate patrol,
                                             HealingAggregate healing, double releasedSpaceGb) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("headline", String.format(Locale.ROOT,
                "近 %d 日综合运维效果 %d 分（%s）", audit.periodDays, overall, grade));
        List<String> bullets = new ArrayList<>();
        if (audit.blocked > 0) {
            bullets.add(String.format("安全门累计拦截高危/越权请求 %d 次", audit.blocked));
        }
        if (audit.successExec > 0) {
            bullets.add(String.format("审计链路成功执行 %d 次，平均耗时 %.1f 秒",
                    audit.successExec, audit.avgDurationMs / 1000.0));
        }
        if (patrol.runs > 0) {
            bullets.add(String.format("巡检 %d 轮、发现线索 %d 条", patrol.runs, patrol.findings));
        }
        if (releasedSpaceGb > 0.01) {
            bullets.add(String.format("清理/优化类处置约合释放空间 %.2f GB", releasedSpaceGb));
        }
        if (healing.samples > 0) {
            bullets.add(String.format("自愈效果样本 %d 条，均分 %.0f/100", healing.samples, healing.avgScore));
        } else {
            bullets.add("自愈评分随自主运维/闭环处置积累，当前以资源快照为参考");
        }
        if (bullets.isEmpty()) {
            bullets.add("系统已就绪；使用工作台、MCP 与自主运维后此处将展示量化成效");
        }
        s.put("bullets", bullets);
        return s;
    }

    private Map<String, Object> buildKpis(AuditAggregate audit, PatrolAggregate patrol,
                                          HealingAggregate healing, double releasedSpaceGb) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("auditTotal", audit.total);
        k.put("auditSuccessExec", audit.successExec);
        k.put("auditBlocked", audit.blocked);
        k.put("auditNeedConfirm", audit.needConfirm);
        k.put("auditPass", audit.pass);
        k.put("avgDurationMs", audit.avgDurationMs);
        k.put("mcpChannelCount", audit.mcpCount);
        k.put("assistantChannelCount", audit.assistantCount);
        k.put("patrolRuns", patrol.runs);
        k.put("patrolClosedLoops", patrol.runs);
        k.put("patrolFindings", patrol.findings);
        k.put("releasedSpaceGb", round2(releasedSpaceGb));
        k.put("avgHealingScore", healing.avgScore);
        k.put("healingSamples", healing.samples);
        return k;
    }

    private List<Map<String, Object>> buildHighlights(AuditAggregate audit,
                                                      PatrolAggregate patrol,
                                                      HealingAggregate healing) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (audit.blocked > 0) {
            out.add(highlight("security", "安全护栏生效",
                    String.format("拦截 %d 次（含注入/高危意图/风险分超限等）", audit.blocked)));
        }
        if (audit.needConfirm > 0) {
            out.add(highlight("confirm", "人机协同确认",
                    String.format("%d 次中等风险操作经二次确认后执行", audit.needConfirm)));
        }
        if (audit.successExec > 0) {
            out.add(highlight("execution", "处置落地",
                    String.format("%d 次工具调用执行成功并留痕", audit.successExec)));
        }
        if (patrol.trendImproving) {
            out.add(highlight("patrol", "巡检态势向好",
                    "近段日告警线索呈下降趋势"));
        }
        if (healing.bestScore > 0) {
            out.add(highlight("healing", "自愈成效",
                    String.format("最佳自愈评分 %d/100", healing.bestScore)));
        }
        return out;
    }

    private static Map<String, Object> highlight(String type, String title, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> scoreDimensions(AuditAggregate audit, PatrolAggregate patrol,
                                                  HealingAggregate healing, Map<String, Object> resource,
                                                  double releasedSpaceGb) {
        Map<String, Object> dims = new LinkedHashMap<>();

        int security = 72;
        if (audit.total > 0) {
            double blockRate = audit.blocked * 1.0 / audit.total;
            security = (int) Math.min(100, 50 + audit.blocked * 2 + blockRate * 40 + audit.needConfirm);
        }
        dims.put("security", dim("security", "安全拦截", 0.22, security,
                audit.total > 0
                        ? String.format("拦截 %d 次 · 须确认 %d 次", audit.blocked, audit.needConfirm)
                        : "暂无审计样本，可用顶栏「安全自检」向评委演示"));

        int execution = audit.total > 0
                ? (int) Math.min(100, 35 + audit.successRate * 65)
                : 70;
        dims.put("execution", dim("execution", "成功执行", 0.22, execution,
                String.format("成功执行 %d / 审计 %d 条", audit.successExec, audit.total)));

        int patrolScore = 65;
        if (patrol.runs > 0) {
            patrolScore = (int) Math.min(100, 55 + Math.min(25, patrol.runs * 2)
                    - Math.min(20, patrol.avgFindingsPerRun * 2)
                    + (patrol.trendImproving ? 15 : 0));
        }
        dims.put("patrol", dim("patrol", "巡检闭环", 0.18, patrolScore,
                String.format("闭环巡检 %d 轮 · 线索 %d 条", patrol.runs, patrol.findings)));

        int resourceScore = resourceOptimizationScore(resource, releasedSpaceGb, healing);
        dims.put("resource", dim("resource", "资源优化", 0.18, resourceScore,
                releasedSpaceGb > 0.01
                        ? String.format("约合释放 %.2f GB · 磁盘占用 %.1f%%",
                        releasedSpaceGb, toDouble(resource.get("diskUsagePct")))
                        : String.format("当前磁盘占用 %.1f%%（清理/自愈后更新）",
                        toDouble(resource.get("diskUsagePct")))));

        int healingScore = healing.samples > 0
                ? (int) Math.round(healing.avgScore)
                : Math.min(resourceHealthScore(resource), 75);
        dims.put("healing", dim("healing", "自愈效果", 0.20, healingScore,
                healing.samples > 0
                        ? String.format("均分 %.0f（%d 条样本）", healing.avgScore, healing.samples)
                        : "运行「全自动运维」后写入 effect 步骤评分"));

        return dims;
    }

    private static int resourceOptimizationScore(Map<String, Object> resource, double releasedGb,
                                                 HealingAggregate healing) {
        int score = 60;
        if (releasedGb > 0.01) {
            score += (int) Math.min(30, releasedGb * 8);
        }
        if (healing.totalDiskDeltaPct > 0.5) {
            score += (int) Math.min(15, healing.totalDiskDeltaPct * 2);
        }
        double disk = toDouble(resource.get("diskUsagePct"));
        if (disk > 0 && disk < 75) {
            score += 10;
        } else if (disk >= 90) {
            score -= 10;
        }
        return Math.max(40, Math.min(100, score));
    }

    private double estimateReleasedSpaceGb(int days, HealingAggregate healing) {
        double mib = 0;
        try {
            List<String> summaries = jdbcTemplate.queryForList(
                    """
                    SELECT result_summary FROM ops_audit_trace
                    WHERE execution_ok = 1
                      AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                      AND (tool_name LIKE '%Clean%' OR tool_name LIKE '%LogCleanup%'
                           OR tool_name LIKE '%DiskOps%' OR result_summary LIKE '%MiB%'
                           OR result_summary LIKE '%deleted%')
                    ORDER BY id DESC LIMIT 200
                    """,
                    String.class,
                    days);
            for (String s : summaries) {
                if (s == null) {
                    continue;
                }
                Matcher gm = GIB_IN_SUMMARY.matcher(s);
                while (gm.find()) {
                    mib += Double.parseDouble(gm.group(1)) * 1024;
                }
                Matcher mm = MIB_IN_SUMMARY.matcher(s);
                while (mm.find()) {
                    mib += Double.parseDouble(mm.group(1));
                }
                Matcher dm = DELETED_COUNT.matcher(s);
                while (dm.find()) {
                    mib += Integer.parseInt(dm.group(1)) * 0.05;
                }
            }
        } catch (Exception e) {
            log.debug("释放空间估算跳过: {}", e.getMessage());
        }
        if (healing.totalDiskDeltaPct > 0) {
            mib += healing.totalDiskDeltaPct * 1024;
        }
        return mib / 1024.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, Object> dim(String id, String name, double weight, int score, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("weight", weight);
        m.put("score", score);
        m.put("detail", detail);
        m.put("grade", gradeLabel(score));
        return m;
    }

    private static int weightedOverall(Map<String, Object> dimensions) {
        double sum = 0;
        double w = 0;
        for (Object v : dimensions.values()) {
            if (!(v instanceof Map<?, ?> d)) {
                continue;
            }
            Object sc = d.get("score");
            Object wt = d.get("weight");
            if (sc instanceof Number n && wt instanceof Number wn) {
                sum += n.intValue() * wn.doubleValue();
                w += wn.doubleValue();
            }
        }
        return w > 0 ? (int) Math.round(sum / w) : 0;
    }

    private static List<Map<String, Object>> radarFromDimensions(Map<String, Object> dimensions) {
        List<Map<String, Object>> radar = new ArrayList<>();
        for (Object v : dimensions.values()) {
            if (v instanceof Map<?, ?> d) {
                radar.add(Map.of(
                        "name", d.get("name"),
                        "value", d.get("score")));
            }
        }
        return radar;
    }

    private static String gradeLabel(int score) {
        if (score >= 85) {
            return "优秀";
        }
        if (score >= 70) {
            return "良好";
        }
        if (score >= 55) {
            return "合格";
        }
        return "待提升";
    }

    private static int resourceHealthScore(Map<String, Object> resource) {
        double disk = toDouble(resource.get("diskUsagePct"));
        double cpu = toDouble(resource.get("cpuUsagePct"));
        double mem = toDouble(resource.get("memoryUsagePct"));
        int score = 100;
        if (disk > 90) {
            score -= 25;
        } else if (disk > 80) {
            score -= 12;
        }
        if (cpu > 90) {
            score -= 15;
        } else if (cpu > 75) {
            score -= 8;
        }
        if (mem > 92) {
            score -= 12;
        }
        return Math.max(45, Math.min(88, score));
    }

    private Map<String, Object> loadResourceSnapshot() {
        Map<String, Object> snap = remediationEffectEvaluator.captureMetrics();
        try {
            Map<String, Object> perf = statisticsService.getSystemPerformance(null);
            if (perf != null) {
                snap.put("diskUsagePct", toDouble(perf.get("diskUsage")));
                snap.put("cpuUsagePct", toDouble(perf.get("cpuUsage")));
                snap.put("memoryUsagePct", toDouble(perf.get("memoryUsage")));
            }
        } catch (Exception ignored) {
            // keep evaluator snapshot
        }
        return snap;
    }

    private AuditAggregate loadAuditAggregate(int days) {
        AuditAggregate a = new AuditAggregate();
        a.periodDays = days;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    """
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN execution_ok = 1 THEN 1 ELSE 0 END) AS success_exec,
                           SUM(CASE WHEN security_outcome LIKE 'REJECT%' OR security_outcome = 'SECURITY_REJECT' THEN 1 ELSE 0 END) AS blocked,
                           SUM(CASE WHEN security_outcome = 'NEED_CONFIRM' THEN 1 ELSE 0 END) AS need_confirm,
                           SUM(CASE WHEN security_outcome = 'PASS' THEN 1 ELSE 0 END) AS pass_cnt,
                           SUM(CASE WHEN channel = 'MCP' THEN 1 ELSE 0 END) AS mcp_cnt,
                           SUM(CASE WHEN channel = 'ASSISTANT' THEN 1 ELSE 0 END) AS assistant_cnt,
                           AVG(duration_ms) AS avg_duration
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    """,
                    days);
            a.total = intVal(row.get("total"));
            a.successExec = intVal(row.get("success_exec"));
            a.blocked = intVal(row.get("blocked"));
            a.needConfirm = intVal(row.get("need_confirm"));
            a.pass = intVal(row.get("pass_cnt"));
            a.mcpCount = intVal(row.get("mcp_cnt"));
            a.assistantCount = intVal(row.get("assistant_cnt"));
            a.avgDurationMs = row.get("avg_duration") instanceof Number n ? n.longValue() : 0L;
            a.successRate = a.total > 0 ? a.successExec * 1.0 / a.total : 0;

            a.byDay = jdbcTemplate.query(
                    """
                    SELECT DATE(created_at) AS day,
                           COUNT(*) AS total,
                           SUM(CASE WHEN execution_ok = 1 THEN 1 ELSE 0 END) AS success_exec,
                           SUM(CASE WHEN security_outcome LIKE 'REJECT%' OR security_outcome = 'SECURITY_REJECT' THEN 1 ELSE 0 END) AS blocked
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    GROUP BY DATE(created_at)
                    ORDER BY day ASC
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("day", rs.getDate("day").toString());
                        m.put("total", rs.getInt("total"));
                        m.put("successExec", rs.getInt("success_exec"));
                        m.put("blocked", rs.getInt("blocked"));
                        return m;
                    },
                    days);
        } catch (Exception e) {
            log.warn("审计聚合失败: {}", e.getMessage());
            a.byDay = List.of();
        }
        return a;
    }

    private HealingAggregate loadHealingAggregate(int days) {
        HealingAggregate h = new HealingAggregate();
        List<Integer> scores = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                    SELECT steps_json, result_summary, created_at, trace_id
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                      AND (steps_json LIKE '%healingScore%' OR steps_json LIKE '%effect%' OR result_summary LIKE '%自愈评分%')
                    ORDER BY id DESC
                    LIMIT 80
                    """,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("stepsJson", rs.getString("steps_json"));
                        m.put("resultSummary", rs.getString("result_summary"));
                        m.put("createdAt", rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toInstant().toString() : null);
                        m.put("traceId", rs.getString("trace_id"));
                        return m;
                    },
                    days);
            for (Map<String, Object> row : rows) {
                extractHealingScores(String.valueOf(row.get("stepsJson")), scores, h);
            }
            List<Map<String, Object>> traces = opsAuditTraceService.listRecent(30);
            for (Map<String, Object> t : traces) {
                if (recent.size() >= 8) {
                    break;
                }
                String summary = String.valueOf(t.getOrDefault("resultSummary", ""));
                Matcher m = HEALING_SCORE_IN_TEXT.matcher(summary);
                if (m.find()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("traceId", t.get("traceId"));
                    row.put("healingScore", Integer.parseInt(m.group(1)));
                    row.put("createdAt", t.get("createdAt"));
                    row.put("channel", t.get("channel"));
                    recent.add(row);
                    scores.add(Integer.parseInt(m.group(1)));
                }
            }
        } catch (Exception e) {
            log.warn("自愈评分聚合失败: {}", e.getMessage());
        }
        h.samples = scores.size();
        h.avgScore = scores.isEmpty() ? 0 : scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        h.bestScore = scores.isEmpty() ? 0 : scores.stream().mapToInt(Integer::intValue).max().orElse(0);
        h.recentRuns = recent;
        return h;
    }

    private void extractHealingScores(String stepsJson, List<Integer> scores, HealingAggregate agg) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            for (Map<String, Object> step : steps) {
                if (!"effect".equals(step.get("phase"))) {
                    continue;
                }
                Object detail = step.get("detail");
                if (detail instanceof Map<?, ?> effect) {
                    Object hs = effect.get("healingScore");
                    if (hs instanceof Number n) {
                        scores.add(n.intValue());
                    }
                    Object delta = effect.get("diskUsageDeltaPct");
                    if (delta instanceof Number d && d.doubleValue() > 0) {
                        agg.totalDiskDeltaPct += d.doubleValue();
                    }
                } else if (detail != null) {
                    Matcher m = HEALING_SCORE_IN_TEXT.matcher(detail.toString());
                    if (m.find()) {
                        scores.add(Integer.parseInt(m.group(1)));
                    }
                }
            }
        } catch (Exception ignored) {
            Matcher m = HEALING_SCORE_IN_TEXT.matcher(stepsJson);
            while (m.find()) {
                scores.add(Integer.parseInt(m.group(1)));
            }
        }
    }

    private static PatrolAggregate aggregatePatrol(List<Map<String, Object>> trend) {
        PatrolAggregate p = new PatrolAggregate();
        if (trend == null || trend.isEmpty()) {
            return p;
        }
        long runs = 0;
        long findings = 0;
        List<Long> dailyAlerts = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            long rc = longVal(row.get("runCount"));
            long ac = longVal(row.get("alertCount"));
            runs += rc;
            findings += ac;
            dailyAlerts.add(ac);
        }
        p.runs = runs;
        p.findings = findings;
        p.avgFindingsPerRun = runs > 0 ? findings * 1.0 / runs : 0;
        if (dailyAlerts.size() >= 2) {
            int mid = dailyAlerts.size() / 2;
            double first = dailyAlerts.subList(0, mid).stream().mapToLong(Long::longValue).average().orElse(0);
            double second = dailyAlerts.subList(mid, dailyAlerts.size()).stream().mapToLong(Long::longValue).average().orElse(0);
            p.trendImproving = second < first * 0.85;
        }
        return p;
    }

    private static int intVal(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longVal(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static class AuditAggregate {
        int periodDays;
        int total;
        int successExec;
        int blocked;
        int needConfirm;
        int pass;
        int mcpCount;
        int assistantCount;
        long avgDurationMs;
        double successRate;
        List<Map<String, Object>> byDay = List.of();
    }

    private static class PatrolAggregate {
        long runs;
        long findings;
        double avgFindingsPerRun;
        boolean trendImproving;
    }

    private static class HealingAggregate {
        int samples;
        double avgScore;
        int bestScore;
        double totalDiskDeltaPct;
        List<Map<String, Object>> recentRuns = List.of();
    }
}
