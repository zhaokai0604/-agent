package com.award.log.config;

import com.award.log.governance.OpsGovernanceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用运维治理属性 {@link OpsGovernanceProperties}。
 * 自身不含 Bean，仅绑定配置前缀。
 */
@Configuration
@EnableConfigurationProperties(OpsGovernanceProperties.class)
public class OpsGovernanceConfiguration {
}
