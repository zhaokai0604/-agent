package com.award.log.mcp.tools;

import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单一入口：触发全栈自主运维编排（融合全部 MCP 工具）。
 */
@Slf4j
@Component
public class AutonomousOpsTool {

    private final AutonomousOpsOrchestrator autonomousOpsOrchestrator;
    private final ObjectMapper objectMapper;

    public AutonomousOpsTool(AutonomousOpsOrchestrator autonomousOpsOrchestrator, ObjectMapper objectMapper) {
        this.autonomousOpsOrchestrator = autonomousOpsOrchestrator;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "runAutonomousOps",
            description = "一键自主运维：并行调用全部观测类 MCP 工具完成感知与诊断，并在策略允许时自动执行临时/日志清理或服务重启；"
                    + "返回 Markdown 报告与结构化 findings。用户说「自动运维」「全自动巡检」时优先调用本工具。")
    public String runAutonomousOps(
            @ToolParam(description = "用户意图摘要，可填「自动运维」", required = false) String userIntent,
            @ToolParam(description = "true 时在策略允许下强制执行已预览的写操作", required = false) Boolean forceRemediate
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        String msg = userIntent == null || userIntent.isBlank() ? "自动运维" : userIntent.trim();
        if (Boolean.TRUE.equals(forceRemediate)) {
            OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
            if (ctx != null && ctx.isChatAgentPath() && !ctx.isUserConfirmedWrite()) {
                forceRemediate = false;
            } else {
                msg = msg + " 执行修复";
            }
        }
        McpToolSurface surface = McpToolSurface.FULL;
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.getToolSurface() != null) {
            surface = ctx.getToolSurface();
        }
        AutonomousOpsOrchestrator.RunResult result =
                autonomousOpsOrchestrator.run(msg, surface, RiskLevel.LOW);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", result.traceId());
        payload.put("markdown", result.markdown());
        payload.put("report", result.report());

        long duration = System.currentTimeMillis() - start;
        return objectMapper.writeValueAsString(
                new com.award.log.mcp.ToolResult<>(true, objectMapper.writeValueAsString(payload), null, duration, false));
    }

    /** MCP HTTP：与 @Tool 共用编排器 */
    public String executeGateway(String userIntent, Boolean forceRemediate) throws JsonProcessingException {
        return runAutonomousOps(userIntent, forceRemediate);
    }
}
