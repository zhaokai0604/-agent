package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.ChatAnalysisService;
import com.award.log.service.ChatAnalysisService.ChatMessage;
import com.award.log.service.ChatAnalysisService.AnalysisResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Chat Analysis")
@RestController
@RequestMapping("/api/chat")
public class ChatAnalysisController {

    @Autowired
    private ChatAnalysisService chatAnalysisService;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "聊天式日志分析 (同步)")
    @PostMapping("/analyze")
    public Result<AnalysisResult> analyze(@RequestBody Map<String, String> payload) {
        String message = payload.get("message");
        log.info("收到聊天分析请求: [{}]", message);

        try {
            AnalysisResult result = chatAnalysisService.analyze(message);
            return Result.success(result);
        } catch (Exception e) {
            log.error("聊天分析失败", e);
            return Result.error("分析失败: " + e.getMessage());
        }
    }

    @Operation(summary = "聊天式日志分析 (流式响应)")
    @PostMapping(value = "/analyze/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter analyzeStream(@RequestBody Map<String, String> payload) {
        String rawMessage = payload.getOrDefault("message", "").trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请求内容不能为空");
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        final String message = rawMessage.length() > 4000 ? rawMessage.substring(0, 4000) : rawMessage;
        log.info("收到流式聊天分析请求: [{}]", message);

        SseEmitter emitter = new SseEmitter(300000L);

        sseExecutor.execute(() -> {
            try {
                chatAnalysisService.analyzeStream(message)
                        .doOnNext(chunk -> {
                            try {
                                if (chunk != null) {
                                    emitter.send(chunk);
                                }
                            } catch (Exception e) {
                                log.error("SSE发送数据失败", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            log.info("流式分析完成");
                            emitter.complete();
                        })
                        .doOnError(e -> {
                            log.error("流式分析错误", e);
                            emitter.completeWithError(e);
                        })
                        .subscribe();
            } catch (Exception e) {
                log.error("执行流式分析失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Operation(summary = "聊天对话 (流式响应)")
    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody Map<String, String> payload) {
        String rawMessage = payload.getOrDefault("message", "").trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请求内容不能为空");
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        final String message = rawMessage.length() > 4000 ? rawMessage.substring(0, 4000) : rawMessage;
        log.info("收到聊天请求: [{}]", message);

        SseEmitter emitter = new SseEmitter(300000L);

        sseExecutor.execute(() -> {
            try {
                chatAnalysisService.chat(message)
                        .doOnNext(chunk -> {
                            try {
                                if (chunk != null) {
                                    emitter.send(chunk);
                                }
                            } catch (Exception e) {
                                log.error("SSE发送数据失败", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            log.info("聊天完成");
                            emitter.complete();
                        })
                        .doOnError(e -> {
                            log.error("聊天错误", e);
                            emitter.completeWithError(e);
                        })
                        .subscribe();
            } catch (Exception e) {
                log.error("执行聊天失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Operation(summary = "获取对话历史")
    @GetMapping("/history")
    public Result<List<ChatMessage>> getHistory() {
        try {
            List<ChatMessage> history = chatAnalysisService.getConversationHistory();
            return Result.success(history);
        } catch (Exception e) {
            log.error("获取对话历史失败", e);
            return Result.error("获取历史失败: " + e.getMessage());
        }
    }

    @Operation(summary = "清除对话历史")
    @DeleteMapping("/history")
    public Result<Boolean> clearHistory() {
        try {
            chatAnalysisService.clearHistory();
            return Result.success(true, "对话历史已清除");
        } catch (Exception e) {
            log.error("清除对话历史失败", e);
            return Result.error("清除失败: " + e.getMessage());
        }
    }
}