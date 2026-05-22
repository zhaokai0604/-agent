package com.award.log.controller;

import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.service.AiDiagnosisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;

@Slf4j
@Tag(name = "AI Diagnosis Stream")
@RestController
@RequestMapping("/log/diagnose")
public class AiDiagnosisController {

    @Autowired
    private AiDiagnosisService aiDiagnosisService;

    @Autowired
    private AnalysisTaskManager taskManager;

    @Autowired
    @Qualifier("sseTaskExecutor")
    private ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "通用 AI 运维对话 (流式响应)")
    @PostMapping(value = "/chat", produces = TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> payload) {
        String rawMessage = payload.getOrDefault("message", "").trim();
        if (rawMessage.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send("请求内容不能为空");
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        final String message = rawMessage.length() > AiDiagnosisContextBuilder.CHAT_USER_MESSAGE_MAX_CHARS
                ? rawMessage.substring(0, AiDiagnosisContextBuilder.CHAT_USER_MESSAGE_MAX_CHARS)
                : rawMessage;
        log.info("收到 AI 运维对话请求: [{}]", message);
        SseEmitter emitter = new SseEmitter(180000L);
        sseExecutor.execute(() -> {
            try {
                aiDiagnosisService.chatStream(message)
                        .doOnNext(chunk -> {
                            try {
                                if (chunk != null) emitter.send(chunk);
                            } catch (Exception e) {
                                log.error("SSE 发送数据块失败", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnComplete(() -> {
                            log.info("AI 运维对话响应完成");
                            emitter.complete();
                        })
                        .doOnError(e -> {
                            log.error("AI 运维对话流发生错误", e);
                            emitter.completeWithError(e);
                        })
                        .subscribe();
            } catch (Exception e) {
                log.error("执行 AI 对话任务时发生异常", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "执行 AI 故障诊断 (流式响应)")
    @GetMapping(value = "/stream/{taskId}", produces = TEXT_EVENT_STREAM_VALUE)
    public SseEmitter performDiagnosisStream(@PathVariable String taskId) {
        log.info("开启 AI 故障诊断流式接口，任务ID: [{}]", taskId);
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时

        try {
            TaskInfo task = taskManager.getTask(taskId);
            if (task == null) {
                log.warn("诊断流开启失败：任务未找到，任务ID: [{}]", taskId);
                try {
                    emitter.send("任务未找到，请确认任务ID是否正确。");
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }

            if (task.getResult() == null) {
                log.warn("诊断流开启失败：任务分析未完成，任务ID: [{}]，状态: [{}]", taskId, task.getStatus());
                try {
                    emitter.send("任务分析未完成，请等待分析完成后再进行诊断。当前状态: " + task.getStatus());
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }

            log.info("任务 [{}] 使用完整解析结果进行流式诊断（多样模板 + 正常对照）", taskId);
            
            sseExecutor.execute(() -> {
                StringBuilder fullResponse = new StringBuilder();
                try {
                    aiDiagnosisService.generateDiagnosisStreamFromFullResult(task.getResult())
                            .doOnNext(chunk -> {
                                try {
                                    if (chunk != null) {
                                        // 发送数据块
                                        emitter.send(chunk);
                                        fullResponse.append(chunk);
                                    }
                                } catch (Exception e) {
                                    log.error("SSE 发送诊断数据块失败", e);
                                    try {
                                        emitter.send("诊断流传输异常: " + e.getMessage());
                                    } catch (Exception ignored) {}
                                    emitter.complete();
                                }
                            })
                            .doOnComplete(() -> {
                                log.info("任务 [{}] 的 AI 流式诊断完成，正在持久化完整结果", taskId);
                                // 完成后保存完整结果到数据库
                                taskManager.updateAiDiagnosis(taskId, fullResponse.toString());
                                emitter.complete();
                            })
                            .doOnError(e -> {
                                log.error("AI 诊断流发生错误，任务ID: [{}]", taskId, e);
                                try {
                                    emitter.send("诊断失败: " + e.getMessage());
                                } catch (Exception ignored) {}
                                emitter.complete();
                            })
                            .subscribe(); // 触发流
                } catch (Exception e) {
                    log.error("执行流式诊断任务时发生异常", e);
                    try {
                        emitter.send("诊断执行异常: " + e.getMessage());
                    } catch (Exception ignored) {}
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            log.error("处理 AI 诊断请求时发生异常，任务ID: [{}]", taskId, e);
            try {
                emitter.send("处理诊断请求时发生异常: " + e.getMessage());
            } catch (Exception ignored) {}
            emitter.complete();
        }

        return emitter;
    }
}
