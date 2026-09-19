package com.award.log.mcp.sandbox;

import com.award.log.config.SandboxProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsShieldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxCoverageTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void bwrapBuilderPreservesInnerCommandAndFiltersExtraMountSyntax() {
        SandboxProperties props = new SandboxProperties();
        props.setBwrapBinary("bwrap-test");
        props.setExtraBindMounts(Arrays.asList("/var/lib/app:/app", " /data ", "", null, "bad:mount:entry"));
        BwrapCommandBuilder builder = new BwrapCommandBuilder(props);
        assertTrue(builder.wrap(List.of("echo", "hello")).contains("--"));
        List<String> wrapped = builder.wrap(List.of("echo", "hello"));
        assertTrue(wrapped.contains("/app"));
        assertTrue(wrapped.get(wrapped.size() - 2).equals("echo"));
        assertTrue(builder.wrap(List.of()).isEmpty());
        assertTrue(builder.wrap(null).isEmpty());
        assertFalse(builder.isAvailable());
    }

    @Test
    void sandboxExecutorBlocksShieldAndChoosesPlainSandboxOrRootHelper() {
        SandboxProperties props = new SandboxProperties();
        MinPrivilegeExecutor min = mock(MinPrivilegeExecutor.class);
        BwrapCommandBuilder bwrap = mock(BwrapCommandBuilder.class);
        RootHelperClient root = mock(RootHelperClient.class);
        OpsShieldService shield = mock(OpsShieldService.class);
        when(shield.evaluateShell(any())).thenReturn(new OpsShieldService.ShieldVerdict(true, ""));
        when(root.isConfigured()).thenReturn(false);
        when(bwrap.isAvailable()).thenReturn(false);
        when(min.executeWithoutSandbox(anyList(), anyLong())).thenReturn(ok("plain"));
        SandboxExecutor sandbox = new SandboxExecutor(props, min, bwrap, root, shield);

        assertFalse(sandbox.executeSafely(List.of()).success());
        assertTrue(sandbox.executeSafely(List.of("echo", "plain"), 1000L).success());
        assertFalse(sandbox.sandboxActive());

        when(shield.evaluateShell("rm -rf /"))
                .thenReturn(new OpsShieldService.ShieldVerdict(false, "blocked"));
        assertFalse(sandbox.executeSafely(List.of("rm", "-rf", "/"), 1000L).success());

        when(shield.evaluateShell("echo sandbox")).thenReturn(new OpsShieldService.ShieldVerdict(true, ""));
        when(bwrap.isAvailable()).thenReturn(true);
        props.setEnabled(true);
        when(bwrap.wrap(List.of("echo", "sandbox"))).thenReturn(List.of("bwrap", "--", "echo", "sandbox"));
        assertTrue(sandbox.sandboxActive());
        assertTrue(sandbox.executeSafely(List.of("echo", "sandbox"), 1000L).success());

        when(root.isConfigured()).thenReturn(true);
        when(root.execute(List.of("systemctl", "restart", "demo"))).thenReturn(ok("root-helper"));
        assertTrue(sandbox.executeSafely(List.of("systemctl", "restart", "demo"), 1000L).success());
    }

    @Test
    void rootHelperDisabledAndMissingClientFailClosed() {
        SandboxProperties props = new SandboxProperties();
        RootHelperClient client = new RootHelperClient(props);
        assertFalse(client.isConfigured());
        assertFalse(client.execute(List.of("systemctl", "restart", "demo")).success());
        props.getRootHelper().setEnabled(true);
        props.getRootHelper().setTimeoutMs(20);
        assertTrue(client.isConfigured());
        assertFalse(client.execute(List.of("command-not-found-threshcore")).success());
    }

    private static AbstractCommandExecutor.CommandResult ok(String output) {
        return new AbstractCommandExecutor.CommandResult(true, output, null, 0, false);
    }
}
