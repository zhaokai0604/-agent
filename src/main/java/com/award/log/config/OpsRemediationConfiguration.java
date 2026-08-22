package com.award.log.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用运维自愈/修复方案属性 {@link OpsRemediationProperties}。
 * 自身不含 Bean，仅作为配置绑定入口。
 */
@Configuration
@EnableConfigurationProperties(OpsRemediationProperties.class)
public class OpsRemediationConfiguration {
}
