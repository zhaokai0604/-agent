package com.award.log.mcp.tools;

import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkToolCoverageTest {

    @Mock private MinPrivilegeExecutor executor;
    private NetworkTool tool;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        tool = new NetworkTool(org.mockito.Mockito.mock(ExecutorService.class), new ObjectMapper(), executor);
    }

    @Test
    void validatesTargetAndParsesHealthyWindowsPing() throws Exception {
        assertTrue(tool.diagnoseNetwork(" ", "ping", 2).contains("目标地址不能为空"));
        when(executor.executeSafely(anyList())).thenReturn(new AbstractCommandExecutor.CommandResult(
                true, "Packets: Sent = 4, Received = 4, Lost = 0 (0% loss)\nApproximate round trip times in milli-seconds:\n    Average = 12ms", "", 0, false));
        String result = tool.diagnoseNetwork("example.com", "PING", 4);
        assertTrue(result.contains("example.com"));
        assertTrue(result.contains("packetLossPercent"));
    }

    @Test
    void returnsWarningForLossAndErrorForEmptyFailedCommand() throws Exception {
        when(executor.executeSafely(anyList())).thenReturn(new AbstractCommandExecutor.CommandResult(
                true, "Packets: Sent = 4, Received = 0, Lost = 4 (100% loss)", "", 1, false));
        assertTrue(tool.diagnoseNetwork("10.0.0.1", "ping", 4).contains("WARN"));
        when(executor.executeSafely(anyList())).thenReturn(new AbstractCommandExecutor.CommandResult(
                false, "", "network down", 1, false));
        assertTrue(tool.diagnoseNetwork("10.0.0.1", "ping", 4).contains("ping 命令执行失败"));
    }

    @Test
    void parsesTracerouteHopsAndRawFallback() throws Exception {
        when(executor.executeSafely(anyList())).thenReturn(new AbstractCommandExecutor.CommandResult(
                true, "  1    10.0.0.1    10.0.0.1    *\n  2    *    *    *", "", 0, false));
        assertTrue(tool.diagnoseNetwork("example.com", "trace", 5).contains("hopCount"));
        when(executor.executeSafely(anyList())).thenReturn(new AbstractCommandExecutor.CommandResult(
                true, "Tracing route to example.com\nrequest timed out", "", 0, false));
        assertTrue(tool.diagnoseNetwork("example.com", "traceroute", 5).contains("raw"));
    }
}
