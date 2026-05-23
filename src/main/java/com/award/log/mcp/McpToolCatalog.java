package com.award.log.mcp;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时扫描 MCP 工具 Bean，集中维护 HTTP 白名单、只读观测集与默认风险分。
 */
@Slf4j
@Component
public class McpToolCatalog {

    private static final String TOOLS_PACKAGE = "com.award.log.mcp.tools";

    private final ApplicationContext applicationContext;

    @Getter
    private Map<String, McpToolDescriptor> descriptors = Map.of();

    @Getter
    private Set<String> httpAllowedToolNames = Set.of();

    @Getter
    private Set<String> readOnlyObservationToolNames = Set.of();

    public McpToolCatalog(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void discover() {
        Map<String, McpToolDescriptor> map = new LinkedHashMap<>();
        registerAlias(map, "DiskInsightTool", null, true, 1.0, true);

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception e) {
                continue;
            }
            Class<?> type = bean.getClass();
            if (!type.getPackageName().startsWith(TOOLS_PACKAGE)) {
                continue;
            }
            if (!type.getSimpleName().endsWith("Tool")) {
                continue;
            }
            McpTool ann = AnnotationUtils.findAnnotation(type, McpTool.class);
            String toolName = ann != null && ann.value() != null && !ann.value().isBlank()
                    ? ann.value().trim()
                    : type.getSimpleName();
            boolean httpAllowed = ann == null || ann.httpAllowed();
            double risk = ann != null ? ann.defaultRiskScore() : defaultRiskFor(toolName);
            boolean readOnly = ann != null ? ann.readOnlyObservation() : isReadOnlyByConvention(toolName);
            if ("FirstMcpTools".equals(toolName)) {
                httpAllowed = false;
            }
            map.putIfAbsent(toolName, new McpToolDescriptor(toolName, type.getName(), httpAllowed, risk, readOnly));
        }

        applyBuiltinRiskDefaults(map);

