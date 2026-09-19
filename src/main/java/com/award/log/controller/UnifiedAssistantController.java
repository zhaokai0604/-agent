package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AiModelRouter;
import com.award.log.service.impl.UnifiedAssistantService;
import com.award.log.security.HttpAuditSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一运维助手 HTTP 入口（上下文、模型列表、状态预览、SSE 流式对话）。
 * <p>
 * SSE 路径注意：客户端断开后必须 dispose Reactor 订阅，否则 LLM/工具流继续跑，
 * 会占满 {@code sseTaskExecutor}（有界队列 + AbortPolicy）。
 */
@Slf4j
@Tag(name = "Unified Assistant")
@RestController
@RequestMapping("/api/assistant")
public class UnifiedAssistantController {

    @Autowired
    private UnifiedAssistantService unifiedAssistantService;

    @Autowired
    private AiModelRouter aiModelRouter;

    @Autowired
    private HttpAuditSubject httpAuditSubject;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "Get assistant context")
    @GetMapping("/context")
    public Result<Map<String, Object>> context() {
        return Result.success(unifiedAssistantService.getAssistantContext());
    }

    @Operation(summary = "Get configured assistant model profiles")
    @GetMapping("/models")
    public Result<Map<String, Object>> models() {
        return Result.success(aiModelRouter.snapshot());
    }

    @Operation(summary = "Preview assistant execution state")
    @PostMapping("/state/preview")
    public Result<Map<String, Object>> statePreview(@RequestBody Map<String, Object> payload) {
        String rawMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        String sessionId = parseSessionId(payload.get("sessionId"));
        List<UnifiedAssistantService.ChatTurn> history = parseHistory(payload.get("history"));
        boolean useToolAgent = parseUseToolAgent(payload.get("useToolAgent"));
        boolean confirmRemediation = parseConfirmRemediation(payload.get("confirmRemediation"));
        Map<String, Object> preview = sessionId.isBlank()
                ? unifiedAssistantService.previewAgentState(rawMessage, history, useToolAgent, confirmRemediation)
                : unifiedAssistantService.previewAgentState(
                        rawMessage, history, useToolAgent, confirmRemediation, sessionId);
        return Result.success(preview);
    }

    @Operation(summary = "Unified assistant streaming chat")
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody Map<String, Object> payload) {
        String rawMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请填写问题");
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }
        // Keep this aligned with the service limit; otherwise long questions and
        // text attachments are silently truncated at the HTTP boundary.
        final String message = rawMessage.length() > 32000 ? rawMessage.substring(0, 32000) : rawMessage;
        final String sessionId = parseSessionId(payload.get("sessionId"));
        List<UnifiedAssistantService.ChatTurn> history = parseHistory(payload.get("history"));
        boolean useToolAgent = parseUseToolAgent(payload.get("useToolAgent"));
        boolean confirmRemediation = parseConfirmRemediation(payload.get("confirmRemediation"));
        String modelProfile = parseModelProfile(payload.get("modelProfile"));
        // 5 分钟超时；断线/超时/出错时 stop 会 dispose 上游 Flux，避免泄漏
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable stop = () -> {
            cancelled.set(true);
            Disposable d = subscription.getAndSet(null);
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        };
        emitter.onTimeout(stop);
        emitter.onCompletion(stop);
        emitter.onError(e -> stop.run());
        try {
            sseExecutor.execute(() -> {
                // 排队期间客户端已断开则不再启动订阅
                if (cancelled.get()) {
                    return;
                }
                try {
                    Flux<String> stream = modelProfile == null
                            ? unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation, null, currentRequester(), sessionId)
                            : unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation, modelProfile, currentRequester(), sessionId);
                    // Keep the controller tolerant of older test doubles/custom
                    // service proxies that only stub the legacy overload.
                    if (stream == null) {
                        stream = modelProfile == null
                                ? unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation)
                                : unifiedAssistantService.chatStream(message, history, useToolAgent, confirmRemediation, modelProfile);
                    }
                    if (stream == null) {
                        stream = Flux.empty();
                    }
                    Disposable d = stream
                            .doOnNext(chunk -> {
                                try {
                                    emitter.send(chunk);
                                } catch (Exception e) {
                                    emitter.completeWithError(e);
                                }
                            })
                            .doOnComplete(emitter::complete)
                            .doOnError(emitter::completeWithError)
                            .subscribe();
                    subscription.set(d);
                    // subscribe 与 disconnect 竞态：若已 cancelled，立刻取消刚挂上的订阅
                    if (cancelled.get()) {
                        d.dispose();
                    }
                } catch (Exception e) {
                    log.error("Unified assistant stream failed", e);
                    emitter.completeWithError(e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("SSE 线程池繁忙，拒绝助手流式请求");
            try {
                emitter.send("当前请求较多，请稍后重试");
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    private String currentRequester() {
        String requester = httpAuditSubject == null ? null : httpAuditSubject.currentOperatorId();
        return requester == null || requester.isBlank() ? "anonymous" : requester;
    }

    private static String parseSessionId(Object raw) {
        if (raw == null) {
            return "";
        }
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) {
            return "";
        }
        // 会话 ID 只用于链路关联，不接受超长或控制字符，避免污染日志与审计字段。
        if (value.length() > 128) {
            value = value.substring(0, 128);
        }
        return value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    /**
     * 解析客户端 history：只保留最近 {@link UnifiedAssistantService#MAX_HISTORY_TURNS} 轮，
     * 每轮内容截断到 {@link UnifiedAssistantService#MAX_TURN_CHARS}。
     * 必须在 Controller 入口就截，不能等 Service 的 trimHistory——否则 Jackson 已把超大 List 建在堆上。
     */
    static List<UnifiedAssistantService.ChatTurn> parseHistory(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        int maxTurns = UnifiedAssistantService.MAX_HISTORY_TURNS;
        int maxChars = UnifiedAssistantService.MAX_TURN_CHARS;
        int from = Math.max(0, list.size() - maxTurns);
        List<UnifiedAssistantService.ChatTurn> turns = new ArrayList<>();
        for (int i = from; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object role = map.get("role");
            Object content = map.get("content");
            if (role == null || content == null) {
                continue;
            }
            String r = String.valueOf(role).trim();
            String c = String.valueOf(content).trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.length() > maxChars) {
                c = c.substring(0, maxChars) + "\n...[truncated]";
            }
            turns.add(new UnifiedAssistantService.ChatTurn(r, c));
        }
        return turns;
    }

    private static boolean parseUseToolAgent(Object raw) {
        if (raw == null) {
            return true;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return true;
        }
        return !("false".equalsIgnoreCase(s) || "0".equals(s));
    }

    private static boolean parseConfirmRemediation(Object raw) {
        if (raw == null) {
            return false;
        }
        String s = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String parseModelProfile(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
