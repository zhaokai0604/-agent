package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ProcessTool extends AbstractCommandExecutor {

    private static final Pattern PS_AUX_PATTERN = Pattern.compile(
        "^(\\S+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d:]+)\\s+(\\S+)\\s+(.*)$"
    );

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;

    @Autowired
    public ProcessTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
    }

    @Tool(name = "listProcesses", description = "执行 ps aux 命令，列出系统进程，可按 CPU 或内存使用率过滤，默认过滤 CPU 或内存占用超过 5% 的进程，以及僵尸进程")
    @Cacheable(value = McpToolsConfig.CACHE_PROCESS_LIST, key = "'processes'", cacheManager = "mcpCacheManager")
    public String listProcesses(
            @ToolParam(description = "最小 CPU 使用率百分比，超过此值的进程会被列出（默认 5.0）", required = false) Double minCpu,
            @ToolParam(description = "最小内存使用率百分比，超过此值的进程会被列出（默认 5.0）", required = false) Double minMem
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        double cpuThreshold = minCpu != null ? minCpu : 5.0;
        double memThreshold = minMem != null ? minMem : 5.0;

        log.info("开始执行进程列表检查，CPU阈值: {}%, 内存阈值: {}%", cpuThreshold, memThreshold);

        try {
            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(List.of("ps", "aux"));

            if (!result.success()) {
                String errorJson = buildErrorResponse("ps 命令执行失败: " + result.error(), startTime);
                log.error("进程列表检查失败: {}", result.error());
                return errorJson;
            }

            List<Map<String, String>> processList = parseOutput(result.output(), cpuThreshold, memThreshold);
            String dataJson = toJson(processList);
            long duration = System.currentTimeMillis() - startTime;

            log.info("进程列表检查完成，共获取 {} 个符合条件的进程，耗时: {}ms", processList.size(), duration);
            return buildSuccessResponse(dataJson, duration, false);

        } catch (Exception e) {
            log.error("执行进程列表检查时发生异常", e);
            return buildErrorResponse("执行进程列表检查时发生异常: " + e.getMessage(), startTime);
        }
    }

    private List<Map<String, String>> parseOutput(String output, double cpuThreshold, double memThreshold) {
        List<Map<String, String>> processList = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return processList;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = PS_AUX_PATTERN.matcher(line);
            if (!matcher.matches()) {
                log.debug("无法解析的行: {}", line);
                continue;
            }

            String user = matcher.group(1);
            double cpu = parsePercent(matcher.group(2));
            double mem = parsePercent(matcher.group(3));
            String etime = matcher.group(4);
            String state = matcher.group(5);
            String command = matcher.group(6).trim();

            boolean isZombie = state != null && state.contains("Z");
            boolean cpuExceeds = cpu > cpuThreshold;
            boolean memExceeds = mem > memThreshold;

            if (!isZombie && !cpuExceeds && !memExceeds) {
                continue;
            }

            Map<String, String> info = new LinkedHashMap<>();
            info.put("user", user);
            info.put("pid", getPidFromCommand(command));
            info.put("cpu", String.format("%.1f", cpu));
            info.put("mem", String.format("%.1f", mem));
            info.put("etime", etime);
            info.put("command", truncateCommand(command));
            info.put("state", isZombie ? "zombie" : "running");
            processList.add(info);
        }
        return processList;
    }

    private double parsePercent(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String getPidFromCommand(String command) {
        String[] parts = command.split("\\s+");
        for (String part : parts) {
            if (part.matches("\\d+")) {
                return part;
            }
        }
        return "unknown";
    }

    private String truncateCommand(String command) {
        if (command.length() > 200) {
            return command.substring(0, 200) + "...";
        }
        return command;
    }

    private String toJson(List<Map<String, String>> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<>(true, data, null, duration, cacheHit));
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<String>(false, null, error, duration, false));
    }
}
