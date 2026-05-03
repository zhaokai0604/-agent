package com.award.log.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
@Component
public class MinPrivilegeExecutor extends AbstractCommandExecutor {

    private static final String DEFAULT_RUN_AS_USER = "award-agent";

    private final String runAsUser;
    private final boolean minPrivilegeEnabled;

    public MinPrivilegeExecutor(
            @Value("${agent.run-as-user:" + DEFAULT_RUN_AS_USER + "}") String runAsUser,
            @Value("${agent.min-privilege.enabled:true}") boolean minPrivilegeEnabled,
            ExecutorService executorService
    ) {
        super(executorService);
        this.runAsUser = runAsUser != null && !runAsUser.isEmpty() ? runAsUser : DEFAULT_RUN_AS_USER;
        this.minPrivilegeEnabled = minPrivilegeEnabled;
        log.info("最小权限执行器初始化，运行用户: {}, minPrivilegeEnabled: {}", this.runAsUser, minPrivilegeEnabled);
    }

    public CommandResult executeSafely(List<String> command) {
        return executeSafely(command, commandTimeoutMillis());
    }

    /**
     * @param timeoutMillis 单轮进程等待超时（毫秒），例如 {@code du} 大目录扫描可适当增大。
     */
    public CommandResult executeSafely(List<String> command, long timeoutMillis) {
        if (!minPrivilegeEnabled) {
            return executeWithRetry(command, timeoutMillis);
        }
        if (requiresRootPrivilege(command)) {
            return executeWithPrivilegeCheck(command, timeoutMillis);
        }
        return executeAsUser(command, runAsUser, timeoutMillis);
    }

    public CommandResult executeWithRoot(List<String> command) {
        if (!requiresRootPrivilege(command)) {
            log.warn("命令不需要 root 权限，使用普通用户执行: {}", command);
            return executeAsUser(command, runAsUser);
        }
        return executeWithPrivilegeCheck(command, commandTimeoutMillis());
    }

    private CommandResult executeWithPrivilegeCheck(List<String> command, long timeoutMillis) {
        boolean hasPrivilege = checkSudoPrivilege(runAsUser, command);
        if (!hasPrivilege) {
            String errorMsg = String.format("用户 %s 没有执行命令的权限: %s", runAsUser, command);
            log.error(errorMsg);
            return new CommandResult(false, null, errorMsg, -1, false);
        }
        return executeAsUser(command, runAsUser, timeoutMillis);
    }

    public String getRunAsUser() {
        return runAsUser;
    }
}