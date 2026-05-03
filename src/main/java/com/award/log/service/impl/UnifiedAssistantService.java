package com.award.log.service.impl;

import com.award.log.service.AiLogAlarmService;
import com.award.log.service.StatisticsService;
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
        Map<String, Object> performance = statisticsService.getSystemPerformance(null);
        Map<String, Object> taskStats = statisticsService.getTaskStatusStatistics();
        Map<String, Object> alarmStats = aiLogAlarmService.getAlarmStatistics(1, null, null);
        Map<String, Object> recentAlarms = aiLogAlarmService.getAlarmHistory(1, 5, null, null);
        context.put("agentHostOs", buildAgentHostOsSummary());
        context.put("performance", performance);
        context.put("taskStats", taskStats);
        context.put("alarmStats", alarmStats);
        context.put("recentAlarms", recentAlarms.getOrDefault("list", List.of()));
        context.put("runbooks", buildRunbookSuggestions(performance, alarmStats));
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
                    String content = "";
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        content = resp.getResult().getOutput().getText();
                    }
                    if (content == null) {
                        content = "";
                    }
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
                你是「企业级系统运维智能助手」，面向 Windows 与 Linux（及同类服务器 OS）场景：帮助定位问题、理解根因，在授权与可追溯前提下给出可执行建议；回答须可被用户直接阅读，禁止输出「一坨不换行的长句」。
                
                ## 平台与命令
                - 上下文中的 `agentHostOs`（含 `osFamily`：windows / linux / macos）表示**当前 Agent 后端与 MCP 工具实际运行的节点**。请优先按该字段选用命令、路径与服务名；若用户明确问「另一平台如何做」，给出对照说明并标明「仅供参考 / 需在对应环境执行」。
                - 同一问题尽量同时列出 Windows 与 Linux 的常见做法各一行（若篇幅过长则按 `agentHostOs.osFamily` 展开详述，另一平台用简短列表）。
                - 不要默认用户只在某一种 OS 上工作；避免全文仅出现 bash 或仅出现 PowerShell 而不加前提。
                
                ## 输出版式（必须遵守，使用 Markdown）
                - 使用二级标题 `##` 分大段，三级标题 `###` 分小节；**每个标题前后各空一行**。
                - 列表统一用 `- ` 条目，每条单独一行；步骤用有序列表 `1.` `2.`。
                - 段落之间必须空一行；单行不要塞过多句子。
                - 需要强调用 `**加粗**`，代码/命令用单反引号 `` `like this` `` 或围栏代码块。
                - 不要滥用 emoji；如用，每段最多 1 个，且勿与文字粘在同一行无空格。
                - 不要输出「伪表格」：若需对照表，用标准 Markdown 表头与分隔行；否则用列表代替。
                
                ## 内容要求
                - 先直接回应用户意图，再结合下方「实时系统上下文」JSON 给建议；证据不足时说明缺什么，不得编造指标。
                - 与运维无关的问题：先诚实作答，再酌情给简短运维提示即可。
                - 延时任务：用户可说「30 分钟之后检查磁盘」「一小时后预览清理临时文件」等；清理类默认预览，真实删除须在界面确认且符合安全策略。
                
                ## 以下为实时系统上下文（JSON）
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
