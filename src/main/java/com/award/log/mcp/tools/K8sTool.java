package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s 只读诊断（kubectl get/describe）；受控 apply 须走 PEP。
 */
@Slf4j
@Component
public class K8sTool {

    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final ObjectMapper objectMapper;

    public K8sTool(MinPrivilegeExecutor minPrivilegeExecutor, ObjectMapper objectMapper) {
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "k8sDiagnose", description = "K8s 只读诊断：pods/nodes/events")
    public String k8sDiagnose(
            @ToolParam(description = "操作 get-pods|get-nodes|get-events", required = false) String operation,
            @ToolParam(description = "命名空间", required = false) String namespace) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        String op = operation == null || operation.isBlank() ? "get-pods" : operation.trim().toLowerCase();
        List<String> cmd;
        switch (op) {
            case "get-nodes" -> cmd = List.of("kubectl", "get", "nodes", "-o", "wide");
            case "get-events" -> cmd = List.of("kubectl", "get", "events", "-n", ns, "--sort-by=.lastTimestamp");
            default -> cmd = List.of("kubectl", "get", "pods", "-n", ns, "-o", "wide");
        }
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(cmd, 45_000L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", op);
        data.put("namespace", ns);
        data.put("success", result.success());
        data.put("output", result.output());
        if (!result.success()) {
            data.put("error", result.error());
        }
        return result.success()
                ? McpToolResponses.success(objectMapper, objectMapper.writeValueAsString(data), System.currentTimeMillis() - start)
                : McpToolResponses.error(objectMapper, result.error() == null ? "kubectl failed" : result.error(), start);
    }
}
