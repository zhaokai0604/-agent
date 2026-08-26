package com.award.log.mcp.sandbox;

import com.award.log.config.SandboxProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsShieldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root Helper Unix Socket 客户端：受限 root 动作白名单执行。
 */
@Slf4j
@Component
public class RootHelperClient {

    private final SandboxProperties sandboxProperties;

    public RootHelperClient(SandboxProperties sandboxProperties) {
        this.sandboxProperties = sandboxProperties;
    }

    public AbstractCommandExecutor.CommandResult execute(List<String> command) {
        if (!sandboxProperties.getRootHelper().isEnabled()) {
            return new AbstractCommandExecutor.CommandResult(false, null, "Root helper disabled", -1, false);
        }
        String joined = String.join(" ", command);
        try {
            // 简化实现：通过 socat/nc 发送 JSON 行协议；生产用独立守护进程 scripts/deploy/root_helper.py
            ProcessBuilder pb = new ProcessBuilder(
                    "python3",
                    "scripts/deploy/root_helper_client.py",
                    sandboxProperties.getRootHelper().getSocketPath(),
                    joined);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(sandboxProperties.getRootHelper().getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new AbstractCommandExecutor.CommandResult(false, null, "Root helper timeout", -1, false);
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.exitValue();
            return new AbstractCommandExecutor.CommandResult(code == 0, out.toString().trim(), null, code, false);
        } catch (Exception e) {
            log.warn("Root helper call failed: {}", e.getMessage());
            return new AbstractCommandExecutor.CommandResult(false, null, e.getMessage(), -1, false);
        }
    }

    public boolean isConfigured() {
        return sandboxProperties.getRootHelper().isEnabled();
    }
}
