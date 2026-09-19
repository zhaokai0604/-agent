package com.award.log.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.award.log.security.effect.ToolOperationPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Action Guardrails：工具环内循环稳定性（步骤依赖、失败预算、Nudge）。
 */
@Service
public class ActionGuardrailService {

    private final ConcurrentHashMap<String, TraceState> traces = new ConcurrentHashMap<>();

    @Value("${agent.guardrails.max-failures-per-trace:5}")
    private int maxFailuresPerTrace;

    public GuardrailVerdict beforeToolCall(String traceId, String toolName, Map<String, Object> parameters) {
        TraceState state = traces.computeIfAbsent(traceId == null ? "default" : traceId, k -> new TraceState());
        state.lastParameters.put(toolName == null ? "" : toolName.trim(),
                parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
        if (state.failures >= maxFailuresPerTrace) {
            return GuardrailVerdict.block("失败预算已用尽，请重新规划或人工介入", nudge("减少工具调用次数，先汇总已有证据"));
        }
        if (isWriteTool(toolName, parameters) && !state.hasSuccessfulObserve) {
            return GuardrailVerdict.block(
                    "写工具前须先完成只读观测",
                    nudge("先调用 SystemLoadTool、DiskTool 或 ProcessTool 收集证据"));
        }
        if (isWriteTool(toolName, parameters) && state.lastObserveFailed) {
            return GuardrailVerdict.block(
                    "前置观测工具失败，禁止写操作",
                    nudge("修复观测步骤或缩小范围后重试"));
        }
        return GuardrailVerdict.allow();
    }

    public void afterToolCall(String traceId, String toolName, boolean success, String resultJson) {
        TraceState state = traces.computeIfAbsent(traceId == null ? "default" : traceId, k -> new TraceState());
        if (!success) {
            state.failures++;
        }
        Map<String, Object> parameters = state.lastParameters.getOrDefault(
                toolName == null ? "" : toolName.trim(), Map.of());
        if (!isWriteTool(toolName, parameters)) {
            state.hasSuccessfulObserve = state.hasSuccessfulObserve || success;
            state.lastObserveFailed = !success;
            if (success) {
                state.observeTools.add(toolName);
            }
        } else if (success) {
            state.writeTools.add(toolName);
        }
        state.toolResults.put(toolName, resultJson == null ? "" : resultJson);
    }

    public void clearTrace(String traceId) {
        if (traceId != null) {
            traces.remove(traceId);
        }
    }

    private boolean isWriteTool(String toolName, Map<String, Object> parameters) {
        return ToolOperationPolicy.isWriteOperation(toolName, parameters);
    }

    private static Map<String, Object> nudge(String message) {
        Map<String, Object> nudge = new LinkedHashMap<>();
        nudge.put("type", "guardrail_nudge");
        nudge.put("message", message);
        return nudge;
    }

  public record GuardrailVerdict(boolean allowed, String reason, Map<String, Object> nudge) {
        static GuardrailVerdict allow() {
            return new GuardrailVerdict(true, "", Map.of());
        }

        static GuardrailVerdict block(String reason, Map<String, Object> nudge) {
            return new GuardrailVerdict(false, reason, nudge);
        }
    }

    private static final class TraceState {
        boolean hasSuccessfulObserve = false;
        boolean lastObserveFailed = false;
        int failures = 0;
        final List<String> observeTools = new ArrayList<>();
        final List<String> writeTools = new ArrayList<>();
        final Map<String, String> toolResults = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> lastParameters = new ConcurrentHashMap<>();
    }
}
