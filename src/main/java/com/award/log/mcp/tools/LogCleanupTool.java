package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志目录陈旧文件清理（写操作）：默认 dry-run；真实删除需 dryRun=false 且 confirmDelete=true。
 * 路径仅限 {@link OpsPathPolicy#isAllowedLogCleanupPath}（默认 /var/log 树下）。
 */
@Slf4j
@Component
public class LogCleanupTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public LogCleanupTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "cleanupOldLogs",
            description = "清理日志根目录下过期的日志文件（默认仅预览）。路径受限（如 /var/log）；删除需 dryRun=false 且 confirmDelete=true。")
    public String cleanupOldLogs(
            @ToolParam(description = "日志根路径，默认 Linux /var/log", required = false) String path,
            @ToolParam(description = "删除多少天未修改的文件，默认 30，最大 3650", required = false) Integer days,
            @ToolParam(description = "true/null=仅预览；false=允许删除（须 confirmDelete=true）", required = false) Boolean dryRun,
            @ToolParam(description = "必须为 true 才会在 dryRun=false 时执行 rm", required = false) Boolean confirmDelete
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = path != null && !path.isBlank() ? path.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\Logs" : "/var/log");
        int targetDays = days != null ? Math.min(Math.max(days, 1), 3650) : 30;
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);

        log.info("[LogCleanup] path={} days={} dryRun={}", targetPath, targetDays, isDryRun);

        try {
            if (!opsPathPolicy.isAllowedLogCleanupPath(targetPath)) {
                return buildErrorResponse(opsPathPolicy.rejectReason("日志清理路径不在白名单"), startTime);
            }
            if (!isDryRun && !Boolean.TRUE.equals(confirmDelete)) {
                return buildErrorResponse(
                        "安全策略：实际删除需 dryRun=false 且 confirmDelete=true（或通过 MCP 二次确认后执行）",
                        startTime);
            }

            if (OsRuntime.isWindows()) {
                return cleanupLogsWindows(targetPath, targetDays, isDryRun, startTime);
            }

            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                    List.of("find", targetPath, "-type", "f", "(", "-name", "*.log", "-o", "-name", "*.log.*", "-o",
                            "-name", "*.gz", "-o", "-name", "*.xz", "-o", "-name", "*.bz2", ")", "-mtime",
                            "+" + targetDays, "-print"),
                    120_000L);

            if (result.output() == null || result.output().isBlank()) {
                long duration = System.currentTimeMillis() - startTime;
                String data = String.format(
                        "{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"message\":\"没有符合条件的陈旧日志文件\"}",
                        isDryRun ? "DRY-RUN" : "DELETE", jsonEscape(targetPath), targetDays);
                return buildSuccessResponse(data, duration, false);
            }

            List<String> fileList = new ArrayList<>();
            for (String line : result.output().split("\n")) {
                if (!line.isBlank()) {
                    fileList.add(line.trim());
                }
            }

            int filesDeleted = 0;
            List<String> previewFiles = new ArrayList<>();
            if (!isDryRun) {
                for (String file : fileList) {
                    AbstractCommandExecutor.CommandResult delResult = minPrivilegeExecutor.executeSafely(List.of("rm", "-f", file));
                    if (delResult.success()) {
                        filesDeleted++;
                    }
                }
            }

            int previewCount = Math.min(fileList.size(), 25);
            for (int i = 0; i < previewCount; i++) {
                previewFiles.add(fileList.get(i));
            }

            String data;
            if (isDryRun) {
                data = String.format(
                        "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"previewCount\":%d,\"preview\":%s,\"note\":\"仅列出候选。真实删除：dryRun=false 且 confirmDelete=true\"}",
                        jsonEscape(targetPath), targetDays, fileList.size(), previewCount, toJsonArray(previewFiles));
            } else {
                data = String.format(
                        "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"filesDeleted\":%d}",
                        jsonEscape(targetPath), targetDays, fileList.size(), filesDeleted);
            }
            return buildSuccessResponse(data, System.currentTimeMillis() - startTime, false);
        } catch (Exception e) {
            log.error("[LogCleanup] 异常", e);
            return buildErrorResponse("日志清理失败: " + e.getMessage(), startTime);
        }
    }

    private String cleanupLogsWindows(String targetPath, int targetDays, boolean isDryRun, long startTime)
            throws JsonProcessingException {
        String safe = targetPath.replace("'", "''");
        String ps = "$p='" + safe + "';$d=" + targetDays
                + ";Get-ChildItem -LiteralPath $p -Recurse -File -ErrorAction SilentlyContinue"
                + "|Where-Object{$_.LastWriteTime -lt (Get-Date).AddDays(-$d) -and ($_.Extension -match '\\.log|gz|xz|bz2' -or $_.Name -match '\\.log\\.')}"
                + "|Select-Object -ExpandProperty FullName|ConvertTo-Json -Compress";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps), 120_000L);
        List<String> fileList = new ArrayList<>();
        if (result.success() && result.output() != null && !result.output().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(result.output());
                if (node.isArray()) {
                    for (JsonNode n : node) {
                        fileList.add(n.asText());
                    }
                } else {
                    fileList.add(node.asText());
                }
            } catch (Exception e) {
                log.warn("解析 Windows 日志清理列表失败: {}", e.getMessage());
            }
        }
        if (fileList.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            String data = String.format(
                    "{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"platform\":\"windows\"}",
                    isDryRun ? "DRY-RUN" : "DELETE", jsonEscape(targetPath), targetDays);
            return buildSuccessResponse(data, duration, false);
        }
        int filesDeleted = 0;
        List<String> previewFiles = new ArrayList<>();
        if (!isDryRun) {
            for (String file : fileList) {
                String f = file.replace("'", "''");
                AbstractCommandExecutor.CommandResult delResult = minPrivilegeExecutor.executeSafely(List.of(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "Remove-Item -LiteralPath '" + f + "' -Force -ErrorAction SilentlyContinue"));
                if (delResult.success()) {
                    filesDeleted++;
                }
            }
        }
        int previewCount = Math.min(fileList.size(), 25);
        for (int i = 0; i < previewCount; i++) {
            previewFiles.add(fileList.get(i));
        }
        String data;
        if (isDryRun) {
            data = String.format(
                    "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"previewCount\":%d,\"preview\":%s,\"platform\":\"windows\"}",
                    jsonEscape(targetPath), targetDays, fileList.size(), previewCount, toJsonArray(previewFiles));
        } else {
            data = String.format(
                    "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"filesDeleted\":%d,\"platform\":\"windows\"}",
                    jsonEscape(targetPath), targetDays, fileList.size(), filesDeleted);
        }
        return buildSuccessResponse(data, System.currentTimeMillis() - startTime, false);
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJsonArray(List<String> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<>(true, data, null, duration, cacheHit));
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<String>(false, null, error, duration, false));
    }
}
