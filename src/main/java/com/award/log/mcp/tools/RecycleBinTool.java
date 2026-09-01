package com.award.log.mcp.tools;

import com.award.log.mcp.McpToolResponses;
import com.award.log.service.OpsRecycleBinService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 回收站：list / restore / purge 可逆删除条目。
 */
@Slf4j
@Component
public class RecycleBinTool {

    private final OpsRecycleBinService recycleBinService;
    private final ObjectMapper objectMapper;

    public RecycleBinTool(OpsRecycleBinService recycleBinService, ObjectMapper objectMapper) {
        this.recycleBinService = recycleBinService;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "recycleBinOps", description = "回收站操作：list 列出、restore 按 handle 恢复、purge 清理过期")
    public String recycleBinOps(
            @ToolParam(description = "操作 list|restore|purge", required = true) String operation,
            @ToolParam(description = "恢复句柄 handle", required = false) String handle) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            String op = operation == null ? "" : operation.trim().toLowerCase();
            if ("list".equals(op)) {
                data.put("entries", recycleBinService.listEntries());
            } else if ("restore".equals(op)) {
                String originalPath = recycleBinService.listEntries().stream()
                        .filter(entry -> handle != null && handle.equals(entry.get("handle")))
                        .map(entry -> String.valueOf(entry.getOrDefault("originalPath", "")))
                        .findFirst()
                        .orElse("");
                boolean ok = recycleBinService.restore(handle);
                data.put("restored", ok);
                data.put("handle", handle);
                data.put("originalPath", originalPath);
                data.put("recoveryVerified", ok && !originalPath.isBlank()
                        && Files.exists(Path.of(originalPath)));
            } else if ("purge".equals(op)) {
                recycleBinService.purgeExpired();
                data.put("purged", true);
            } else {
                return McpToolResponses.error(objectMapper, "不支持的操作: " + operation, start);
            }
            return McpToolResponses.success(
                    objectMapper, objectMapper.writeValueAsString(data), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("RecycleBinTool failed: {}", e.getMessage());
            return McpToolResponses.error(objectMapper, e.getMessage(), start);
        }
    }
}
