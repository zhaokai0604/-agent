package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Prompt Injection 边车推理配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.injection-guard")
public class InjectionGuardProperties {

    private boolean sidecarEnabled = false;

    private String sidecarUrl = "http://127.0.0.1:8099";

    private int timeoutMs = 2000;

    private boolean scanToolOutput = true;
}
