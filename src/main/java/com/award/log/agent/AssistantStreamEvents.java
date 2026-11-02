package com.award.log.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 助手流式通道上的结构化 SSE 事件编码（与 Markdown 正文解耦）。
 * 行前缀 {@link #PREFIX}，前端按此前缀剥离 JSON。
 */
public final class AssistantStreamEvents {

    public static final String PREFIX = "ASSISTANT_EVENT:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssistantStreamEvents() {
    }

    /** 编码为 {@code ASSISTANT_EVENT:{json}}；失败返回 null（调用方应忽略该行）。 */
    public static String encode(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return null;
        }
        try {
            return PREFIX + MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
