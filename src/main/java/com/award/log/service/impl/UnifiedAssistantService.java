package com.award.log.service.impl;

import com.award.log.service.AiLogAlarmService;
import com.award.log.service.StatisticsService;
import com.award.log.util.AiChatResponseSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class UnifiedAssistantService {

    public record ChatTurn(String role, String content) {}

    private static final int MAX_HISTORY_TURNS = 24;
    private static final int MAX_TURN_CHARS = 2800;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private AiLogAlarmService aiLogAlarmService;

    @Autowired
    private ObjectMapper objectMapper;

    public Map<String, Object> getAssistantContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("agentHostOs", buildAgentHostOsSummary());
        try {
            context.put("performance", statisticsService.getSystemPerformance(null));
        } catch (Exception e) {
            log.warn("助手上下文：性能数据不可用: {}", e.getMessage());
            context.put("performance", Map.of("unavailable", true, "reason", String.valueOf(e.getMessage())));
        }
        try {
            context.put("taskStats", statisticsService.getTaskStatusStatistics());
        } catch (Exception e) {
            log.warn("助手上下文：任务统计不可用: {}", e.getMessage());
            context.put("taskStats", Map.of("unavailable", true));
        }
        Map<String, Object> alarmStats = Map.of();
        try {
            alarmStats = aiLogAlarmService.getAlarmStatistics(1, null, null);
            if (alarmStats == null) {
                alarmStats = Map.of();
            }
            context.put("alarmStats", alarmStats);
        } catch (Exception e) {
            log.warn("助手上下文：告警统计不可用: {}", e.getMessage());
            context.put("alarmStats", Map.of("unavailable", true));
        }
        try {
            Map<String, Object> recentAlarms = aiLogAlarmService.getAlarmHistory(1, 5, null, null);
            Object list = recentAlarms != null ? recentAlarms.getOrDefault("list", List.of()) : List.of();
            context.put("recentAlarms", list != null ? list : List.of());
        } catch (Exception e) {
            log.warn("助手上下文：近期告警不可用: {}", e.getMessage());
            context.put("recentAlarms", List.of());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> perf = (Map<String, Object>) context.getOrDefault("performance", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> alarms = (Map<String, Object>) context.getOrDefault("alarmStats", Map.of());
            context.put("runbooks", buildRunbookSuggestions(perf, alarms));
        } catch (Exception e) {
            log.warn("助手上下文：Runbook 建议生成失败: {}", e.getMessage());
            context.put("runbooks", List.of());
        }
        return context;
    }

    /** 标明采集指标与 MCP 工具实际运行的节点 OS，便于模型区分 Windows / Linux 话术与命令。 */
    private Map<String, String> buildAgentHostOsSummary() {
        Map<String, String> m = new HashMap<>(4);
        m.put("osName", System.getProperty("os.name", ""));
        m.put("osVersion", System.getProperty("os.version", ""));
        m.put("osArch", System.getProperty("os.arch", ""));
        String name = m.get("osName");
        String family = "unknown";
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.contains("win")) {
                family = "windows";
            } else if (lower.contains("linux") || lower.contains("nix")) {
                family = "linux";
            } else if (lower.contains("mac")) {
                family = "macos";
            }
        }
        m.put("osFamily", family);
        return m;
    }

    public Flux<String> chatStream(String userMessage) {
        return chatStream(userMessage, List.of());
    }

    /**
     * 流式对话：system（含实时上下文）+ 可选多轮 history + 当前用户消息。
     */
    public Flux<String> chatStream(String userMessage, List<ChatTurn> history) {
        Map<String, Object> context = getAssistantContext();
        String systemPrompt = buildSystemPrompt(context);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (ChatTurn turn : trimHistory(history)) {
            String role = turn.role() == null ? "" : turn.role().trim();
            String content = turn.content() == null ? "" : turn.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equalsIgnoreCase(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        messages.add(new UserMessage(userMessage));
        Prompt prompt = new Prompt(messages);
        AtomicReference<String> streamAcc = new AtomicReference<>("");
        return chatModel.stream(prompt)
                .map(resp -> {
                    String content = AiChatResponseSupport.textFrom(resp);
                    return toStreamingDelta(streamAcc, content);
                })
                .filter(s -> !s.isEmpty())
                .timeout(Duration.ofSeconds(90))
                .retry(1)
                .onErrorResume(e -> {
                    log.error("统一助手流式回答失败", e);
                    return Flux.just("统一助手暂时不可用: " + e.getMessage());
                });
    }

    private List<ChatTurn> trimHistory(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, history.size() - MAX_HISTORY_TURNS);
        List<ChatTurn> slice = history.subList(start, history.size());
        List<ChatTurn> out = new ArrayList<>(slice.size());
        for (ChatTurn t : slice) {
            String c = t.content() == null ? "" : t.content();
            if (c.length() > MAX_TURN_CHARS) {
                c = c.substring(0, MAX_TURN_CHARS) + "\n...[truncated]";
            }
            out.add(new ChatTurn(t.role(), c));
        }
        return out;
    }

    /**
     * 兼容「每帧为全文」与「每帧为增量」两种流式返回，避免重复拼接导致内容刷屏或雷同感。
     */
    private static String toStreamingDelta(AtomicReference<String> accumulatedFull, String piece) {
        if (piece.isEmpty()) {
            return "";
        }
        String prev = accumulatedFull.get();
        if (prev == null) {
            prev = "";
        }
        if (piece.startsWith(prev)) {
            String delta = piece.substring(prev.length());
            accumulatedFull.set(piece);
            return delta;
        }
        accumulatedFull.set(prev + piece);
        return piece;
    }

    private String buildSystemPrompt(Map<String, Object> context) {
        String ctxJson;
        try {
            ctxJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException e) {
            log.warn("系统上下文序列化为 JSON 失败，使用降级字符串", e);
            ctxJson = String.valueOf(context);
        }
        return """
                ## 角色与边界
                你以 **Principal-level Site Reliability Engineer（首席站点可靠性工程师）** 身份作答：同时具备生产事故指挥（Incident Commander）、容量与性能工程、平台安全基线意识。交付物须 **可审计、可执行、可验证**：区分「观测事实」「推断假设」「建议动作」，禁止将推测陈述为既定结论。
                
                ## 运行环境与指令语义
                - 附加上下文中的 `agentHostOs`（含 `osFamily`）表征 **本助手所绑定 MCP/Agent 进程的实际宿主操作系统**。一切命令、路径、服务单元（systemd / Windows Service）、性能计数器均以该宿主为准。
                - 若用户询问与当前宿主 **异构 OS** 的处置方式：给出 **对照清单**，并显式标注 **「非本节点可执行，仅供参考」**，避免隐含误导。
                - GNU/Linux（含麒麟 Kylin 等发行版）与 Windows Server/桌面族系在工具链上不对等：禁止混用 shell 与 PowerShell 语法而不加前提。
                
                ## 分析与表述准则（必须遵守）
                1. **证据驱动**：仅基于上下文 JSON 与用户问题中可核验的信息推断；缺参时列出 **Minimum Required Evidence**，不得捏造监控数值或日志片段。
                2. **影响面**：在适用场景下简述 **Blast Radius**（受影响租户/服务域/数据面或控制面），未知则写明「影响面待确认」。
                3. **可操作项**：建议动作须含 **验证步骤（Verification）** 与 **回滚/止血（Rollback / Mitigation）** 思路；高危变更提示变更窗口与审批维度。
                4. **风险管理**：涉及删除、权限提升、网络暴露面变更时，引用 **最小权限** 与 **双人复核** 原则（叙述层面即可）。
                
                ## Markdown 版式（硬性）
                - 结构：`##` 章节 → `###` 小节；章节之间 **空一行**。
                - 段落短句化；枚举使用 `- `；有序步骤使用 `1.` `2.`。
                - 命令、路径、指标名、API 使用反引号；多行脚本使用围栏代码块并注明语言。
                - 对照信息优先 **标准 Markdown 表格**；禁用错位对齐的「伪表格」。
                - 表情符号非必要不使用；若使用，密度 ≤ 每 **两节** 一枚。
                
                ## 任务类型提示
                - **闲聊 / 非运维域**：简明作答即可，结尾可加一行「如需针对贵司栈做可执行 Runbook，请提供环境与约束」。
                - **延时运维**：用户可预约巡检或清理预览； destructive 操作默认 **Dry-Run / 预览**，真实变更须经由控制台二次确认与策略白名单。
                
                ## 与本节点绑定的 MCP 工具名（须写确切英文名，与 `/api/mcp` 白名单一致）
                `DiskTool`、`DiskInsightTool`、`DiskAnalyzeTool`、`ProcessTool`、`SystemLoadTool`、`LogAnalysisTool`、`CleanTempTool`、`LogCleanupTool`、`ServiceRestartTool`、`ConfigCheckTool`、`NetworkTool`、`PrivilegeTool`、`OsInsightTool`。
                
                ## 实时上下文附件（JSON，只读）
                """ + ctxJson;
    }

    private List<Map<String, String>> buildRunbookSuggestions(Map<String, Object> performance, Map<String, Object> alarmStats) {
        List<Map<String, String>> runbooks = new ArrayList<>();
        double cpu = asDouble(performance.get("cpuUsage"));
        double memory = asDouble(performance.get("memoryUsage"));
        double network = asDouble(performance.get("networkUsage"));
        double critical = asDouble(alarmStats.get("criticalCount"));

        if (cpu >= 80) {
            runbooks.add(runbook(
                    "CPU高负载排障",
                    "排查占用最高的进程并限流（跨平台）",
                    "Linux: top / htop; ps aux --sort=-%cpu | head — Windows PowerShell: Get-Process | Sort-Object CPU -Descending | Select-Object -First 15"));
        }
        if (memory >= 85) {
            runbooks.add(runbook(
                    "内存压力处置",
                    "定位内存压力与可疑进程（跨平台）",
                    "Linux: free -m; ps aux --sort=-%rss | head — Windows: 任务管理器 → 详细信息；或 Get-CimInstance Win32_OperatingSystem | Select FreePhysicalMemory"));
        }
        if (network >= 75) {
            runbooks.add(runbook(
                    "网络拥塞处置",
                    "检查连接数与监听端口（跨平台）",
                    "Linux: ss -s; netstat -an | wc -l — Windows: netstat -an; Get-NetTCPConnection | Group-Object State"));
        }
        if (critical > 0) {
            runbooks.add(runbook("高危告警响应", "按服务维度执行止血", "先切流/降级，再定位异常实例"));
        }
        if (runbooks.isEmpty()) {
            runbooks.add(runbook(
                    "常规巡检",
                    "当前未达自动处置阈值，建议持续观测（Windows / Linux 通用思路）",
                    "关注 CPU/内存/磁盘与错误率趋势；Linux 可看 vmstat/iostat；Windows 可看性能监视器与事件日志"));
        }
        return runbooks;
    }

    private Map<String, String> runbook(String title, String action, String command) {
        Map<String, String> item = new HashMap<>();
        item.put("title", title);
        item.put("action", action);
        item.put("command", command);
        return item;
    }

    private double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}
