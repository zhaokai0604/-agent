package com.award.log.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 可主动调用的 AI 分析 Skill 目录（提示词模板 + 参数定义）。
 * 与 MCP 工具不同：此处仅做 LLM 推理，不直接执行主机命令。
 */
public final class OpsAnalysisSkillCatalog {

    public record SkillParam(
            String key,
            String label,
            String type,
            boolean required,
            String placeholder,
            List<String> options
    ) {
        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("type", type);
            m.put("required", required);
            m.put("placeholder", placeholder == null ? "" : placeholder);
            if (options != null && !options.isEmpty()) {
                m.put("options", options);
            }
            return m;
        }
    }

    public record SkillDefinition(
            String id,
            String name,
            String category,
            String description,
            List<String> outputSections,
            List<SkillParam> params,
            String systemPrompt,
            PromptBuilder promptBuilder
    ) {
        Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("category", category);
            m.put("description", description);
            m.put("outputSections", outputSections);
            m.put("kind", "analysis");
            List<Map<String, Object>> paramViews = new ArrayList<>();
            for (SkillParam p : params) {
                paramViews.add(p.toView());
            }
            m.put("params", paramViews);
            return m;
        }

        public String buildUserPrompt(Map<String, Object> input) {
            return promptBuilder.build(input == null ? Map.of() : input);
        }
    }

    @FunctionalInterface
    public interface PromptBuilder {
        String build(Map<String, Object> input);
    }

    private static final Map<String, SkillDefinition> SKILLS = new LinkedHashMap<>();

    static {
        register(logAnalysis());
        register(performanceAnalysis());
        register(alertTriage());
        register(opsReport());
        register(incidentRca());
        register(runbookDraft());
        register(securityLogReview());
        register(capacityReview());
        register(postmortemDraft());
        register(sloBurnReview());
    }

    private OpsAnalysisSkillCatalog() {
    }

    public static List<Map<String, Object>> listViews() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (SkillDefinition def : SKILLS.values()) {
            views.add(def.toView());
        }
        return views;
    }

    /**
     * 注入 Chat Agent 系统提示词：列出可主动调用的 AI 分析 Skill（不含 system 提示词全文）。
     */
    public static String summarizeForPrompt(int maxSkills) {
        int limit = maxSkills <= 0 ? SKILLS.size() : Math.min(maxSkills, SKILLS.size());
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (SkillDefinition def : SKILLS.values()) {
            if (i >= limit) {
                break;
            }
            sb.append("- **").append(def.id()).append("**（").append(def.name()).append("，")
                    .append(def.category()).append("）：").append(def.description()).append('\n');
            if (def.params() != null && !def.params().isEmpty()) {
                sb.append("  参数：");
                for (SkillParam p : def.params()) {
                    sb.append(p.key()).append("(").append(p.label()).append(")");
                    if (p.required()) {
                        sb.append("*");
                    }
                    sb.append(" ");
                }
                sb.append('\n');
            }
            i++;
        }
        return sb.toString().trim();
    }

    /** 按用户话术推断可能适用的分析 Skill（供计划层提示，非强制）。 */
    public static List<String> suggestSkillIds(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        String u = userMessage.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        if (u.contains("日志") || u.contains("log") || u.contains("stack") || u.contains("exception")
                || u.contains("error") || u.contains("traceback")) {
            ids.add("log_analysis");
        }
        if (u.contains("告警") || u.contains("alert") || u.contains("on-call") || u.contains("oncall")) {
            ids.add("alert_triage");
        }
        if (u.contains("报告") || u.contains("摘要") || u.contains("运行分析") || u.contains("report")) {
            ids.add("ops_report");
        }
        if (u.contains("根因") || u.contains("rca") || u.contains("故障") || u.contains("incident")) {
            ids.add("incident_rca");
        }
        if (u.contains("runbook") || u.contains("手册") || u.contains("操作步骤")) {
            ids.add("runbook_draft");
        }
        if (u.contains("安全") && (u.contains("日志") || u.contains("登录") || u.contains("攻击"))) {
            ids.add("security_log_review");
        }
        if (u.contains("容量") || u.contains("扩容") || u.contains("capacity")) {
            ids.add("capacity_review");
        }
        if (u.contains("复盘") || u.contains("postmortem")) {
            ids.add("postmortem_draft");
        }
        if (u.contains("slo") || u.contains("错误预算") || u.contains("燃烧率")) {
            ids.add("slo_burn_review");
        }
        if (u.contains("cpu") || u.contains("内存") || u.contains("指标") || u.contains("metric")
                || u.contains("性能")) {
            ids.add("performance_analysis");
        }
        return ids.stream().distinct().toList();
    }

    public static Optional<SkillDefinition> find(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SKILLS.get(skillId.trim().toLowerCase(Locale.ROOT)));
    }

    private static void register(SkillDefinition def) {
        SKILLS.put(def.id(), def);
    }

    private static String text(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String textOrDefault(Map<String, Object> input, String key, String fallback) {
        String v = text(input, key);
        return v.isBlank() ? fallback : v;
    }

    private static SkillDefinition logAnalysis() {
        return new SkillDefinition(
                "log_analysis",
                "日志异常分析",
                "诊断",
                "从日志片段识别异常模式、根因假设与可执行排查步骤（SRE 风格）。",
                List.of("异常模式", "根因假设", "排查步骤", "修复建议"),
                List.of(new SkillParam(
                        "content",
                        "日志内容",
                        "textarea",
                        true,
                        "粘贴 ERROR/WARN 片段或 stack trace…",
                        null)),
                """
                        你是资深 SRE / Linux 运维工程师，熟悉 Google SRE 与 CNCF 可观测性实践。
                        任务：基于用户提供的真实日志片段做诊断，禁止编造未出现的日志行、主机名、IP 或指标数值。
                        输出结构（Markdown）：
                        1. **异常模式**：列出可核验的模式（含关键原文引用，≤3 条）
                        2. **根因假设**：按可能性排序，标注置信度（高/中/低）与依据
                        3. **排查步骤**：可执行的命令或检查点（先只读、后变更）
                        4. **修复建议**：最小变更方案与回滚思路
                        若信息不足，明确列出需补充的日志/指标/时间窗。""",
                input -> "请分析以下日志：\n\n" + text(input, "content"));
    }

    private static SkillDefinition performanceAnalysis() {
        return new SkillDefinition(
                "performance_analysis",
                "性能指标评估",
                "诊断",
                "结合指标名称与当前值，判断健康度并给出阈值与处置建议。",
                List.of("健康评估", "可能原因", "处置建议", "需补充观测"),
                List.of(
                        new SkillParam("metricName", "指标名称", "text", true, "如 cpu_usage、heap_used_pct", null),
                        new SkillParam("metricValue", "当前值", "number", true, "如 92.5", null),
                        new SkillParam("content", "补充上下文（可选）", "textarea", false, "业务高峰时段、最近变更等", null)),
                """
                        你是 APM / 系统性能分析专家，参考 USE（Utilization/Saturation/Errors）与 RED 方法。
                        结合指标名与数值给出运维建议；不要虚构历史曲线或未提供的基线。
                        输出：健康评估（正常/关注/严重）、可能原因、短期处置、长期优化、需补充的观测项。""",
                input -> String.format(
                        Locale.ROOT,
                        "指标名称：%s\n当前值：%.4f\n补充说明：%s\n请评估并给出建议。",
                        textOrDefault(input, "metricName", "unknown"),
                        parseDouble(input.get("metricValue"), 0),
                        textOrDefault(input, "content", "无")));
    }

    private static SkillDefinition alertTriage() {
        return new SkillDefinition(
                "alert_triage",
                "告警分诊",
                "响应",
                "按 ITIL 思路评估告警严重级别、影响面与优先处置步骤。",
                List.of("严重级别", "影响面", "处置优先级", "升级条件"),
                List.of(new SkillParam(
                        "content",
                        "告警内容",
                        "textarea",
                        true,
                        "告警标题、指标、阈值、关联服务等",
                        null)),
                """
                        你是告警响应/on-call 专家，熟悉 P1–P4 分级与 ITIL 事件管理。
                        根据告警文本评估：严重级别、影响范围、是否误报可能、优先处置步骤（先止血后根因）、何时升级/on-call。
                        不要假设未提及的业务 SLA；信息不足时列出需确认项。""",
                input -> "告警内容：\n\n" + text(input, "content"));
    }

    private static SkillDefinition opsReport() {
        return new SkillDefinition(
                "ops_report",
                "运行摘要报告",
                "报告",
                "生成指定时间窗的结构化运维摘要（状态、风险、建议）。",
                List.of("运行概况", "主要风险", "已处理事项", "后续建议"),
                List.of(
                        new SkillParam("period", "时间范围", "select", true, "", List.of("1h", "24h", "7d", "30d")),
                        new SkillParam("content", "已知事实（可选）", "textarea", false, "粘贴巡检结果、告警统计等", null)),
                """
                        你是运维报告撰写助手。基于用户提供的时间范围与已知事实，输出结构化运行摘要。
                        禁止虚构具体 CPU/内存百分比或未提供的告警数量；缺少数据时说明需补充哪些采集项。""",
                input -> "请生成过去 "
                        + textOrDefault(input, "period", "24h")
                        + " 的系统运行分析报告。\n\n已知事实：\n"
                        + textOrDefault(input, "content", "（用户未提供具体指标，请按通用模板并标注待补充项）"));
    }

    private static SkillDefinition incidentRca() {
        return new SkillDefinition(
                "incident_rca",
                "故障根因分析",
                "响应",
                "基于事件描述做时间线梳理、5 Whys 根因分析与复发预防。",
                List.of("时间线", "根因分析", "贡献因子", "预防项"),
                List.of(new SkillParam(
                        "content",
                        "故障描述",
                        "textarea",
                        true,
                        "现象、开始时间、影响范围、已做操作…",
                        null)),
                """
                        你是 SRE 事故复盘专家。基于用户描述做 blameless RCA：
                        1. 时间线（已知节点）
                        2. 5 Whys 或鱼骨式根因链（标注假设 vs 已证实）
                        3. 贡献因子（变更、容量、依赖、监控盲区）
                        4. 预防与检测改进项（可落地）
                        不得编造未提及的变更单号或监控截图。""",
                input -> "故障/事件描述：\n\n" + text(input, "content"));
    }

    private static SkillDefinition runbookDraft() {
        return new SkillDefinition(
                "runbook_draft",
                "Runbook 草稿",
                "自动化",
                "根据场景生成可执行的 Runbook 步骤（含前置检查与回滚）。",
                List.of("适用场景", "前置条件", "执行步骤", "验证与回滚"),
                List.of(
                        new SkillParam("content", "场景描述", "textarea", true, "如：磁盘使用率>90% 且 /var 分区", null),
                        new SkillParam("environment", "环境", "select", false, "", List.of("Linux", "Windows", "Kubernetes", "混合"))),
                """
                        你是运维 Runbook 作者。输出可执行步骤清单：前置检查、只读诊断、变更操作、验证点、回滚方案。
                        涉及 destructive 操作必须标注风险与需人工确认；命令示例需注明适用 OS。""",
                input -> "目标环境："
                        + textOrDefault(input, "environment", "Linux")
                        + "\n场景：\n"
                        + text(input, "content")
                        + "\n请生成 Runbook 草稿。");
    }

    private static SkillDefinition securityLogReview() {
        return new SkillDefinition(
                "security_log_review",
                "安全日志研判",
                "安全",
                "从认证/访问日志中识别可疑行为与处置建议。",
                List.of("可疑行为", "风险等级", "关联分析", "处置建议"),
                List.of(new SkillParam(
                        "content",
                        "安全相关日志",
                        "textarea",
                        true,
                        "登录失败、权限变更、异常 IP 等",
                        null)),
                """
                        你是安全运维（SecOps）分析师，熟悉 MITRE ATT&CK 与常见入侵指标。
                        仅基于提供的日志研判：可疑行为、风险等级、是否需隔离/封禁/升级、进一步取证建议。
                        不要捏造 IP、账号或未出现的攻击链。""",
                input -> "请研判以下安全相关日志：\n\n" + text(input, "content"));
    }

    private static SkillDefinition capacityReview() {
        return new SkillDefinition(
                "capacity_review",
                "容量与扩容建议",
                "规划",
                "根据资源使用描述评估容量风险与扩容/优化路径。",
                List.of("容量风险", "趋势判断", "扩容建议", "成本优化"),
                List.of(new SkillParam(
                        "content",
                        "资源使用描述",
                        "textarea",
                        true,
                        "CPU/内存/磁盘/连接数现状与峰值、增长趋势",
                        null)),
                """
                        你是容量规划工程师。基于描述评估：当前风险、是否接近饱和、垂直/水平扩容建议、缓存/归档/限流等优化手段。
                        缺少历史趋势时明确说明，不要预测具体数字。""",
                input -> "资源使用描述：\n\n" + text(input, "content"));
    }

    private static SkillDefinition postmortemDraft() {
        return new SkillDefinition(
                "postmortem_draft",
                "无责复盘草稿",
                "响应",
                "生成 blameless postmortem 文档骨架与改进项。",
                List.of("摘要", "影响", "根因", "行动项"),
                List.of(new SkillParam(
                        "content",
                        "事故摘要",
                        "textarea",
                        true,
                        "已结束的 incident 要点",
                        null)),
                """
                        你是 SRE postmortem 撰写者。输出无责复盘结构：摘要、影响、时间线、根因、做得好的/待改进、行动项（Owner/优先级占位）。
                        语气客观，不指责个人。""",
                input -> "事故摘要：\n\n" + text(input, "content"));
    }

    private static SkillDefinition sloBurnReview() {
        return new SkillDefinition(
                "slo_burn_review",
                "SLO 燃烧率分析",
                "诊断",
                "根据错误预算/可用性描述，评估燃烧率与响应策略。",
                List.of("SLO 状态", "燃烧率解读", "响应策略", "沟通建议"),
                List.of(
                        new SkillParam("sloTarget", "SLO 目标", "text", false, "如 99.9%", null),
                        new SkillParam("content", "现状描述", "textarea", true, "近期错误率、持续时间、影响请求量", null)),
                """
                        你是 SLO/错误预算专家（Google SRE Workbook 思路）。
                        根据 SLO 目标与现状描述，解读燃烧率快/慢速、是否应冻结发布、是否需 incident 响应；不要编造未提供的 SLI 数值。""",
                input -> "SLO 目标："
                        + textOrDefault(input, "sloTarget", "未指定")
                        + "\n现状：\n"
                        + text(input, "content"));
    }

    private static double parseDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
