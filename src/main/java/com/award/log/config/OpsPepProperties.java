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

    /** 旧配置兼容字段；运行时必须为 true，关闭配置会阻止启动。 */
    private boolean enabled = true;

    @jakarta.annotation.PostConstruct
    public void validateMandatoryPipeline() {
        if (!enabled) throw new IllegalStateException(
                "ops.pep.enabled=false 已停用：写操作必须经过统一门控；请移除旧关闭配置或设置为 true");
    }

    /** HMAC 密钥，生产须通过环境变量 OPS_CONFIRM_HMAC_KEY 注入 */
    @lombok.ToString.Exclude
    private String confirmHmacKey = "";

    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.ToString.Exclude
    private final String processConfirmHmacKey = createProcessKey();

    /** Local pending tickets live in memory, so an equally scoped random key is safe. */
    public String getConfirmHmacKey() {
        return confirmHmacKey == null || confirmHmacKey.isBlank()
                ? processConfirmHmacKey : confirmHmacKey;
    }

    private static String createProcessKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    /** 确认令牌 TTL（毫秒） */
    private long confirmTtlMs = 900_000L;

    /** 回收站 TTL（天） */
    private int recycleTtlDays = 7;
}
