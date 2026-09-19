package com.award.log.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractCommandExecutorCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    void synchronousExecutionCapturesStdoutStderrAndExitCodes() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Harness harness = new Harness(executor);
            AbstractCommandExecutor.CommandResult success = harness.sync(cmd("echo stdout"));
            assertTrue(success.success());
            assertTrue(success.output().contains("stdout"));
            assertEquals(0, success.exitCode());

            AbstractCommandExecutor.CommandResult failure = harness.sync(cmd("echo stderr 1>&2 & exit /b 7"));
            assertFalse(failure.success());
            assertEquals(7, failure.exitCode());
            assertTrue(failure.error().contains("stderr"));

            AbstractCommandExecutor.CommandResult invalid = harness.sync(List.of("command-that-does-not-exist-xyz"));
            assertFalse(invalid.success());
            assertTrue(invalid.error() != null && !invalid.error().isBlank());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retryRepeatsFailedCommandAndZeroTimeoutWaitsForCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Harness harness = new Harness(executor);
            Path marker = tempDir.resolve("retry.marker");
            String markerPath = marker.toString();
            String retryCommand = "if exist \"" + markerPath + "\" (del \"" + markerPath
                    + "\" & exit /b 0) else (type nul > \"" + markerPath + "\" & exit /b 1)";
            AbstractCommandExecutor.CommandResult retried = harness.retry(cmd(retryCommand), 1000);
            assertTrue(retried.success());
            assertFalse(Files.exists(marker));

            AbstractCommandExecutor.CommandResult noTimeout = harness.retry(cmd("echo no-timeout"), 0);
            assertTrue(noTimeout.success());
            assertTrue(noTimeout.output().contains("no-timeout"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutForceKillsProcessAndAsyncWrapperReturnsResults() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Harness harness = new Harness(executor);
            AbstractCommandExecutor.CommandResult timeout = harness.retry(
                    cmd("ping 127.0.0.1 -n 10 > nul"), 20);
            assertFalse(timeout.success());
            assertTrue(timeout.timeout());
            assertEquals("command timeout", timeout.error());

            AbstractCommandExecutor.CommandResult async = harness.async(cmd("echo async"))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(async.success());
            assertTrue(async.output().contains("async"));

            AbstractCommandExecutor.CommandResult asyncFailure = harness.async(
                    List.of("command-that-does-not-exist-async-xyz"))
                    .get(5, TimeUnit.SECONDS);
            assertFalse(asyncFailure.success());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void privilegeAndPlatformHelpersFollowWindowsPolicy() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Harness harness = new Harness(executor);
            assertTrue(harness.root(List.of("docker", "ps")));
            assertTrue(harness.root(List.of("systemctl", "status", "x")));
            assertTrue(harness.root(List.of("echo", "sudo something")));
            assertTrue(harness.root(List.of("rm", "-rf /")));
            assertFalse(harness.root(List.of("echo", "hello")));
            assertTrue(harness.sudo("agent", List.of("docker", "ps")));
            assertTrue(harness.asUser(cmd("echo as-user"), "award-agent").success());
            assertTrue(harness.asUser(cmd("echo current-user"), null).success());
            assertEquals(0, harness.normalize(0));
            assertTrue(harness.normalize(System.currentTimeMillis()) >= 0);
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<String> cmd(String command) {
        return List.of("cmd", "/c", command);
    }

    private static final class Harness extends AbstractCommandExecutor {
        private Harness(ExecutorService executor) {
            super(executor);
        }

        private CommandResult sync(List<String> command) {
            return executeCommand(command);
        }

        private CommandResult retry(List<String> command, long timeoutMillis) {
            return executeWithRetry(command, timeoutMillis);
        }

        private CompletableFuture<CommandResult> async(List<String> command) {
            return executeCommandAsync(command);
        }

        private CommandResult asUser(List<String> command, String user) {
            return executeAsUser(command, user, 1000);
        }

        private boolean root(List<String> command) {
            return requiresRootPrivilege(command);
        }

        private boolean sudo(String user, List<String> command) {
            return checkSudoPrivilege(user, command);
        }

        private long normalize(long duration) {
            return normalizeDurationMs(duration);
        }
    }
}
