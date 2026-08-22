package com.award.log.mcp;

import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/**
 * MCP 工具进程执行基类：同步/异步拉起外部命令并收集 stdout/stderr。
 * <p>
 * 实现要点：
 * <ul>
 *   <li>{@code STREAM_DRAIN_EXECUTOR} 为有界线程池（core=2, max=16, queue=64），避免每条命令各开排空线程导致线程爆炸；</li>
 *   <li>stdout/stderr 使用 {@link StringBuffer} 缓冲，保证跨排空线程的并发写入安全；</li>
 *   <li>进程超时后 {@code destroyForcibly}，再 {@link #awaitDrain} 最多等待 1s 收尾，防止管道阻塞与 Future 泄漏。</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractCommandExecutor {

    private static final int ASYNC_COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500;
    /** 防止 df/journalctl 等洪泛输出撑爆堆内存；超出截断并标记 */
    private static final int MAX_STDOUT_CHARS = 512 * 1024;
    private static final int MAX_STDERR_CHARS = 256 * 1024;

    /**
     * 有界排空线程池：避免每条命令各开两个无界线程导致线程爆炸。
     * 队列满时 CallerRuns，把压力打回调用线程而不是无限排队。
     */
    private static final ExecutorService STREAM_DRAIN_EXECUTOR = new ThreadPoolExecutor(
            2,
            16,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "mcp-stream-drain");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    protected final ExecutorService executor;

    protected AbstractCommandExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /** 同步执行单条命令时的默认超时（毫秒）；子类可覆盖，或通过 {@link #executeWithRetry(List, long)} 传入更大值。 */
    protected long commandTimeoutMillis() {
        return 10_000L;
    }

    /** @see McpDurationSupport#normalize(long) */
    protected long normalizeDurationMs(long durationOrStartTime) {
        return McpDurationSupport.normalize(durationOrStartTime);
    }

    /** 在工具线程池异步执行，外层再加固定超时；超时/异常均返回失败 {@link CommandResult}。 */
    protected CompletableFuture<CommandResult> executeCommandAsync(List<String> command) {
        return CompletableFuture
            .supplyAsync(() -> executeWithRetry(command, commandTimeoutMillis()), executor)
            .orTimeout(ASYNC_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex instanceof TimeoutException) {
                    log.warn("命令执行超时: {}", command);
                    return new CommandResult(false, null, "command timeout", -1, true);
                }
                log.error("命令执行异常: {}", ex.getMessage());
                return new CommandResult(false, null, ex.getMessage(), -1, false);
            });
    }

    protected CommandResult executeCommand(List<String> command) {
        return executeWithRetry(command, commandTimeoutMillis());
    }

    protected CompletableFuture<CommandResult> executeAsUserAsync(List<String> command, String runAsUser) {
        return CompletableFuture
            .supplyAsync(() -> executeAsUser(command, runAsUser, commandTimeoutMillis()), executor)
            .orTimeout(ASYNC_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex instanceof TimeoutException) {
                    log.warn("命令执行超时: {} (as user: {})", command, runAsUser);
                    return new CommandResult(false, null, "command timeout", -1, true);
                }
                log.error("命令执行异常: {}", ex.getMessage());
                return new CommandResult(false, null, ex.getMessage(), -1, false);
            });
    }

    protected CommandResult executeAsUser(List<String> command, String runAsUser) {
        return executeAsUser(command, runAsUser, commandTimeoutMillis());
    }

    /**
     * 以指定用户执行：Linux {@code sudo -u}；Windows 无 sudo，降级为当前进程用户。
     */
    protected CommandResult executeAsUser(List<String> command, String runAsUser, long timeoutMillis) {
        if (runAsUser == null || runAsUser.isEmpty()) {
            return executeWithRetry(command, timeoutMillis);
        }
        // Windows 无 sudo -u：直接以当前服务进程用户执行（与 application-dev 关闭 min-privilege 效果一致）
        if (OsRuntime.isWindows()) {
            log.debug("Windows 环境：跳过 sudo，直接执行 {}", command);
            return executeWithRetry(command, timeoutMillis);
        }

        List<String> sudoCommand = new java.util.ArrayList<>();
        sudoCommand.add("sudo");
        sudoCommand.add("-u");
        sudoCommand.add(runAsUser);
        sudoCommand.addAll(command);

        log.info("以用户 {} 执行命令: {}", runAsUser, command);
        return executeWithRetry(sudoCommand, timeoutMillis);
    }

    /** 启发式判断命令是否需要 root（systemctl/docker/iptables 等）。 */
    protected boolean requiresRootPrivilege(List<String> command) {
        String firstArg = command.get(0);
        String fullCommand = String.join(" ", command);

        // 检查需要root权限的命令
        return firstArg.equals("systemctl") ||
               firstArg.equals("docker") ||
               firstArg.equals("mount") ||
               firstArg.equals("umount") ||
               firstArg.equals("iptables") ||
               firstArg.equals("ifconfig") ||
               firstArg.equals("route") ||
               firstArg.equals("useradd") ||
               firstArg.equals("userdel") ||
               firstArg.equals("groupadd") ||
               firstArg.equals("groupdel") ||
               fullCommand.contains("sudo") ||
               fullCommand.contains("chmod 777") ||
               fullCommand.contains("rm -rf /");
    }

    /** 通过 {@code sudo -l -U} 检查目标用户是否被授权执行该命令（Windows 直接放行）。 */
    protected boolean checkSudoPrivilege(String runAsUser, List<String> command) {
        if (OsRuntime.isWindows()) {
            return true;
        }
        try {
            List<String> checkCommand = List.of(
                "sudo", "-l", "-U", runAsUser
            );
            CommandResult result = executeWithRetry(checkCommand, 30_000L);
            if (!result.success()) {
                log.warn("检查 sudo 权限失败: {}", result.error());
                return false;
            }

            String sudoOutput = result.output();
            String commandStr = String.join(" ", command);
            return sudoOutput.contains(command.get(0));
        } catch (Exception e) {
            log.error("检查 sudo 权限异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 供 {@link MinPrivilegeExecutor} 在关闭最小权限模式时直接执行原始命令。
     */
    protected CommandResult executeWithRetry(List<String> command) {
        return executeWithRetry(command, commandTimeoutMillis());
    }

    /** 失败最多重试 {@value #MAX_RETRIES} 次；每次独立进程超时。 */
    protected CommandResult executeWithRetry(List<String> command, long timeoutMillis) {
        long perAttemptTimeout = timeoutMillis > 0 ? timeoutMillis : commandTimeoutMillis();
        int attempts = 0;
        while (attempts <= MAX_RETRIES) {
            attempts++;
            try {
                CommandResult result = doExecute(command, perAttemptTimeout);
                if (result.success() || attempts > MAX_RETRIES) {
                    return result;
                }
                log.warn("命令执行失败，重试 {}/{}: {}", attempts, MAX_RETRIES, command);
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new CommandResult(false, null, "interrupted", -1, false);
            }
        }
        return new CommandResult(false, null, "max retries exceeded", -1, false);
    }

    private CommandResult doExecute(List<String> command, long timeoutMillis) {
        // StringBuffer 线程安全：drain 在 STREAM_DRAIN_EXECUTOR 写，主线程读
        // StringBuffer：stdout/stderr 由排空线程并发写入，需线程安全
        StringBuffer output = new StringBuffer();
        StringBuffer errorOutput = new StringBuffer();
        int exitCode = -1;

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(
                    () -> drainStream(process.getInputStream(), output, MAX_STDOUT_CHARS, "stdout"),
                    STREAM_DRAIN_EXECUTOR);
            CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(
                    () -> drainStream(process.getErrorStream(), errorOutput, MAX_STDERR_CHARS, "stderr"),
                    STREAM_DRAIN_EXECUTOR);

            if (timeoutMillis <= 0) {
                CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
                exitCode = process.waitFor();
            } else {
                boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    awaitDrain(stdoutFuture, stderrFuture);
                    log.warn("命令执行超时 ({} ms): {}", timeoutMillis, command);
                    return new CommandResult(false, output.toString(), "command timeout", -1, true);
                }
                CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
                exitCode = process.exitValue();
            }

            boolean success = exitCode == 0;
            String errorMsg = errorOutput.length() > 0 ? errorOutput.toString() : null;

            if (!success && errorMsg == null) {
                errorMsg = "exit code: " + exitCode;
            }

            return new CommandResult(success, output.toString(), errorMsg, exitCode, false);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, null, "interrupted", -1, false);
        } catch (Exception e) {
            log.error("执行命令失败: {} - {}", command, e.getMessage());
            return new CommandResult(false, null, e.getMessage(), -1, false);
        }
    }

    /** Windows 控制台输出多为 GBK，Linux 为 UTF-8。 */
    private static Charset commandOutputCharset() {
        if (OsRuntime.isWindows()) {
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    /** 超时杀进程后最多再等 1s 排空管道，避免 Future 悬挂；超时则 cancel。 */
    private static void awaitDrain(CompletableFuture<Void> stdoutFuture, CompletableFuture<Void> stderrFuture) {
        try {
            CompletableFuture.allOf(stdoutFuture, stderrFuture).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
        }
    }

    private static void drainStream(java.io.InputStream stream, StringBuffer target, int maxChars, String label) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, commandOutputCharset()))) {
            String line;
            int chars = 0;
            while ((line = reader.readLine()) != null) {
                int inc = line.length() + (target.length() > 0 ? 1 : 0);
                if (chars + inc > maxChars) {
                    if (target.length() > 0) {
                        target.append("\n");
                    }
                    target.append("...[").append(label).append(" truncated at ").append(maxChars).append(" chars]");
                    break;
                }
                chars += inc;
                if (target.length() > 0) {
                    target.append("\n");
                }
                target.append(line);
            }
        } catch (Exception e) {
            if (target.length() > 0) {
                target.append("\n");
            }
            target.append("...[").append(label).append(" read error: ").append(e.getMessage()).append("]");
        }
    }

    /** 单次命令结果。{@code timeout=true} 表示进程等待超时（已被强制销毁）。 */
    public record CommandResult(
        boolean success,
        String output,
        String error,
        int exitCode,
        boolean timeout
    ) {}
}