        descriptors = Collections.unmodifiableMap(map);
        Set<String> http = new LinkedHashSet<>();
        Set<String> readOnly = new LinkedHashSet<>();
        for (McpToolDescriptor d : map.values()) {
            if (d.httpAllowed()) {
                http.add(d.toolName());
            }
            if (d.readOnlyObservation()) {
                readOnly.add(d.toolName());
            }
        }
        httpAllowedToolNames = Collections.unmodifiableSet(http);
        readOnlyObservationToolNames = Collections.unmodifiableSet(readOnly);
        log.info("McpToolCatalog 已加载 {} 个工具描述，HTTP 可执行 {} 个", map.size(), http.size());
    }

    private static void registerAlias(
            Map<String, McpToolDescriptor> map,
            String toolName,
            String beanClassName,
            boolean httpAllowed,
            double risk,
            boolean readOnly) {
        map.put(toolName, new McpToolDescriptor(
                toolName,
                beanClassName,
                httpAllowed,
                risk,
                readOnly));
    }

    private static void applyBuiltinRiskDefaults(Map<String, McpToolDescriptor> map) {
        for (var e : map.entrySet()) {
            McpToolDescriptor d = e.getValue();
            if (d.defaultRiskScore() >= 0) {
                continue;
            }
            double risk = defaultRiskFor(d.toolName());
            map.put(e.getKey(), new McpToolDescriptor(
                    d.toolName(), d.beanClassName(), d.httpAllowed(), risk, d.readOnlyObservation()));
        }
    }

    private static double defaultRiskFor(String toolName) {
        return switch (toolName) {
            case "DiskTool", "ProcessTool", "SystemLoadTool", "LogAnalysisTool", "NetworkTool" -> 0.5;
            case "ConfigCheckTool", "DiskAnalyzeTool", "DiskInsightTool", "PortHealthTool", "SslCertTool",
                 "ConfigDriftTool" -> 1.0;
            case "PrivilegeTool", "OsInsightTool", "DockerTool", "CronJobTool", "FirewallTool", "SystemdTool" -> 2.0;
            case "AutonomousOpsTool" -> 5.0;
            case "CleanTempTool", "DiskOpsTool" -> 4.5;
            case "LogCleanupTool", "LogOpsTool" -> 5.5;
            case "ServiceRestartTool", "ServiceOpsTool" -> 7.0;
            case "ContainerOpsTool" -> 6.0;
            case "ProcessOpsTool" -> 6.5;
            default -> 2.5;
        };
    }

    private static boolean isReadOnlyByConvention(String toolName) {
        return switch (toolName) {
            case "DiskTool", "DiskAnalyzeTool", "DiskInsightTool", "ProcessTool", "SystemLoadTool",
                 "LogAnalysisTool", "NetworkTool", "ConfigCheckTool", "PrivilegeTool", "OsInsightTool",
                 "PortHealthTool", "DockerTool", "CronJobTool", "FirewallTool", "SslCertTool",
                 "SystemdTool", "ConfigDriftTool" -> true;
            default -> false;
        };
    }

    public boolean isHttpAllowed(String toolName) {
        McpToolDescriptor d = descriptors.get(toolName);
        return d != null && d.httpAllowed();
    }

    public boolean isReadOnlyObservation(String toolName) {
        return readOnlyObservationToolNames.contains(toolName);
    }

    public double defaultRiskScore(String toolName) {
        McpToolDescriptor d = descriptors.get(toolName);
        if (d == null) {
            return defaultRiskFor(toolName);
        }
        return d.resolvedRiskScore(defaultRiskFor(toolName));
    }

    /**
     * 供 HTTP /api/mcp/tools 与前端动态元数据同步。
     */
    public List<Map<String, Object>> listToolsForApi() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpToolDescriptor d : descriptors.values()) {
            if (!d.httpAllowed()) {
                continue;
            }
            ToolUiMeta meta = TOOL_UI_META.getOrDefault(d.toolName(), ToolUiMeta.generic(d.toolName()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", d.toolName());
            row.put("description", meta.title());
            row.put("summary", meta.summary());
            row.put("endpoint", "/mcp/execute");
            row.put("commandHint", meta.commandHint());
            row.put("readOnly", d.readOnlyObservation());
            row.put("defaultRiskScore", d.resolvedRiskScore(defaultRiskFor(d.toolName())));
            out.add(row);
        }
        return out;
    }

    private record ToolUiMeta(String title, String summary, String commandHint) {
        static ToolUiMeta generic(String name) {
            return new ToolUiMeta(name, "MCP 运维工具", "system command");
        }
    }

    private static final Map<String, ToolUiMeta> TOOL_UI_META = buildUiMeta();

    private static Map<String, ToolUiMeta> buildUiMeta() {
        Map<String, ToolUiMeta> m = new LinkedHashMap<>();
        m.put("DiskTool", new ToolUiMeta("磁盘检查工具", "检查磁盘使用情况", "df -h"));
        m.put("DiskInsightTool", new ToolUiMeta("磁盘热点扫描", "du 排序定位大目录（白名单路径）", "du -xk --max-depth=N"));
        m.put("ProcessTool", new ToolUiMeta("进程管理工具", "查看和管理系统进程", "ps aux --sort=-%cpu"));
        m.put("SystemLoadTool", new ToolUiMeta("系统负载工具", "检查系统负载状态", "top -bn1 + /proc/loadavg"));
        m.put("LogAnalysisTool", new ToolUiMeta("日志分析工具", "分析系统日志", "日志采样 + Drain-Plus"));
        m.put("CleanTempTool", new ToolUiMeta("文件清理工具", "清理临时文件", "find 白名单临时目录 + 可选 rm"));
        m.put("ConfigCheckTool", new ToolUiMeta("配置检查工具", "检查配置文件", "配置语法探测"));
        m.put("NetworkTool", new ToolUiMeta("网络诊断工具", "诊断网络连接", "ping / traceroute"));
        m.put("PrivilegeTool", new ToolUiMeta("权限验证工具", "验证文件权限", "权限探测"));
        m.put("OsInsightTool", new ToolUiMeta("OS 深度感知", "journalctl / ss / lsof", "journalctl | ss | lsof"));
        m.put("LogCleanupTool", new ToolUiMeta("日志清理（写）", "清理白名单下过旧日志，默认仅预览", "find + rm"));
        m.put("ServiceRestartTool", new ToolUiMeta("服务重启（写）", "白名单内非关键服务重启", "systemctl restart"));
        m.put("DiskAnalyzeTool", new ToolUiMeta("磁盘分析", "综合 df + 热点目录", "df + du"));
        m.put("PortHealthTool", new ToolUiMeta("端口健康检查", "TCP 端口连通性探测", "TCP Socket"));
        m.put("DockerTool", new ToolUiMeta("Docker 巡检", "容器列表与 inspect 摘要", "docker ps / inspect"));
        m.put("CronJobTool", new ToolUiMeta("计划任务", "crontab / schtasks 只读列表", "crontab -l | schtasks"));
        m.put("FirewallTool", new ToolUiMeta("防火墙状态", "ufw / firewalld / netsh", "ufw | firewalld | netsh"));
        m.put("SslCertTool", new ToolUiMeta("SSL 证书", "TLS 证书有效期与主题", "TLS 握手读证书"));
        m.put("SystemdTool", new ToolUiMeta("服务状态", "systemctl 失败单元与服务状态", "systemctl --failed"));
        m.put("AutonomousOpsTool", new ToolUiMeta("自主运维（融合）", "全工具感知→诊断→修复→验证", "融合编排"));
        m.put("ConfigDriftTool", new ToolUiMeta("配置漂移", "配置文件 checksum 快照对比（只读）", "checksum diff"));
        m.put("DiskOpsTool", new ToolUiMeta("磁盘运维（读写合一）", "operation: df|hotspots|analyze|clean-temp", "gateway"));
        m.put("LogOpsTool", new ToolUiMeta("日志运维（读写合一）", "operation: analyze|cleanup", "gateway"));
        m.put("ServiceOpsTool", new ToolUiMeta("服务运维（读写合一）", "operation: failed|status|restart", "gateway"));
        m.put("ContainerOpsTool", new ToolUiMeta("容器运维（读写合一）", "operation: list|inspect|restart|stop", "gateway"));
        m.put("ProcessOpsTool", new ToolUiMeta("进程运维（读写合一）", "operation: list|kill", "gateway"));
        return Collections.unmodifiableMap(m);
    }
}
