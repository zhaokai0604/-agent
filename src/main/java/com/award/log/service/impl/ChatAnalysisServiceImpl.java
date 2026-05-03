package com.award.log.service.impl;

import com.award.log.service.*;
import com.award.log.service.ChatMemoryService.MemoryEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatAnalysisServiceImpl implements ChatAnalysisService {

    @Autowired
    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private ToolRegistryService toolRegistry;

    @Autowired
    private ChatMemoryService memoryService;

    @Autowired
    private ChatModel chatModel;

    private final Map<String, List<ChatMessage>> conversationHistories = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_SIZE = 50;
    private static final String DEFAULT_SESSION_ID = "default";

    private String currentSessionId = DEFAULT_SESSION_ID;

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "【([a-z_]+)】\\s*\\{\\s*([^}]*?)\\s*\\}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public Flux<String> chat(String userMessage) {
        log.info("收到聊天消息: [{}]", userMessage);

        try {
            IntentRecognitionService.RecognitionResult intent = intentRecognitionService.recognize(userMessage);

            String memoryContext = buildMemoryContext();
            String toolResult = null;
            String analysisContext = "";

            if (shouldCallTool(intent, userMessage)) {
                toolResult = executeToolWithMemory(intent, userMessage);
                if (toolResult != null) {
                    analysisContext = "\n\n工具执行结果：\n" + toolResult;
                }
            }

            String aiResponse = generateAiResponse(userMessage, intent, analysisContext, memoryContext);

            addToHistory(new ChatMessage("user", userMessage, intent.getIntent().name()));
            addToHistory(new ChatMessage("assistant", aiResponse));

            memoryService.addToMemory(currentSessionId, new MemoryEntry("user", userMessage));
            memoryService.addToMemory(currentSessionId, new MemoryEntry("assistant", aiResponse));

            return Flux.just(aiResponse);

        } catch (Exception e) {
            log.error("处理聊天消息失败", e);
            return Flux.just("抱歉，处理您的请求时出现了错误：" + e.getMessage());
        }
    }

    private String buildMemoryContext() {
        List<MemoryEntry> recentMemory = memoryService.getRecentMemory(currentSessionId, 6);
        if (recentMemory.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n【对话记忆摘要】\n");

        ChatMemoryService.MemorySummary summary = memoryService.getMemorySummary(currentSessionId);
        if (summary.getLastIntent() != null) {
            context.append(String.format("- 最近意图：%s\n", summary.getLastIntent()));
        }
        if (summary.getAnalysisCount() > 0) {
            context.append(String.format("- 已完成%d次分析\n", summary.getAnalysisCount()));
        }

        context.append("\n最近对话：\n");
        for (int i = Math.max(0, recentMemory.size() - 4); i < recentMemory.size(); i++) {
            MemoryEntry entry = recentMemory.get(i);
            String preview = entry.getContent().length() > 100
                    ? entry.getContent().substring(0, 100) + "..."
                    : entry.getContent();
            context.append(String.format("- %s: %s\n", entry.getRole(), preview));
        }

        return context.toString();
    }

    private boolean shouldCallTool(IntentRecognitionService.RecognitionResult intent, String userMessage) {
        IntentRecognitionService.Intent intentType = intent.getIntent();

        if (intentType == IntentRecognitionService.Intent.QUERY_ANOMALIES ||
            intentType == IntentRecognitionService.Intent.QUERY_ERRORS ||
            intentType == IntentRecognitionService.Intent.QUERY_BY_TIME ||
            intentType == IntentRecognitionService.Intent.DIAGNOSE_ISSUE ||
            intentType == IntentRecognitionService.Intent.GENERATE_REPORT ||
            intentType == IntentRecognitionService.Intent.STATISTICS) {
            return true;
        }

        String lowerMsg = userMessage.toLowerCase();
        return lowerMsg.contains("查询") || lowerMsg.contains("诊断") ||
               lowerMsg.contains("报告") || lowerMsg.contains("分析") ||
               lowerMsg.contains("统计") || lowerMsg.contains("最近") ||
               lowerMsg.contains("错误") || lowerMsg.contains("异常") ||
               lowerMsg.contains("看看") || lowerMsg.contains("检查");
    }

    private String executeToolWithMemory(IntentRecognitionService.RecognitionResult intent, String userMessage) {
        try {
            IntentRecognitionService.Intent intentType = intent.getIntent();
            Map<String, Object> params = extractParameters(intent, userMessage);

            AiTool tool = null;
            String toolName = null;

            switch (intentType) {
                case QUERY_ANOMALIES:
                case QUERY_ERRORS:
                case QUERY_BY_TIME:
                case STATISTICS:
                    tool = toolRegistry.getTool("query_logs");
                    toolName = "query_logs";
                    break;
                case DIAGNOSE_ISSUE:
                    tool = toolRegistry.getTool("diagnose_system");
                    toolName = "diagnose_system";
                    break;
                case GENERATE_REPORT:
                    tool = toolRegistry.getTool("generate_report");
                    toolName = "generate_report";
                    break;
                default:
                    break;
            }

            if (tool == null) {
                Matcher matcher = TOOL_CALL_PATTERN.matcher(userMessage);
                if (matcher.find()) {
                    toolName = matcher.group(1);
                    tool = toolRegistry.getTool(toolName);
                }
            }

            if (tool != null) {
                AiTool.ToolResult result = tool.execute(params);
                if (result.isSuccess()) {
                    MemoryEntry memoryEntry = new MemoryEntry("tool", result.getContent());
                    memoryEntry.setIntent(intentType.name());
                    memoryEntry.setToolUsed(toolName);
                    memoryEntry.setAnalysis(true);
                    memoryService.addToMemory(currentSessionId, memoryEntry);
                    return result.getContent();
                } else {
                    return "工具执行失败：" + result.getError();
                }
            }

        } catch (Exception e) {
            log.error("工具执行失败", e);
            return null;
        }
        return null;
    }

    private Map<String, Object> extractParameters(IntentRecognitionService.RecognitionResult intent, String userMessage) {
        Map<String, Object> params = new HashMap<>();

        if (intent.getTimeRange() != null) {
            params.put("timeRange", intent.getTimeRange().getDuration());
        }

        if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            params.put("keywords", String.join(" ", intent.getKeywords()));
        }

        if (intent.getTargetService() != null) {
            params.put("service", intent.getTargetService());
        }

        String lowerMsg = userMessage.toLowerCase();
        if (lowerMsg.contains("fatal") || intent.getKeywords().contains("FATAL")) {
            params.put("severity", "FATAL");
        } else if (lowerMsg.contains("error") || intent.getKeywords().contains("ERROR")) {
            params.put("severity", "ERROR");
        } else if (lowerMsg.contains("warning") || intent.getKeywords().contains("WARNING")) {
            params.put("severity", "WARNING");
        }

        return params;
    }

    private Map<String, Object> parseParameters(String paramsStr) {
        Map<String, Object> params = new HashMap<>();
        if (paramsStr == null || paramsStr.isEmpty()) return params;

        Pattern kvPattern = Pattern.compile("([a-zA-Z_]+)\\s*[=:]\\s*[\"']?([^\"'\\n]+)[\"']?");
        Matcher matcher = kvPattern.matcher(paramsStr);

        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            params.put(key, value);
        }

        return params;
    }

    private String generateAiResponse(String userMessage,
                                     IntentRecognitionService.RecognitionResult intent,
                                     String analysisContext,
                                     String memoryContext) {
        String systemPrompt = buildEnhancedSystemPrompt();

        String userPrompt;
        if (analysisContext != null && !analysisContext.isEmpty()) {
            userPrompt = "用户问题：「" + userMessage + "」" + memoryContext + "\n\n" +
                        "工具执行结果：\n" + analysisContext + "\n\n" +
                        "请基于上述工具执行结果和专业背景知识，用专业但友好的语气回答用户的问题。\n" +
                        "如果工具结果中包含日志详情，请引用关键信息并给出分析。\n" +
                        "适当使用Markdown格式，重点内容加粗，列表清晰呈现。";
        } else {
            userPrompt = "用户问题：「" + userMessage + "」" + memoryContext + "\n\n" +
                        "请根据你的专业知识，帮助用户解答问题。\n" +
                        "适当使用Markdown格式，重点内容加粗。";
        }

        try {
            Message systemMessage = new SystemMessage(systemPrompt);
            Message userMessageObj = new UserMessage(userPrompt);

            List<Message> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.addAll(getConversationHistoryMessages());
            messages.add(userMessageObj);

            Prompt prompt = new Prompt(messages);

            String response = CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
                    .orTimeout(60, TimeUnit.SECONDS)
                    .thenApply(res -> res.getResult().getOutput().getText())
                    .exceptionally(ex -> {
                        log.warn("AI调用超时或失败，进入降级: {}", ex.getMessage());
                        return null;
                    })
                    .join();
            return response != null ? response : "抱歉，AI暂时无法回答这个问题。";
        } catch (Exception e) {
            log.error("AI调用失败", e);
            if (analysisContext != null && !analysisContext.isEmpty()) {
                return buildDirectResponse(intent, analysisContext);
            }
            return "抱歉，AI服务暂时不可用。但我可以帮你使用日志查询工具来分析你的问题。";
        }
    }

    private List<Message> getConversationHistoryMessages() {
        List<ChatMessage> history = conversationHistories.getOrDefault(currentSessionId, new ArrayList<>());
        List<Message> messages = new ArrayList<>();

        int startIndex = Math.max(0, history.size() - 10);
        for (ChatMessage msg : history.subList(startIndex, history.size())) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }

        return messages;
    }

    private String buildEnhancedSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「慧鉴小助手」，一个专业的智能日志分析AI助手。\n\n");

        prompt.append("【核心能力】\n");
        prompt.append("1. 系统日志分析与异常检测\n");
        prompt.append("2. 问题根因诊断与定位\n");
        prompt.append("3. 健康状态评估与建议\n");
        prompt.append("4. 专业术语通俗化解释\n\n");

        prompt.append("【沟通风格】\n");
        prompt.append("- 专业、友好、耐心\n");
        prompt.append("- 复杂问题分步骤讲解\n");
        prompt.append("- 不确定的问题明确告知，不胡编\n");
        prompt.append("- 主动追问以获取更多信息\n\n");

        prompt.append("【回复格式】\n");
        prompt.append("- 使用Markdown格式化\n");
        prompt.append("- 重点用**加粗**\n");
        prompt.append("- 代码/日志用```包裹\n");
        prompt.append("- 列表项用-开头\n\n");

        prompt.append("【专业建议】\n");
        prompt.append("- 检测到FATAL/ERROR级别日志时，主动提醒用户及时处理\n");
        prompt.append("- 发现异常模式时，提供可能的根因和解决方案\n");
        prompt.append("- 分析后主动提供后续操作建议\n\n");

        if (toolRegistry.getToolCount() > 0) {
            prompt.append("【可用工具】\n");
            prompt.append(toolRegistry.getToolsSchema()).append("\n");
        }

        return prompt.toString();
    }

    private String buildDirectResponse(IntentRecognitionService.RecognitionResult intent, String analysisContext) {
        StringBuilder response = new StringBuilder();
        IntentRecognitionService.Intent intentType = intent.getIntent();

        switch (intentType) {
            case QUERY_ANOMALIES:
                response.append("根据您的查询，我已经找到了一些异常日志：\n\n");
                break;
            case QUERY_ERRORS:
                response.append("以下是您查询的错误日志：\n\n");
                break;
            case DIAGNOSE_ISSUE:
                response.append("我已经完成了系统诊断分析：\n\n");
                break;
            case GENERATE_REPORT:
                response.append("这是您请求的分析报告：\n\n");
                break;
            case STATISTICS:
                response.append("统计结果如下：\n\n");
                break;
            default:
                response.append("分析结果：\n\n");
        }

        response.append(analysisContext);

        return response.toString();
    }

    private void addToHistory(ChatMessage message) {
        String sessionId = currentSessionId;
        conversationHistories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        List<ChatMessage> history = conversationHistories.get(sessionId);
        history.add(message);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }
    }

    @Override
    public AnalysisResult analyze(String userMessage) {
        AnalysisResult result = new AnalysisResult();
        result.setSummary("该方法已废弃，请使用 chat() 方法进行对话式分析");
        return result;
    }

    @Override
    public Flux<String> analyzeStream(String userMessage) {
        return chat(userMessage);
    }

    @Override
    public List<ChatMessage> getConversationHistory() {
        return conversationHistories.getOrDefault(currentSessionId, new ArrayList<>());
    }

    @Override
    public void clearHistory() {
        conversationHistories.remove(currentSessionId);
        memoryService.clearMemory(currentSessionId);
    }

    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }
}