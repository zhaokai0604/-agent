package com.award.log.mcp;

import com.award.log.config.OpsDryRunProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinPrivilegeExecutorCoverageTest {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void globalDryRunInterceptsMutationButLeavesReadOnlyCommandExecutable() {
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        dryRun.setGlobal(true);
        MinPrivilegeExecutor executor = new MinPrivilegeExecutor("", false, dryRun, executorService);
        assertEquals("award-agent", executor.getRunAsUser());
        assertFalse(executor.isMinPrivilegeEnabled());
        assertTrue(executor.defaultTimeoutMillis() > 0);

        AbstractCommandExecutor.CommandResult simulated = executor.executeSafely(
                List.of("rm", "-rf", "C:/Temp/example"));
        assertTrue(simulated.success());
        assertTrue(simulated.output().contains("DRY-RUN"));

        AbstractCommandExecutor.CommandResult read = executor.executeSafely(
                List.of("cmd", "/c", "echo read-only"), 2_000L);
        assertTrue(read.success());
        assertTrue(read.output().contains("read-only"));
    }

    @Test
    void sandboxDelegationAndWindowsDirectExecutionAreExplicit() {
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        MinPrivilegeExecutor executor = new MinPrivilegeExecutor("agent", true, dryRun, executorService);
        com.award.log.mcp.sandbox.SandboxExecutor sandbox = mock(com.award.log.mcp.sandbox.SandboxExecutor.class);
        when(sandbox.sandboxActive()).thenReturn(true);
        when(sandbox.executeSafely(List.of("cmd", "/c", "echo sandbox"), 1234L))
                .thenReturn(new AbstractCommandExecutor.CommandResult(true, "sandbox", null, 0, false));
        executor.setSandboxExecutor(sandbox);

        AbstractCommandExecutor.CommandResult delegated = executor.executeSafely(
                List.of("cmd", "/c", "echo sandbox"), 1234L);
        assertEquals("sandbox", delegated.output());

        when(sandbox.sandboxActive()).thenReturn(false);
        AbstractCommandExecutor.CommandResult direct = executor.executeWithoutSandbox(
                List.of("cmd", "/c", "echo direct"), 2_000L);
        assertTrue(direct.success());
        assertTrue(direct.output().contains("direct"));

        AbstractCommandExecutor.CommandResult rootPath = executor.executeWithRoot(
                List.of("cmd", "/c", "echo ordinary"));
        assertTrue(rootPath.success());
    }

    @Test
    void windowsSpecificPrivilegeHeuristicsAndNullDryRunAreCovered() {
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        MinPrivilegeExecutor executor = new MinPrivilegeExecutor("agent", true, dryRun, executorService);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(executor, "requiresRootPrivilege",
                List.of("net", "stop", "demo")));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(executor, "requiresRootPrivilege",
                List.of("sc", "start", "demo")));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(executor, "requiresRootPrivilege",
                List.of("powershell", "Remove-Item -Recurse C:/Temp/demo")));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(executor, "requiresRootPrivilege",
                List.of("cmd", "/c", "echo safe")));

        OpsDryRunProperties global = new OpsDryRunProperties();
        global.setGlobal(true);
        MinPrivilegeExecutor dry = new MinPrivilegeExecutor("agent", true, global, executorService);
        AbstractCommandExecutor.CommandResult readOnly = dry.executeSafely(
                List.of("cmd", "/c", "echo safe"), 1000L);
        assertTrue(readOnly.success());
    }
}
