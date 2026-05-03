package com.award.log.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class StartupConfigValidator {

    private final Environment environment;

    public StartupConfigValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
        List<String> missing = new ArrayList<>();

        require("spring.datasource.url", missing);
        require("spring.datasource.username", missing);

        if (isEnabled("spring.elasticsearch.enabled")) {
            require("spring.elasticsearch.uris", missing);
            require("spring.elasticsearch.username", missing);
            require("spring.elasticsearch.password", missing);
        }

        if (isEnabled("spring.kafka.enabled")) {
            require("spring.kafka.bootstrap-servers", missing);
        }

        if (StringUtils.hasText(environment.getProperty("spring.mail.username"))) {
            require("spring.mail.password", missing);
        }

        if (prod) {
            require("spring.ai.openai.api-key", missing);
        }

        if (!missing.isEmpty()) {
            String msg = "关键配置缺失: " + String.join(", ", missing);
            if (prod) {
                throw new IllegalStateException(msg);
            }
            log.warn("[启动配置检查] {}。当前非prod环境，继续启动。", msg);
        } else {
            log.info("[启动配置检查] 关键配置检查通过。");
        }
    }

    private void require(String key, List<String> missing) {
        if (!StringUtils.hasText(environment.getProperty(key))) {
            missing.add(key);
        }
    }

    private boolean isEnabled(String key) {
        return Boolean.parseBoolean(environment.getProperty(key, "false"));
    }
}
