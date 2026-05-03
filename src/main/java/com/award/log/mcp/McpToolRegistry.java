package com.award.log.mcp;

import com.award.log.mcp.tools.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
        this.toolBeans.add(firstMcpTools);

        log.info("MCP Tools 注册完成，共 {} 个工具", toolBeans.size());
    }

    public List<Object> getToolBeans() {
        return new ArrayList<>(toolBeans);
    }
}
