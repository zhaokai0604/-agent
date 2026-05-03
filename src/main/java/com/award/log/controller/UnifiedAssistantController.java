package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.impl.UnifiedAssistantService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Unified Assistant")
@RestController
@RequestMapping("/api/assistant")
public class UnifiedAssistantController {

    @Autowired
    private UnifiedAssistantService unifiedAssistantService;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "获取统一助手上下文")
    @GetMapping("/context")
    public Result<Map<String, Object>> context() {
        return Result.success(unifiedAssistantService.getAssistantContext());
    }

    @Operation(summary = "统一助手流式问答（支持多轮 history：role=user|assistant, content=文本）")
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody Map<String, Object> payload) {
        String rawMessage = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请求内容不能为空");
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        final String message = rawMessage.length() > 4000 ? rawMessage.substring(0, 4000) : rawMessage;
        List<UnifiedAssistantService.ChatTurn> history = parseHistory(payload.get("history"));
        SseEmitter emitter = new SseEmitter(300000L);
        sseExecutor.execute(() -> {
            try {
                unifiedAssistantService.chatStream(message, history)
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
            } catch (Exception e) {
                log.error("统一助手流式响应异常", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private static List<UnifiedAssistantService.ChatTurn> parseHistory(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UnifiedAssistantService.ChatTurn> turns = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (role == null || content == null) {
                continue;
            }
            String r = String.valueOf(role).trim();
            String c = String.valueOf(content).trim();
            if (c.isEmpty()) {
                continue;
            }
            turns.add(new UnifiedAssistantService.ChatTurn(r, c));
        }
        return turns;
    }
}
