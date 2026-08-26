package com.award.log.mcp.sandbox;

import com.award.log.config.SandboxProperties;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 根据路径策略生成 bwrap 命令行。
 */
@Slf4j
@Component
public class BwrapCommandBuilder {

    private static final Set<String> DEFAULT_BINDS = Set.of(
            "/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/tmp", "/var/tmp", "/var/log", "/proc", "/dev");

    private final SandboxProperties sandboxProperties;

    public BwrapCommandBuilder(SandboxProperties sandboxProperties) {
        this.sandboxProperties = sandboxProperties;
    }

    public List<String> wrap(List<String> innerCommand) {
        if (innerCommand == null || innerCommand.isEmpty()) {
            return List.of();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(sandboxProperties.getBwrapBinary());
        cmd.add("--die-with-parent");
        cmd.add("--unshare-all");
        cmd.add("--proc");
        cmd.add("/proc");
        cmd.add("--dev");
        cmd.add("/dev");
        for (String mount : DEFAULT_BINDS) {
            cmd.add("--ro-bind");
            cmd.add(mount);
            cmd.add(mount);
        }
        if (sandboxProperties.getExtraBindMounts() != null) {
            for (String extra : sandboxProperties.getExtraBindMounts()) {
                if (extra == null || extra.isBlank()) {
                    continue;
                }
                String[] parts = extra.split(":");
                if (parts.length == 2) {
                    cmd.add("--ro-bind");
                    cmd.add(parts[0].trim());
                    cmd.add(parts[1].trim());
                } else {
                    cmd.add("--ro-bind");
                    cmd.add(extra.trim());
                    cmd.add(extra.trim());
                }
            }
        }
        cmd.add("--");
        cmd.addAll(innerCommand);
        return cmd;
    }

    public boolean isAvailable() {
        if (!OsRuntime.isUnixLike()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(sandboxProperties.getBwrapBinary(), "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            log.debug("bwrap not available: {}", e.getMessage());
            return false;
        }
    }
}
