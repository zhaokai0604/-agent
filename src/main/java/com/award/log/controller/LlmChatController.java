package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AiAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@Tag(name = "LLM Chat")
@RestController
@RequestMapping("/api/v1/llm")
public class LlmChatController {

    private final AiAnalysisService aiAnalysisService;

    public LlmChatController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @Operation(summary = "流式对话与工具调用")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        SseEmitter emitter = new SseEmitter(60000L);
        new Thread(() -> {
            try {
                String result = aiAnalysisService.analyzeLog(prompt);
                for (String token : result.split("")) {
                    emitter.send(SseEmitter.event().name("token").data(token));
                }
                emitter.send(SseEmitter.event().name("done").data(Map.of("completed", true)));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, "llm-sse-thread").start();
        return emitter;
    }

    @Operation(summary = "非流式对话")
    @PostMapping("/chat-sync")
    public Result<Map<String, Object>> chatSync(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        String answer = aiAnalysisService.analyzeLog(prompt);
        return Result.success(Map.of("answer", answer));
    }
}
