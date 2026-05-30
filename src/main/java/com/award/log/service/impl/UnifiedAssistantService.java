package com.award.log.service.impl;

import com.award.log.agent.AssistantAuditRecorder;
import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.agent.MultiAgentContextBuilder;
import com.award.log.mcp.McpToolRegistry;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.HighRiskCommandDetector;
import com.award.log.security.IntentRiskFilter;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.OpsSecurityRejectedException;
import com.award.log.security.PromptInjectionGuard;
import com.award.log.security.ReadOnlySurfaceDenylist;
import com.award.log.security.RiskLevel;
import com.award.log.security.ToolSurfaceResolver;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.KnowledgeBaseService;
import com.award.log.service.OpsOpenIncidentService;
import com.award.log.service.StatisticsService;
import com.award.log.util.AiChatResponseSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class UnifiedAssistantService {

    public record ChatTurn(String role, String content) {}

    private static final int MAX_HISTORY_TURNS = 24;
    private static final int MAX_TURN_CHARS = 2800;
    /** 首帧 SSE 元数据前缀，前端解析后不入正文 */
    static final String ASSISTANT_META_PREFIX = "ASSISTANT_META:";

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private AiLogAlarmService aiLogAlarmService;

    @Autowired
    private MultiAgentContextBuilder multiAgentContextBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntentRiskFilter intentRiskFilter;

    @Autowired
    private ToolSurfaceResolver toolSurfaceResolver;

    @Autowired
    private AgenticRiskScoreEngine agenticRiskScoreEngine;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private McpToolRegistry mcpToolRegistry;

    @Autowired
    private PromptInjectionGuard promptInjectionGuard;

    @Autowired
    private HighRiskCommandDetector highRiskCommandDetector;

    @Autowired
    private ReadOnlySurfaceDenylist readOnlySurfaceDenylist;

    @Autowired(required = false)
    private OpsRuntimeService opsRuntimeService;

    @Autowired
    private OpsIntentRouter opsIntentRouter;

    @Autowired
    private AssistantAuditRecorder assistantAuditRecorder;

    @Autowired
    private OpsOpenIncidentService opsOpenIncidentService;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    @Value("${knowledge.search-top-k:5}")
    private int knowledgeSearchTopK;

    @Value("${agent.assistant.use-tool-agent-default:true}")
    private boolean defaultUseToolAgent;

    @Value("${agent.assistant.orchestrator.enabled:true}")
    private boolean orchestratorEnabled;

    @Value("${agent.assistant.tool-agent-stream-chunk-chars:900}")
    private int toolAgentStreamChunkChars;

    public Map<String, Object> getAssistantContext() {
        return getAssistantContext(null);
    }

    public Map<String, Object> getAssistantContext(String userQuery) {
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
        try {
            context.put("agentMultiTeam", multiAgentContextBuilder.build(userQuery));
        } catch (Exception e) {
            log.warn("助手上下文：多 Agent 摘要不可用: {}", e.getMessage());
            context.put("agentMultiTeam", Map.of("unavailable", true));
        }
        if (userQuery != null && !userQuery.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> diagnosis = (Map<String, Object>) ((Map<?, ?>) context
                        .getOrDefault("agentMultiTeam", Map.of())).get("diagnosisAgent");
                if (diagnosis != null && diagnosis.get("similarHistoricalCases") instanceof List<?> hits) {
                    context.put("knowledgeRagHits", hits);
                }
            } catch (Exception ignored) {
                // optional mirror for prompt readability
            }
        }
        try {
            context.put("openIncident", opsOpenIncidentService.buildOpenIncidentContext());
        } catch (Exception e) {
            log.warn("助手上下文：openIncident 不可用: {}", e.getMessage());
            context.put("openIncident", Map.of("hasOpenIncident", false));
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

    private void augmentContextWithSessionSecurity(String userMessage, Map<String, Object> context) {
        try {
            RiskLevel rl = intentRiskFilter.evaluate(userMessage);
            McpToolSurface surf = toolSurfaceResolver.resolve(userMessage, rl);
            AgenticRiskScoreEngine.ScoreResult utter = agenticRiskScoreEngine.scoreNaturalLanguageUtterance(userMessage);
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("intentRisk", rl.name());
            sec.put("toolSurface", surf.name());
            Map<String, Object> utterMap = new LinkedHashMap<>();
            utterMap.put("total", utter.total());
            utterMap.put("dimensions", utter.dimensions());
            utterMap.put("explanation", utter.explanation());
            sec.put("utteranceRiskScore", utterMap);
            if (surf == McpToolSurface.READ_ONLY) {
                sec.put("toolingHint",
                        "本会话为只读工具面：答复中仅规划观测/分析类步骤；勿假定可自动执行临时清理、日志删除、服务重启或提权探测。");
            } else {
                sec.put("toolingHint",
                        "本会话为完整工具面：在遵守 cleanupAgentPolicy 与 OpsPathPolicy 前提下，可通过本节点 MCP 调用 CleanTempTool、LogCleanupTool、ServiceRestartTool 等写类工具；"
                                + "须在答复中说明操作范围、Dry-Run/预览与回滚建议，并避免超出白名单路径。");
            }
            context.put("sessionSecurityPolicy", sec);
        } catch (Exception e) {
            log.debug("会话安全策略附加失败: {}", e.getMessage());
        }
    }

    public Flux<String> chatStream(String userMessage) {
        return chatStream(userMessage, List.of());
    }

    /**
     * 流式对话：system（含实时上下文）+ 可选多轮 history + 当前用户消息。
     * 默认是否走「工具增强」见 {@code agent.assistant.use-tool-agent-default}；单次请求可由 Controller 覆盖。
     */
    public Flux<String> chatStream(String userMessage, List<ChatTurn> history) {
        return chatStream(userMessage, history, defaultUseToolAgent);
    }

    /**
     * @param useToolAgent true：与 /api/chat 的 AI Agent 类似，经 ChatClient 多步调用本机 MCP 工具后再总结（阻塞 call，按块模拟流式）；
     *                     false：仅 ChatModel 流式，不执行工具。
     */
    public Flux<String> chatStream(String userMessage, List<ChatTurn> history, boolean useToolAgent) {
        return chatStream(userMessage, history, useToolAgent, false);
    }

    /**
     * @param confirmRemediation 用户已在对话中确认落地写操作（与「确认执行」话术等价）
     */
    public Flux<String> chatStream(String userMessage, List<ChatTurn> history, boolean useToolAgent,
                                   boolean confirmRemediation) {
        String safe = McpInvocationSecurityGate.enforceChatMessageLimit(
                userMessage == null ? "" : userMessage.trim(), 32_000);
        if (useToolAgent && chatClientBuilder != null) {
            return chatStreamWithToolAgent(safe, history, confirmRemediation);
        }
        return chatStreamPlain(safe, history);
    }

    private Flux<String> chatStreamPlain(String userMessage, List<ChatTurn> history) {
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userMessage)) {
            return Flux.just("检测到提示注入特征，已拦截本轮请求。请用自然语言描述运维问题，勿嵌入系统指令。");
        }
        if (intentRiskFilter != null && intentRiskFilter.evaluate(userMessage) == RiskLevel.HIGH) {
            return Flux.just("检测到高风险意图，已暂停纯文本回答。请缩小操作范围或通过工具箱在确认后执行。");
        }
        Map<String, Object> context = getAssistantContext(userMessage);
        augmentContextWithSessionSecurity(userMessage, context);
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
        Flux<String> body = chatModel.stream(prompt)
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
        return prependAssistantMeta(body, userMessage);
    }

    Map<String, Object> buildChatMeta(String userMessage) {
        return buildChatMeta(userMessage, Map.of());
    }

    Map<String, Object> buildChatMeta(String userMessage, Map<String, Object> extras) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "assistant-meta");
        meta.put("ragHits", List.of());
        meta.put("writeConfirmed", false);
        if (extras != null) {
            meta.putAll(extras);
        }
        if (userMessage == null || userMessage.isBlank()) {
            return meta;
        }
        if (knowledgeBaseService != null) {
            try {
                meta.put("ragHits", knowledgeBaseService.search(userMessage, knowledgeSearchTopK));
            } catch (Exception e) {
                log.debug("对话元数据：知识库检索跳过 {}", e.getMessage());
            }
        }
        return meta;
    }

    private Flux<String> prependAssistantMeta(Flux<String> body, String userMessage) {
        return prependAssistantMeta(body, userMessage, Map.of());
    }

    private Flux<String> prependAssistantMeta(Flux<String> body, String userMessage, Map<String, Object> extras) {
        try {
            String json = objectMapper.writeValueAsString(buildChatMeta(userMessage, extras));
            return Flux.concat(Flux.just(ASSISTANT_META_PREFIX + json), body);
        } catch (JsonProcessingException e) {
            log.debug("对话元数据序列化失败: {}", e.getMessage());
            return body;
        }
    }

    /**
     * Spring AI 在「工具多步」场景多为阻塞 {@code call()}，此处放到 boundedElastic 并在完成后分块推送，兼顾 SSE 体验与本机真实运维能力。
     */
    private Flux<String> chatStreamWithToolAgent(String userMessage, List<ChatTurn> history,
                                                 boolean confirmRemediation) {
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userMessage)) {
            return Flux.just("检测到提示注入特征，已拦截本轮 **自动工具** 链路。请用自然语言直接描述运维目标。");
        }
        if (highRiskCommandDetector != null && highRiskCommandDetector.isHighRiskCommand(userMessage)) {
            return Flux.just("检测到高危命令表述，已暂停自动工具调用。请改为观测/分析类需求，或通过 MCP 控制台在确认后执行。");
        }
        RiskLevel rl = intentRiskFilter.evaluate(userMessage);
        if (rl == RiskLevel.HIGH) {
            return Flux.just("检测到高风险意图，与对话入口风控一致：已暂停 **自动工具多步** 。请拆分需求、降低操作范围，或联系管理员走审批与 MCP 确认执行。");
        }
        if (orchestratorEnabled && opsRuntimeService != null && opsRuntimeService.shouldOrchestrate(userMessage)) {
            McpToolSurface surface = toolSurfaceResolver.resolve(userMessage, rl);
            String traceId = UUID.randomUUID().toString();
            boolean allowWrite = confirmRemediation || opsIntentRouter.forceRemediate(userMessage);
            OpsSecurityContext.openChatAgent(traceId, userMessage, surface, allowWrite);
            try {
                Map<String, Object> metaExtras = new LinkedHashMap<>();
                metaExtras.put("writeConfirmed", allowWrite);
                Flux<String> body = Flux.concat(
                        Flux.just("> 启动 **自主运维**：采集本机状态 → 诊断 → 生成计划 → "
                                + (allowWrite ? "**执行处置**" : "预览处置") + " → 验证\n\n"),
                        Mono.fromCallable(() -> {
                            String md = opsRuntimeService.run(userMessage, surface, rl).markdown();
                            return appendChatAntiHallucinationFooter(md, allowWrite);
                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .timeout(Duration.ofMinutes(5))
                                .flatMapMany(this::chunkTextForSse)
                ).onErrorResume(e -> {
                    log.error("运维闭环编排失败", e);
                    return Flux.just("运维闭环编排失败: " + e.getMessage());
                });
                return prependAssistantMeta(body, userMessage, metaExtras);
            } finally {
                OpsSecurityContext.clear();
            }
        }
        boolean allowWrite = confirmRemediation || opsIntentRouter.forceRemediate(userMessage);
        Map<String, Object> metaExtras = Map.of("writeConfirmed", allowWrite);
        Flux<String> body = Flux.concat(
                Flux.just("> 正在调用本机运维工具采集与分析，请稍候…\n\n"),
                Mono.fromCallable(() -> runBlockingToolAugmentedCompletion(userMessage, history, confirmRemediation))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(Duration.ofMinutes(4))
                        .flatMapMany(this::chunkTextForSse)
        ).onErrorResume(e -> {
            log.error("统一助手工具增强失败", e);
            Throwable c = e;
            while (c != null) {
                if (c instanceof OpsSecurityRejectedException ore) {
                    return Flux.just("工具安全门拦截: " + ore.getMessage());
                }
                c = c.getCause();
            }
            if (e instanceof java.util.concurrent.TimeoutException) {
                return Flux.just("工具增强阶段超时（4min），请缩小日志范围或稍后重试。");
            }
            return Flux.just("统一助手（工具增强）暂时不可用: " + e.getMessage());
        });
        return prependAssistantMeta(body, userMessage, metaExtras);
    }

    private String runBlockingToolAugmentedCompletion(String userMessage, List<ChatTurn> history,
                                                    boolean confirmRemediation) {
        Map<String, Object> context = getAssistantContext(userMessage);
        augmentContextWithSessionSecurity(userMessage, context);
        context.put("assistantToolLoop", Boolean.TRUE);
        boolean allowWrite = confirmRemediation || opsIntentRouter.forceRemediate(userMessage);
        context.put("userConfirmedWrite", allowWrite);
        McpToolSurface surface = parseToolSurface(context);
        String traceId = UUID.randomUUID().toString();
        long t0 = System.currentTimeMillis();
        OpsSecurityContext.openChatAgent(traceId, userMessage, surface, allowWrite);
        try {
            ChatClient client = chatClientBuilder.build();
            String systemPrompt = buildSystemPrompt(context);
            String bundledUser = bundleHistoryAndUser(history, userMessage);
            String content = client.prompt()
                    .system(systemPrompt)
                    .user(bundledUser)
                    .toolCallbacks(mcpToolRegistry.getToolCallbacksForChatAgent(surface, readOnlySurfaceDenylist))
                    .call()
                    .content();
            List<Map<String, Object>> steps = assistantAuditRecorder.newSteps();
            assistantAuditRecorder.addCot(steps, 1, "接收", userMessage);
            assistantAuditRecorder.addCot(steps, 2, "感知", "会话上下文已注入 getAssistantContext");
            assistantAuditRecorder.addCot(steps, 3, "推理", "ChatClient 多步 MCP 工具调用");
            assistantAuditRecorder.addCot(steps, 4, "校验", "toolSurface=" + surface.name());
            assistantAuditRecorder.addStep(steps, "result", truncateForAudit(content, 600));
            assistantAuditRecorder.record(
                    traceId,
                    userMessage,
                    parseIntentRisk(context).name(),
                    "TOOL_AGENT",
                    "ChatClient.tools",
                    true,
                    truncateForAudit(content, 400),
                    steps,
                    System.currentTimeMillis() - t0
            );
            if (content != null && content.contains("traceId:")) {
                content = content.replaceAll("(?m)^>\\s*\\*\\*traceId:\\*\\*\\s*`[^`]+`\\s*\\n\\n?", "");
            }
            content = appendChatAntiHallucinationFooter(content, allowWrite);
            return content;
        } finally {
            OpsSecurityContext.clear();
        }
    }

    private RiskLevel parseIntentRisk(Map<String, Object> context) {
        Object raw = context.get("sessionSecurityPolicy");
        if (raw instanceof Map<?, ?> m) {
            Object ir = m.get("intentRisk");
            if (ir != null) {
                try {
                    return RiskLevel.valueOf(String.valueOf(ir).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return RiskLevel.MEDIUM;
    }

    private static String truncateForAudit(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private McpToolSurface parseToolSurface(Map<String, Object> context) {
        Object raw = context.get("sessionSecurityPolicy");
        if (!(raw instanceof Map<?, ?> m)) {
            return McpToolSurface.FULL;
        }
        Object ts = m.get("toolSurface");
        if (ts == null) {
            return McpToolSurface.FULL;
        }
        try {
            return McpToolSurface.valueOf(String.valueOf(ts).trim());
        } catch (Exception e) {
            log.debug("解析 toolSurface 失败，使用 FULL: {}", ts);
            return McpToolSurface.FULL;
        }
    }

    private String bundleHistoryAndUser(List<ChatTurn> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        for (ChatTurn t : trimHistory(history)) {
            String r = t.role() == null ? "" : t.role().trim();
            String c = t.content() == null ? "" : t.content().trim();
            if (c.isEmpty()) {
                continue;
            }
            sb.append("[").append(r).append("]\n").append(c).append("\n\n");
        }
        sb.append("[user]\n").append(userMessage);
        return sb.toString();
    }

    private Flux<String> chunkTextForSse(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        int step = Math.max(200, toolAgentStreamChunkChars);
        if (text.length() <= step) {
            return Flux.just(text);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += step) {
            parts.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return Flux.fromIterable(parts);
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
                
                ## 对用户回复（硬性）
                - 使用 **简体中文** 自然语言，结构清晰；禁止大段粘贴原始 JSON、`ToolResult` 或 `{"success":...}` 类机器输出。
                - 工具或上下文已返回结构化数据时：提炼为 **结论、关键指标、建议动作**；需要细节时用 Markdown 列表或表格，勿整段 dump。
                - 仅在用户明确要求「原始 JSON / 调试输出」时方可附 fenced JSON。
                
                ## 任务类型提示
                - **闲聊 / 非运维域**：简明作答即可，结尾可加一行「如需针对贵司栈做可执行 Runbook，请提供环境与约束」。
                - **延时运维**：用户可预约巡检或清理预览； destructive 操作默认 **Dry-Run / 预览**，真实变更须经由控制台二次确认与策略白名单。
                
                ## 多 Agent 协作（逻辑角色，JSON 字段 `agentMultiTeam`）
                - `perceptionAgent`：只读环境快照；`diagnosisAgent`：告警/异常/相似案例（`similarHistoricalCases` 来自知识库 RAG）；`cleanupAgentPolicy`：写操作边界；`orchestratorAgent`：你如何汇总与分派。
                - 引用知识库片段时须标注 **来源标题**（`title` 字段），不得将案例细节改写成未检索到的「新事实」。
                - 回答时可用小节标题区分「感知事实 / 诊断推断 / 处置建议」；**仅在** `sessionSecurityPolicy.toolSurface=FULL` 且工具调用已成功返回时，可陈述已发生的写操作事实；只读面或未实际调用工具时勿虚构已执行变更。
                
                ## 当前用户轮次安全策略（JSON `sessionSecurityPolicy`）
                - `toolSurface=READ_ONLY`：仅观测/分析类 MCP 能力适用；禁止在答复中假定已执行或可直接调用配置项 `agent.security.read-only-extra-denied-tool-beans` 所列工具（默认含 CleanTemp / LogCleanup / ServiceRestart / Privilege）。
                - `toolSurface=FULL`：允许在路径白名单与审计前提下规划并调用写类 MCP（临时清理、日志裁剪、受控服务重启等）；仍须拒绝明显破坏性行为，并优先 Dry-Run / 预览。
                - 结合 `intentRisk` 与 `utteranceRiskScore` 调整措辞激进程度；高风险时优先给止血建议与人工升级路径。
                
                ## 与本节点绑定的 MCP 工具名（须写确切英文名，与 `/api/mcp` 白名单一致）
                **一键自主运维（优先）**：`AutonomousOpsTool`（`runAutonomousOps`）— 并行融合全部观测工具并策略内自动修复。
                分项工具：`DiskTool`、`DiskInsightTool`、`DiskAnalyzeTool`、`ProcessTool`、`SystemLoadTool`、`LogAnalysisTool`、
                `CleanTempTool`、`LogCleanupTool`、`ServiceRestartTool`、`ConfigCheckTool`、`NetworkTool`、`PrivilegeTool`、`OsInsightTool`、
                `PortHealthTool`、`DockerTool`、`CronJobTool`、`FirewallTool`、`SslCertTool`、`SystemdTool`、`ConfigDriftTool`。
                用户要求「自动运维 / 全自动 / 察觉并解决问题」时，**必须先调用 `runAutonomousOps`**，勿零散调用十余个工具代替。
                """ + toolAugmentedSection(context) + """
                
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

    private String toolAugmentedSection(Map<String, Object> context) {
        if (!Boolean.TRUE.equals(context.get("assistantToolLoop"))) {
            return "";
        }
        return """
                
                ## 本轮：工具增强（Tool-Augmented）
                - 先 **制定计划**（## 现状 → ## 诊断 → ## 处置计划 → ## 执行结果 → ## 验证），再逐步调用工具；禁止跳过采集直接下结论。
                - 凡涉及本机指标，**必须先调用 MCP 工具**；禁止虚构数值或日志原文。
                - 默认处置为 **DRY-RUN 预览**；仅当上下文 `userConfirmedWrite=true` 或用户已说「确认执行」时，方可真实删除/重启（仍受路径白名单约束）。
                - 未调用工具或工具失败时，明确写「缺少 live 数据」，不得编造。
                """;
    }

    private static String appendChatAntiHallucinationFooter(String content, boolean writeConfirmed) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (content.contains("数据依据说明")) {
            return content;
        }
        if (writeConfirmed) {
            return content + "\n\n---\n**数据依据说明**：本轮已在您确认后按策略执行写操作。";
        }
        return content + "\n\n---\n**数据依据说明**：结论来自本轮真实工具/编排采集。"
                + " 若报告中有「预览/待确认」项，回复 **「确认执行」** 可在策略允许范围内自动落地；"
                + " 也可在「工具箱」中单项确认。";
    }

    private static String appendChatAntiHallucinationFooter(String content) {
        return appendChatAntiHallucinationFooter(content, false);
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
