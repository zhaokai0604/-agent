package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Tool(name = "listProcesses", description = "列出进程：Linux 用 ps aux；Windows 用 WMI 性能计数器近似 CPU%，内存为物理内存占比。默认列出 CPU 或内存超过阈值（默认 5%）的进程")
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
            if (OsRuntime.isWindows()) {
                return listProcessesWindows(cpuThreshold, memThreshold, startTime);
            }
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

    /**
     * Windows：用性能计数器 {@code PercentProcessorTime} 得到近似 CPU%，勿把 {@code Get-Process.CPU}（累计秒）当百分比。
     */
    private String listProcessesWindows(double cpuThreshold, double memThreshold, long startTime) throws JsonProcessingException {
        String psPerf =
                "$t=(Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize*1KB;"
                        + "$list=@(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction SilentlyContinue"
                        + "|Where-Object{$_.IDProcess -gt 0 -and $_.Name -ne 'Idle'});"
                        + "$list|Sort-Object WorkingSet -Descending|Select-Object -First 80 "
                        + "@{N='ProcessName';E={($_.Name -split '#')[0]}},@{N='Id';E={$_.IDProcess}},"
                        + "@{N='cpuPct';E={[math]::Round([double]$_.PercentProcessorTime,2)}},"
                        + "@{N='memPct';E={[math]::Round(100*[double]$_.WorkingSet/($t+1),2)}},"
                        + "@{N='wsMb';E={[math]::Round($_.WorkingSet/1MB,2)}}|ConvertTo-Json -Compress -Depth 5";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psPerf), 60_000L);
        List<Map<String, String>> processList = parseWindowsProcessJson(result, cpuThreshold, memThreshold, true);
        if (processList.isEmpty() && (!result.success() || result.output() == null || result.output().isBlank())) {
            return buildErrorResponse("Windows 进程列表失败: " + (result.success() ? "无数据" : result.error()), startTime);
        }
        if (processList.isEmpty()) {
            processList = listProcessesWindowsMemFallback(cpuThreshold, memThreshold);
        }
        String dataJson = toJson(processList);
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(dataJson, duration, false);
    }

    /** perf 计数器不可用时：仅按内存占比筛选，CPU 列标注为不可用 */
    private List<Map<String, String>> listProcessesWindowsMemFallback(double cpuThreshold, double memThreshold) {
        String ps =
                "$t=(Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize*1KB;"
                        + "Get-Process -ErrorAction SilentlyContinue|Sort-Object WorkingSet64 -Descending|Select-Object -First 60 "
                        + "ProcessName,Id,@{N='cpuPct';E={-1}},"
                        + "@{N='memPct';E={[math]::Round(100*$_.WorkingSet64/($t+1),2)}},"
                        + "@{N='wsMb';E={[math]::Round($_.WS/1MB,2)}}|ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult r = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 60_000L);
        List<Map<String, String>> list = parseWindowsProcessJson(r, cpuThreshold, memThreshold, false);
        if (!list.isEmpty()) {
            log.info("Windows 进程列表已回退为仅内存阈值筛选（CPU 计数器不可用）");
        }
        return list;
    }

    private List<Map<String, String>> parseWindowsProcessJson(
            AbstractCommandExecutor.CommandResult result,
            double cpuThreshold,
            double memThreshold,
            boolean useCpuThreshold) {
        List<Map<String, String>> processList = new ArrayList<>();
        if (!result.success() || result.output() == null || result.output().isBlank()) {
            return processList;
        }
        try {
            JsonNode node = objectMapper.readTree(result.output());
            JsonNode arr = node.isArray() ? node : objectMapper.createArrayNode().add(node);
            for (JsonNode n : arr) {
                double mem = n.path("memPct").asDouble(0);
                double cpu = n.path("cpuPct").asDouble(0);
                boolean cpuHit = useCpuThreshold && cpu >= 0 && cpu > cpuThreshold;
                boolean memHit = mem > memThreshold;
                if (!cpuHit && !memHit) {
                    continue;
                }
                Map<String, String> info = new LinkedHashMap<>();
                info.put("user", "-");
                info.put("pid", String.valueOf(n.path("Id").asInt(0)));
                if (cpu < 0) {
                    info.put("cpu", "n/a");
                } else {
                    info.put("cpu", String.format("%.1f", cpu));
                }
                info.put("mem", String.format("%.1f", mem));
                info.put("etime", "-");
                info.put("command", n.path("ProcessName").asText(""));
                info.put("state", "running");
                processList.add(info);
            }
        } catch (Exception e) {
            log.warn("解析 Windows 进程 JSON 失败: {}", e.getMessage());
        }
        return processList;
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

    @Tool(name = "terminateProcess",
            description = "终止进程（写操作）：默认 dryRun 预览；真实结束须 dryRun=false 且 confirmKill=true。禁止结束 PID≤10")
    public String terminateProcess(
            @ToolParam(description = "进程 PID", required = true) Integer pid,
            @ToolParam(description = "信号：TERM|KILL，默认 TERM", required = false) String signal,
            @ToolParam(description = "true/null=预览", required = false) Boolean dryRun,
            @ToolParam(description = "真实结束须 true", required = false) Boolean confirmKill
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        if (pid == null || pid <= 10) {
            return buildErrorResponse("PID 无效或属于受保护范围（须 >10）", startTime);
        }
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);
        if (!isDryRun && !Boolean.TRUE.equals(confirmKill)) {
            return buildErrorResponse("真实结束进程需 dryRun=false 且 confirmKill=true", startTime);
        }
        String sig = signal == null || signal.isBlank() ? "TERM" : signal.trim().toUpperCase();
        List<String> cmd;
        if (OsRuntime.isWindows()) {
            cmd = "KILL".equals(sig)
                    ? List.of("taskkill", "/F", "/PID", String.valueOf(pid))
                    : List.of("taskkill", "/PID", String.valueOf(pid));
        } else {
            cmd = "KILL".equals(sig) || "SIGKILL".equals(sig)
                    ? List.of("kill", "-9", String.valueOf(pid))
                    : List.of("kill", "-15", String.valueOf(pid));
        }
        if (isDryRun) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("mode", "DRY-RUN");
            preview.put("pid", pid);
            preview.put("plan", String.join(" ", cmd));
            String data = objectMapper.writeValueAsString(preview);
            return buildSuccessResponse(data, System.currentTimeMillis() - startTime, false);
        }
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(cmd, 30_000L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pid", pid);
        body.put("command", String.join(" ", cmd));
        body.put("success", result.success());
        body.put("output", result.output());
        if (!result.success()) {
            body.put("error", result.error());
        }
        String data = objectMapper.writeValueAsString(body);
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(data, duration, false);
    }

    /** MCP HTTP 网关：operation = list | kill */
    public String executeGateway(
            String operation,
            Double minCpu,
            Double minMem,
            Integer pid,
            String signal,
            Boolean dryRun,
            Boolean confirmKill
    ) throws JsonProcessingException {
        String op = operation == null || operation.isBlank() ? "list" : operation.trim().toLowerCase();
        return switch (op) {
            case "list", "ps" -> listProcesses(minCpu, minMem);
            case "kill", "terminate", "stop" -> terminateProcess(pid, signal, dryRun, confirmKill);
            default -> buildErrorResponse("未知 operation: " + operation + "；可选 list|kill", System.currentTimeMillis());
        };
    }
}
