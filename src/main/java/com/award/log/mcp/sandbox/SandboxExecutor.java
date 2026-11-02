package com.award.log.mcp.sandbox;

import com.award.log.config.SandboxProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsShieldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 沙箱执行器：OpsShield 拦截 → bwrap 包装 → 或 Root Helper 提权。
 */
@Slf4j
@Component
public class SandboxExecutor {

    private final SandboxProperties sandboxProperties;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final BwrapCommandBuilder bwrapCommandBuilder;
    private final RootHelperClient rootHelperClient;
    private final OpsShieldService opsShieldService;

    public SandboxExecutor(
            SandboxProperties sandboxProperties,
            @Lazy MinPrivilegeExecutor minPrivilegeExecutor,
            BwrapCommandBuilder bwrapCommandBuilder,
            RootHelperClient rootHelperClient,
            OpsShieldService opsShieldService) {
        this.sandboxProperties = sandboxProperties;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.bwrapCommandBuilder = bwrapCommandBuilder;
        this.rootHelperClient = rootHelperClient;
        this.opsShieldService = opsShieldService;
    }

    public AbstractCommandExecutor.CommandResult executeSafely(List<String> command) {
        return executeSafely(command, minPrivilegeExecutor.defaultTimeoutMillis());
    }

    public AbstractCommandExecutor.CommandResult executeSafely(List<String> command, long timeoutMillis) {
        if (command == null || command.isEmpty()) {
            return new AbstractCommandExecutor.CommandResult(false, null, "empty command", -1, false);
        }
        String joined = String.join(" ", command);
        OpsShieldService.ShieldVerdict shield = opsShieldService.evaluateShell(joined);
        if (!shield.allowed()) {
            log.warn("OpsShield blocked command: {}", shield.reason());
            return new AbstractCommandExecutor.CommandResult(false, null, shield.reason(), -1, false);
        }

        if (requiresRootHelper(command) && rootHelperClient.isConfigured()) {
            return rootHelperClient.execute(command);
        }

        if (sandboxProperties.isEnabled() && bwrapCommandBuilder.isAvailable()) {
            List<String> wrapped = bwrapCommandBuilder.wrap(command);
            log.debug("Executing in bwrap sandbox: {}", wrapped);
            return minPrivilegeExecutor.executeWithoutSandbox(wrapped, timeoutMillis);
        }

        return minPrivilegeExecutor.executeWithoutSandbox(command, timeoutMillis);
    }

    private boolean requiresRootHelper(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", command).toLowerCase();
        return joined.contains("systemctl") && (joined.contains("restart") || joined.contains("reload"))
                || joined.contains("resolvectl") || joined.contains("rndc");
    }

    public boolean sandboxActive() {
        return sandboxProperties.isEnabled() && bwrapCommandBuilder.isAvailable();
    }
}
