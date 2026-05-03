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

@Slf4j
@Component
public class CleanTempTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;

    @Autowired
    public CleanTempTool(
            @Autowired(required = false) ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy) {
        super(mcpToolExecutor != null ? mcpToolExecutor : Executors.newCachedThreadPool());
        this.objectMapper = objectMapper;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
    }

    @Tool(name = "cleanTempFiles", description = "清理临时文件：默认 dry-run 仅预览；真实删除需 dryRun=false 且 confirmDelete=true。路径受限（如 /tmp,/var/tmp）。")
    public String cleanTempFiles(
            @ToolParam(description = "要清理的目录路径（默认 /tmp）", required = false) String path,
            @ToolParam(description = "删除多少天前的文件（默认 7 天）", required = false) Integer days,
            @ToolParam(description = "null/true=仅预览；false=允许删除（须 confirmDelete=true）", required = false) Boolean dryRun,
            @ToolParam(description = "必须为 true 才会在 dryRun=false 时执行 rm", required = false) Boolean confirmDelete
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = path != null && !path.isBlank() ? path.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\Temp" : "/tmp");
        int targetDays = days != null ? Math.min(Math.max(days, 1), 3650) : 7;
        boolean isDryRun = dryRun == null || Boolean.TRUE.equals(dryRun);

        log.info("开始执行临时文件清理，路径: {}, 天数: {}, 模式: {}",
            targetPath, targetDays, isDryRun ? "DRY-RUN" : "实际删除");

        try {
            if (!opsPathPolicy.isAllowedCleanDirectory(targetPath)) {
                return buildErrorResponse(opsPathPolicy.rejectReason("清理目录不在白名单或命中黑名单"), startTime);
            }
            if (!isDryRun && !Boolean.TRUE.equals(confirmDelete)) {
                return buildErrorResponse(
                        "安全策略：实际删除需同时设置 dryRun=false 且 confirmDelete=true（或通过 MCP 二次确认流程执行）",
                        startTime);
            }

            if (OsRuntime.isWindows()) {
                return cleanTempFilesWindows(targetPath, targetDays, isDryRun, startTime);
            }

            AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("find", targetPath, "-type", "f", "-mtime", "+" + targetDays, "-print")
            );

            if (result.output() == null || result.output().isBlank()) {
                long duration = System.currentTimeMillis() - startTime;
                String data = String.format("{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"message\":\"没有找到符合条件的文件\"}",
                    isDryRun ? "DRY-RUN" : "DELETE", targetPath, targetDays);
                return buildSuccessResponse(data, duration, false);
            }

            List<String> fileList = new ArrayList<>();
            for (String line : result.output().split("\n")) {
                if (!line.isBlank()) {
                    fileList.add(line);
                }
            }

            int filesDeleted = 0;
            List<String> previewFiles = new ArrayList<>();

            if (!isDryRun) {
                for (String file : fileList) {
                    try {
                        AbstractCommandExecutor.CommandResult delResult = minPrivilegeExecutor.executeSafely(List.of("rm", "-f", file));
                        if (delResult.success()) {
                            filesDeleted++;
                        }
                    } catch (Exception e) {
                        log.debug("删除文件失败: {}", file);
                    }
                }
            }

            int previewCount = Math.min(fileList.size(), 20);
            for (int i = 0; i < previewCount; i++) {
                previewFiles.add(fileList.get(i));
            }

            String data;
            if (isDryRun) {
                data = String.format(
                    "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"previewCount\":%d,\"preview\":%s,\"note\":\"默认预览。真实删除：dryRun=false 且 confirmDelete=true\"}",
                    targetPath, targetDays, fileList.size(), previewCount, toJsonArray(previewFiles)
                );
            } else {
                data = String.format(
                    "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"filesDeleted\":%d}",
                    targetPath, targetDays, fileList.size(), filesDeleted
                );
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("临时文件清理完成，模式: {}, 文件数: {}, 耗时: {}ms",
                isDryRun ? "DRY-RUN" : "DELETE", fileList.size(), duration);

            return buildSuccessResponse(data, duration, false);

        } catch (Exception e) {
            log.error("执行临时文件清理时发生异常", e);
            return buildErrorResponse("执行临时文件清理时发生异常: " + e.getMessage(), startTime);
        }
    }

    private String cleanTempFilesWindows(String targetPath, int targetDays, boolean isDryRun, long startTime)
            throws JsonProcessingException {
        String safe = targetPath.replace("'", "''");
        String ps = "$p='" + safe + "';$d=" + targetDays
                + ";Get-ChildItem -LiteralPath $p -Recurse -File -ErrorAction SilentlyContinue"
                + "|Where-Object{$_.LastWriteTime -lt (Get-Date).AddDays(-$d)}"
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
                log.warn("解析 Windows 清理文件列表失败: {}", e.getMessage());
            }
        }
        if (fileList.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            String data = String.format("{\"mode\":\"%s\",\"path\":\"%s\",\"days\":%d,\"filesFound\":0,\"message\":\"没有找到符合条件的文件\",\"platform\":\"windows\"}",
                    isDryRun ? "DRY-RUN" : "DELETE", targetPath, targetDays);
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
        int previewCount = Math.min(fileList.size(), 20);
        for (int i = 0; i < previewCount; i++) {
            previewFiles.add(fileList.get(i));
        }
        String data;
        if (isDryRun) {
            data = String.format(
                    "{\"mode\":\"DRY-RUN\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"previewCount\":%d,\"preview\":%s,\"platform\":\"windows\",\"note\":\"默认预览。真实删除：dryRun=false 且 confirmDelete=true\"}",
                    targetPath, targetDays, fileList.size(), previewCount, toJsonArray(previewFiles));
        } else {
            data = String.format(
                    "{\"mode\":\"DELETE\",\"path\":\"%s\",\"days\":%d,\"filesFound\":%d,\"filesDeleted\":%d,\"platform\":\"windows\"}",
                    targetPath, targetDays, fileList.size(), filesDeleted);
        }
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(data, duration, false);
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
