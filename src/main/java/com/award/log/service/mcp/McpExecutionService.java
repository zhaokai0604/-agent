package com.award.log.service.mcp;

import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.security.OpsSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具实际分发执行（含 OpsSecurityContext 生命周期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpExecutionService {

    private final McpToolDispatcher mcpToolDispatcher;

    public Map<String, Object> execute(String toolName, Map<String, Object> parameters, long startTime,
                                       String traceId, String userInstruction) {
        OpsSecurityContext.open(traceId, userInstruction, true);
        try {
            Map<String, Object> response = new HashMap<>();
            McpToolDispatchResult dispatched = mcpToolDispatcher.dispatch(toolName, parameters);
            long duration = System.currentTimeMillis() - startTime;

            if (dispatched.success()) {
                response.put("success", true);
                response.put("data", dispatched.data());
                response.put("duration", duration);
                log.info("工具 {} 执行成功，耗时: {}ms", toolName, duration);
            } else {
                response.put("success", false);
                response.put("error", dispatched.errorMessage());
                response.put("duration", duration);
                log.info("工具 {} 执行失败: {}", toolName, dispatched.errorMessage());
            }
            return response;
        } finally {
            OpsSecurityContext.clear();
        }
    }
}
