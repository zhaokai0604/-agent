package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.ChatWriteExecutionPolicy;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OpsPathExtractSupport;
import com.award.log.util.OsRuntime;
import com.award.log.util.WindowsDriveSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    @Tool(name = "cleanTempFiles", description = "清理临时文件：默认仅预览；真实删除需 dryRun=false 且 confirmDelete=true。支持 removeDirectory 删除指定子目录（须在白名单 Temp 子路径下）。")
    public String cleanTempFiles(
            @ToolParam(description = "要清理的目录路径（默认 /tmp）", required = false) String path,
            @ToolParam(description = "删除多少天前的文件（默认 7 天；0=不限年龄）", required = false) Integer days,
            @ToolParam(description = "null/true=仅预览；false=允许删除（须 confirmDelete=true）", required = false) Boolean dryRun,
            @ToolParam(description = "必须为 true 才会在 dryRun=false 时执行 rm", required = false) Boolean confirmDelete,
            @ToolParam(description = "true=递归删除整个目录（仅允许 Temp 白名单下的子目录，禁止删 Temp 根）", required = false) Boolean removeDirectory
    ) throws JsonProcessingException {
        return cleanTempFilesInternal(path, days, dryRun, confirmDelete, removeDirectory);
    }

    /** 兼容旧调用方（未传 removeDirectory）。 */
    public String cleanTempFiles(String path, Integer days, Boolean dryRun, Boolean confirmDelete)
            throws JsonProcessingException {
        return cleanTempFilesInternal(path, days, dryRun, confirmDelete, null);
    }

    /**
     * 扫描所有白名单 Temp 根（Windows 自动包含各盘符 Temp / 用户 TEMP），汇总垃圾文件预览。
     */
    @Tool(name = "scanAllTempJunk",
            description = "扫描全部盘符下白名单 Temp 目录，汇总可清理文件数量（默认预览，不删除）")
    public String scanAllTempJunk(
            @ToolParam(description = "统计多少天前的文件（0=含今日）", required = false) Integer days)
            throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        int targetDays = days != null ? Math.min(Math.max(days, 0), 3650) : 7;
        List<Map<String, Object>> locations = new ArrayList<>();
        int totalFiles = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String root : opsPathPolicy.snapshotTempCleanRoots()) {
            String norm = OsRuntime.isWindows()
                    ? OpsPathPolicy.normalizeWindowsPath(root)
                    : opsPathPolicy.normalizeUnixPath(root);
            if (norm.isEmpty() || !seen.add(norm.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!opsPathPolicy.isAllowedCleanDirectory(root)) {
                continue;
            }
            String preview = cleanTempFilesInternal(root, targetDays, true, false, false);
            JsonNode rootNode = objectMapper.readTree(preview);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", root);
            row.put("drive", OsRuntime.isWindows() ? WindowsDriveSupport.driveLabel(root) : "/");
            row.put("allowed", true);
            boolean systemTemp = WindowsDriveSupport.isSystemElevatedTempRoot(root);
            boolean writable = !OsRuntime.isWindows() || probePathWritable(root);
            row.put("writable", writable);
            row.put("systemElevatedTemp", systemTemp);
            row.put("autoCleanEligible", writable && !systemTemp);
            if (systemTemp) {
                row.put("skipReason", "系统 Temp 需管理员权限，已跳过自动删除（请用管理员启动后端或手动清理）");
            } else if (!writable) {
                row.put("skipReason", "当前进程无写权限");
            }
            if (!rootNode.path("success").asBoolean(false)) {
                row.put("error", rootNode.path("error").asText("预览失败"));
                row.put("filesFound", 0);
                locations.add(row);
                continue;
            }
            JsonNode data = parseNestedData(rootNode);
            int found = data != null && data.has("filesFound") ? data.get("filesFound").asInt(0) : 0;
            row.put("filesFound", found);
            row.put("mode", data != null ? data.path("mode").asText("DRY-RUN") : "DRY-RUN");
            totalFiles += found;
            locations.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "SCAN");
        payload.put("days", targetDays);
        payload.put("platform", OsRuntime.isWindows() ? "windows" : "unix");
        if (OsRuntime.isWindows()) {
            payload.put("drives", WindowsDriveSupport.listLogicalDrives());
        }
        payload.put("totalFilesFound", totalFiles);
        payload.put("locationCount", locations.size());
        payload.put("locations", locations);
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(objectMapper.writeValueAsString(payload), duration, false);
    }

    private JsonNode parseNestedData(JsonNode toolRoot) {
        try {
            JsonNode dataNode = toolRoot.get("data");
            if (dataNode == null) {
                return null;
            }
            if (dataNode.isTextual()) {
                return objectMapper.readTree(dataNode.asText());
            }
            return dataNode;
        } catch (Exception e) {
            return null;
        }
    }

    private String cleanTempFilesInternal(String path, Integer days, Boolean dryRun, Boolean confirmDelete,
                                          Boolean removeDirectory)
            throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = path != null && !path.isBlank() ? path.trim() : defaultTempTargetPath();
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                opsPathPolicy, targetPath, days, dryRun, confirmDelete, removeDirectory);
        int targetDays = write.days();
        boolean isDryRun = write.dryRun();
        boolean removeDir = write.removeDirectory();
        boolean confirmDeleteResolved = write.confirmDelete();

        log.info("开始执行临时文件清理，路径: {}, 天数: {}, 模式: {}, removeDirectory: {}",
            targetPath, targetDays, isDryRun ? "DRY-RUN" : "DELETE", removeDir);

        try {
            if (!opsPathPolicy.isAllowedCleanDirectory(targetPath)) {
                return buildErrorResponse(opsPathPolicy.rejectReason("清理目录不在白名单或命中黑名单"), startTime);
            }
            if (!isDryRun && !confirmDeleteResolved) {
                return buildErrorResponse(
                        "安全策略：实际删除需同时设置 dryRun=false 且 confirmDelete=true（或通过 MCP 二次确认流程执行）",
                        startTime);
            }
            if (removeDir) {
                if (!opsPathPolicy.isAllowedCleanDirectory(targetPath)) {
                    return buildErrorResponse(opsPathPolicy.rejectReason("清理目录不在白名单或命中黑名单"), startTime);
                }
                if (!OpsPathExtractSupport.isCleanableSubDirectory(opsPathPolicy, targetPath)) {
                    return buildErrorResponse(
                            "安全策略：removeDirectory 仅允许删除 Temp 白名单下的子目录，不可直接删除 Temp 根目录",
                            startTime);
                }
                return removeDirectoryTree(targetPath, isDryRun, startTime);
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
                        if (delResult.success() && !isSimulatedDryRun(delResult)) {
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
                if (filesDeleted == 0 && !fileList.isEmpty()) {
                    return buildErrorResponse(
                            "未删除任何文件（可能全局演练模式仍开启、路径无写权限或文件被占用）", startTime);
                }
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

    private String removeDirectoryTree(String targetPath, boolean isDryRun, long startTime)
            throws JsonProcessingException {
        if (OsRuntime.isWindows()) {
            String safe = targetPath.replace("'", "''");
            if (isDryRun) {
                String psPreview = "$p='" + safe + "';"
                        + "if (-not (Test-Path -LiteralPath $p)) { '{\"exists\":false}' ; exit 0 };"
                        + "$s=(Get-ChildItem -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum);"
                        + "$c=(Get-ChildItem -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object).Count;"
                        + "'{\"exists\":true,\"entries\":'+$c+',\"bytes\":'+[int64]$s.Sum+'}'";
                AbstractCommandExecutor.CommandResult preview = minPrivilegeExecutor.executeSafely(
                        List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psPreview),
                        120_000L);
                long duration = System.currentTimeMillis() - startTime;
                String data = String.format(
                        "{\"mode\":\"DRY-RUN\",\"removeDirectory\":true,\"path\":\"%s\",\"preview\":%s,\"platform\":\"windows\"}",
                        targetPath, preview.success() && preview.output() != null ? preview.output().trim() : "{}");
                return buildSuccessResponse(data, duration, false);
            }
            String psDelete = "$p='" + safe + "';"
                    + "if (-not (Test-Path -LiteralPath $p)) { Write-Error 'path not found'; exit 1 };"
                    + "$s=(Get-ChildItem -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum;"
                    + "Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction Stop;"
                    + "'{\"removed\":true,\"bytes\":'+[int64]$s+'}'";
            AbstractCommandExecutor.CommandResult del = minPrivilegeExecutor.executeSafely(
                    List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psDelete),
                    120_000L);
            if (isSimulatedDryRun(del)) {
                return buildErrorResponse(
                        "全局演练模式已开启：Remove-Item 被模拟执行，磁盘未变化。请在系统配置关闭演练模式并重启。",
                        startTime);
            }
            if (!del.success()) {
                return buildErrorResponse("删除目录失败: " + (del.error() != null ? del.error() : del.output()), startTime);
            }
            long bytes = 0;
            try {
                JsonNode node = objectMapper.readTree(del.output() != null ? del.output().trim() : "{}");
                bytes = node.path("bytes").asLong(0);
            } catch (Exception ignored) {
            }
            long duration = System.currentTimeMillis() - startTime;
            String data = String.format(
                    "{\"mode\":\"DELETE\",\"removeDirectory\":true,\"path\":\"%s\",\"filesDeleted\":1,\"bytesFreed\":%d,\"platform\":\"windows\"}",
                    targetPath, bytes);
            return buildSuccessResponse(data, duration, false);
        }
        if (isDryRun) {
            AbstractCommandExecutor.CommandResult du = minPrivilegeExecutor.executeSafely(
                    List.of("du", "-sk", targetPath));
            long duration = System.currentTimeMillis() - startTime;
            String data = String.format(
                    "{\"mode\":\"DRY-RUN\",\"removeDirectory\":true,\"path\":\"%s\",\"duKb\":%s}",
                    targetPath, du.success() && du.output() != null ? du.output().trim().split("\\s+")[0] : "0");
            return buildSuccessResponse(data, duration, false);
        }
        AbstractCommandExecutor.CommandResult rm = minPrivilegeExecutor.executeSafely(
                List.of("rm", "-rf", targetPath));
        if (isSimulatedDryRun(rm)) {
            return buildErrorResponse(
                    "全局演练模式已开启：rm 被模拟执行，磁盘未变化。请在系统配置关闭演练模式并重启。",
                    startTime);
        }
        if (!rm.success()) {
            return buildErrorResponse("删除目录失败: " + (rm.error() != null ? rm.error() : rm.output()), startTime);
        }
        long duration = System.currentTimeMillis() - startTime;
        String data = String.format(
                "{\"mode\":\"DELETE\",\"removeDirectory\":true,\"path\":\"%s\",\"filesDeleted\":1}",
                targetPath);
        return buildSuccessResponse(data, duration, false);
    }

    private String defaultTempTargetPath() {
        if (!OsRuntime.isWindows()) {
            return "/tmp";
        }
        String runtimeTemp = System.getProperty("java.io.tmpdir", "").trim();
        if (!runtimeTemp.isEmpty()) {
            return runtimeTemp;
        }
        String envTemp = System.getenv("TEMP");
        if (envTemp != null && !envTemp.isBlank()) {
            return envTemp.trim();
        }
        return "C:\\Temp";
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
                if (delResult.success() && !isSimulatedDryRun(delResult)) {
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
            if (filesDeleted == 0 && !fileList.isEmpty()) {
                return buildErrorResponse(
                        "未删除任何文件（可能全局演练模式仍开启、路径无写权限或文件被占用）", startTime);
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        return buildSuccessResponse(data, duration, false);
    }

    private boolean isSimulatedDryRun(AbstractCommandExecutor.CommandResult result) {
        if (result == null || result.output() == null) {
            return false;
        }
        return result.output().contains("[DRY-RUN]");
    }

    /** 探测目录是否可由当前进程创建/删除文件（Windows 实测写探针）。 */
    private boolean probePathWritable(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return false;
        }
        if (!OsRuntime.isWindows()) {
            return true;
        }
        String safe = targetPath.replace("'", "''");
        String ps = "$p='" + safe + "';"
                + "if(-not(Test-Path -LiteralPath $p)){'NO'};"
                + "else{$t=Join-Path $p ('.threshcore-w-' + [guid]::NewGuid().ToString() + '.tmp');"
                + "try{New-Item -ItemType File -LiteralPath $t -Force -ErrorAction Stop|Out-Null;"
                + "Remove-Item -LiteralPath $t -Force -ErrorAction Stop;'OK'}"
                + "catch{'NO'}}";
        AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps),
                30_000L);
        return result.success() && result.output() != null && result.output().trim().contains("OK");
    }

    private String toJsonArray(List<String> list) throws JsonProcessingException {
        return objectMapper.writeValueAsString(list);
    }

    private String buildSuccessResponse(String data, long duration, boolean cacheHit) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new com.award.log.mcp.ToolResult<>(true, data, null, duration, cacheHit));
    }

    private String buildErrorResponse(String error, long duration) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                new com.award.log.mcp.ToolResult<String>(false, null, error, normalizeDurationMs(duration), false));
    }
}
