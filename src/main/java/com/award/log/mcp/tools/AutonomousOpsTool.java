package com.award.log.mcp.tools;

import com.award.log.agent.OpsPatrolAutomationService;
import com.award.log.agent.OpsRunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容旧自主运维工具名，现统一转为巡检自动修复单轨入口。
 */
@Component
public class AutonomousOpsTool {

    private final OpsPatrolAutomationService patrolAutomationService;
    private final ObjectMapper objectMapper;

    public AutonomousOpsTool(OpsPatrolAutomationService patrolAutomationService, ObjectMapper objectMapper) {
        this.patrolAutomationService = patrolAutomationService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "runAutonomousOps",
            description = "触发一次巡检自动修复单轨，返回巡检摘要、自动修复结果与待确认方案。")
    public String runAutonomousOps(
            @ToolParam(description = "用户意图摘要，可选", required = false) String userIntent,
            @ToolParam(description = "兼容旧参数，当前保留但仅触发巡检单轨", required = false) Boolean forceRemediate
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        OpsRunResult result = patrolAutomationService.run();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", result.traceId());
        payload.put("markdown", result.markdown());
        payload.put("report", result.report());
        payload.put("mode", "PATROL_AUTOMATION");
        if (userIntent != null && !userIntent.isBlank()) {
            payload.put("userIntent", userIntent.trim());
        }
        if (forceRemediate != null) {
            payload.put("forceRemediateAccepted", forceRemediate);
        }

        long duration = System.currentTimeMillis() - start;
        return objectMapper.writeValueAsString(
                new com.award.log.mcp.ToolResult<>(true, objectMapper.writeValueAsString(payload), null, duration, false));
    }

    public String executeGateway(String userIntent, Boolean forceRemediate) throws JsonProcessingException {
        return runAutonomousOps(userIntent, forceRemediate);
    }
}
