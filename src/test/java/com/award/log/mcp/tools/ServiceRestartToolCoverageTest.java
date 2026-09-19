package com.award.log.mcp.tools;

import com.award.log.config.AgentOpsProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceRestartToolCoverageTest {

    @Mock private MinPrivilegeExecutor executor;
    private ServiceRestartTool tool;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        AgentOpsProperties props = new AgentOpsProperties();
        props.getServiceRestart().setAllowlist(List.of("nginx", "award-log"));
        tool = new ServiceRestartTool(org.mockito.Mockito.mock(ExecutorService.class), new ObjectMapper(), executor, props);
    }

    @Test
    void validatesAllowlistAndConfirmationBeforeExecution() throws Exception {
        assertTrue(tool.restartService(" ", true, false).contains("serviceName 不能为空"));
        assertTrue(tool.restartService("mysql", true, false).contains("不在允许重启列表"));
        assertTrue(tool.restartService("nginx", false, false).contains("需 dryRun=false"));
        assertTrue(tool.restartService("nginx", true, null).contains("DRY-RUN"));
    }

    @Test
    void executesWindowsRestartAndProbesResult() throws Exception {
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "ok", "", 0, false),
                new AbstractCommandExecutor.CommandResult(true, "Running", "", 0, false));
        String result = tool.restartService("NGINX.service", false, true);
        assertTrue(result.contains("EXECUTED"));
        assertTrue(result.contains("active"));
    }

    @Test
    void rejectsSimulatedAndFailedRestartAndHandlesProbeFailure() throws Exception {
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "[DRY-RUN] simulated", "", 0, false));
        assertTrue(tool.restartService("nginx", false, true).contains("演练模式"));
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(false, "", "access denied", 1, false));
        assertTrue(tool.restartService("nginx", false, true).contains("服务重启失败"));
        when(executor.executeSafely(anyList(), anyLong())).thenReturn(
                new AbstractCommandExecutor.CommandResult(true, "ok", "", 0, false),
                new AbstractCommandExecutor.CommandResult(false, "", "probe down", 1, false));
        assertTrue(tool.restartService("nginx", false, true).contains("unknown"));
    }
}
