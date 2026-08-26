package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PEP 执行管线与 HMAC 确认令牌配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops.pep")
public class OpsPepProperties {

    /** 是否强制写操作经 OpsExecutionPipeline */
    private boolean enabled = true;

    /** HMAC 密钥，生产须通过环境变量 OPS_CONFIRM_HMAC_KEY 注入 */
    private String confirmHmacKey = "";

    /** 确认令牌 TTL（毫秒） */
    private long confirmTtlMs = 900_000L;

    /** 回收站 TTL（天） */
    private int recycleTtlDays = 7;
}
