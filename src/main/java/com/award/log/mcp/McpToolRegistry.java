package com.award.log.mcp;

import com.award.log.mcp.tools.*;
import com.award.log.security.McpToolSurface;
import com.award.log.security.ReadOnlySurfaceDenylist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class McpToolRegistry {

    private final List<Object> toolBeans;

    public McpToolRegistry(
            LogAnalysisTool logAnalysisTool,
            DiskTool diskTool,
            ProcessTool processTool,
            SystemLoadTool systemLoadTool,
            CleanTempTool cleanTempTool,
            LogCleanupTool logCleanupTool,
            ServiceRestartTool serviceRestartTool,
            DiskAnalyzeTool diskAnalyzeTool,
            ConfigCheckTool configCheckTool,
            NetworkTool networkTool,
            PrivilegeTool privilegeTool,
            OsInsightTool osInsightTool,
            PortHealthTool portHealthTool,
            DockerTool dockerTool,
            CronJobTool cronJobTool,
            FirewallTool firewallTool,
            SslCertTool sslCertTool,
            SystemdTool systemdTool,
            AutonomousOpsTool autonomousOpsTool,
            ConfigDriftTool configDriftTool,
            DiskOpsTool diskOpsTool,
            LogOpsTool logOpsTool,
            ServiceOpsTool serviceOpsTool,
            ContainerOpsTool containerOpsTool,
            ProcessOpsTool processOpsTool,
            FirstMcpTools firstMcpTools
    ) {
        this.toolBeans = new ArrayList<>();

        log.info("开始注册 MCP Tools（DiskInsightTool 由 DiskTool.rankDiskUsageUnderPath 提供，执行入口见 McpExecuteController）");

        this.toolBeans.add(logAnalysisTool);
        this.toolBeans.add(diskTool);
        this.toolBeans.add(processTool);
        this.toolBeans.add(systemLoadTool);
        this.toolBeans.add(cleanTempTool);
        this.toolBeans.add(logCleanupTool);
        this.toolBeans.add(serviceRestartTool);
        this.toolBeans.add(diskAnalyzeTool);
        this.toolBeans.add(configCheckTool);
        this.toolBeans.add(networkTool);
        this.toolBeans.add(privilegeTool);
        this.toolBeans.add(osInsightTool);
        this.toolBeans.add(portHealthTool);
        this.toolBeans.add(dockerTool);
        this.toolBeans.add(cronJobTool);
        this.toolBeans.add(firewallTool);
        this.toolBeans.add(sslCertTool);
        this.toolBeans.add(systemdTool);
        this.toolBeans.add(autonomousOpsTool);
        this.toolBeans.add(configDriftTool);
        this.toolBeans.add(diskOpsTool);
        this.toolBeans.add(logOpsTool);
        this.toolBeans.add(serviceOpsTool);
        this.toolBeans.add(containerOpsTool);
        this.toolBeans.add(processOpsTool);
        // FirstMcpTools 仅用于对话 @Tool 实验；HTTP /api/mcp 白名单见 McpInvocationSecurityGate.allowedToolNames()
        this.toolBeans.add(firstMcpTools);

        log.info("MCP Tools 注册完成，共 {} 个工具（HTTP 可执行见 McpToolCatalog）", toolBeans.size());
    }

    public List<Object> getToolBeans() {
        return new ArrayList<>(toolBeans);
    }

    /**
     * READ_ONLY 面下排除 {@link ReadOnlySurfaceDenylist} 中的写类工具 Bean，避免模型误绑可执行写操作。
     */
    public List<Object> getToolBeansForSurface(McpToolSurface surface, ReadOnlySurfaceDenylist denylist) {
        if (surface == null || surface == McpToolSurface.FULL || denylist == null) {
            return getToolBeans();
        }
        return toolBeans.stream()
                .filter(bean -> !denylist.denies(bean.getClass().getSimpleName()))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
