package com.award.log.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 磁盘综合分析（只读）：聚合全局使用率 + 可选热点目录扫描，用于「磁盘满 / I/O 异常」排查场景。
 * 热点扫描复用 {@link DiskTool} 的白名单策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskAnalyzeTool {

    private final DiskTool diskTool;
    private final ObjectMapper objectMapper;

    @Tool(name = "analyzeDiskPressure",
            description = "磁盘压力综合诊断：df/分区使用率汇总 + 可选热点目录 du 排序（白名单路径）。")
    public String analyzeDiskPressure(
            @ToolParam(description = "热点扫描根路径，默认 /var/log", required = false) String rootPath,
            @ToolParam(description = "是否执行热点目录扫描", required = false) Boolean includeHotspots,
            @ToolParam(description = "热点扫描返回条数，默认 12", required = false) Integer topN
    ) throws JsonProcessingException {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overview", unwrapNestedToolData(diskTool.checkDiskUsage()));
        boolean hot = Boolean.TRUE.equals(includeHotspots);
        if (hot) {
            String root = (rootPath != null && !rootPath.isBlank()) ? rootPath.trim()
                    : (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "C:\\Windows\\Logs" : "/var/log");
            int n = topN != null ? Math.min(Math.max(topN, 3), 40) : 12;
            out.put("hotspotsRank", unwrapNestedToolData(diskTool.rankDiskUsageUnderPath(root, 2, n)));
        }
        out.put("hint", "结合 overview 使用率与 hotspots 大目录定位垃圾文件或日志膨胀；高使用率时需关注 I/O wait（可用 OsInsightTool / 系统监控）。");
        out.put("durationMs", System.currentTimeMillis() - t0);
        return objectMapper.writeValueAsString(out);
    }

    private Object unwrapNestedToolData(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return parsed;
            }
            Object success = map.get("success");
            if (!(success instanceof Boolean ok)) {
                return parsed;
            }
            if (!ok) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", map.getOrDefault("error", "子工具执行失败"));
                if (map.containsKey("durationMs")) {
                    err.put("durationMs", map.get("durationMs"));
                }
                return err;
            }
            Object data = map.get("data");
            if (!(data instanceof String str)) {
                return data;
            }
            try {
                return objectMapper.readValue(str, Object.class);
            } catch (Exception ignored) {
                return str;
            }
        } catch (Exception e) {
            log.warn("解析 DiskAnalyze 子工具结果失败: {}", e.getMessage());
            return raw;
        }
    }
}
