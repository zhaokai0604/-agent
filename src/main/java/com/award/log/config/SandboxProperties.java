package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * bwrap 沙箱与 Root Helper 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.sandbox")
public class SandboxProperties {

    private boolean enabled = false;

    private String bwrapBinary = "bwrap";

    /** 额外 bind mount（host:container 或 path） */
    private List<String> extraBindMounts = new ArrayList<>();

    private RootHelper rootHelper = new RootHelper();

    @Data
    public static class RootHelper {
        private boolean enabled = false;
        private String socketPath = "/var/run/award-root-helper.sock";
        private int timeoutMs = 30_000;
    }
}
